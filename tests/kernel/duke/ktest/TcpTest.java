package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.net.Ipv4;
import duke.kernel.net.Network;
import duke.kernel.net.Tcp;
import duke.kernel.time.HpetClock;
import java.util.function.Supplier;

/**
 * ktest's NIC hears its own frames back, so a connection to our own address goes out over the
 * wire and back in on both ends: a listener and a connection to it test each other.
 */
final class TcpTest {

    private static final long TIMEOUT_NANOS = 30_000_000_000L;

    static void testAConnectionToItselfMovesAMegabyteEachWay() throws InterruptedException {
        awaitAddress();
        transfer(Network.address(), 7001, 1 << 20, 0);
    }

    // Every seventh segment never leaves, data, acknowledgments, SYNs and FINs alike.
    static void testLostSegmentsAreSentAgain() throws InterruptedException {
        awaitAddress();
        transfer(Network.address(), 7002, 256 << 10, 7);
    }

    static void testLoopbackMovesAMegabyteEachWay() throws InterruptedException {
        transfer(Ipv4.address(127, 0, 0, 1), 7005, 1 << 20, 0);
    }

    static void testLoopbackLosesSegmentsToo() throws InterruptedException {
        transfer(Ipv4.address(127, 0, 0, 1), 7006, 64 << 10, 7);
    }

    static void testAllOf127IsLoopback() throws InterruptedException {
        transfer(Ipv4.address(127, 1, 2, 3), 7007, 4096, 0);
    }

    static void testNobodyListeningOnLoopbackRefusesTheConnection() {
        Tcp.Connection connection = new Tcp.Connection();
        assertEquals(-Tcp.ECONNREFUSED, connection.connect(Ipv4.address(127, 0, 0, 1), 7008), "connect");
    }

    static void testNobodyListeningRefusesTheConnection() throws InterruptedException {
        awaitAddress();
        Tcp.Connection connection = new Tcp.Connection();
        assertEquals(-Tcp.ECONNREFUSED, connection.connect(Network.address(), 7003), "connect");
        byte[] buffer = new byte[1];
        assertEquals(-Tcp.ECONNREFUSED, connection.read(buffer, 0, 1), "read after the refusal");
    }

    static void testAnInterruptEndsAnAccept() throws InterruptedException {
        Tcp.Listener listener = Tcp.listen(7009);
        Thread[] waiter = new Thread[1];
        boolean[] stop = new boolean[1];
        Tcp.Connection[] accepted = {new Tcp.Connection()};
        Supplier<Boolean> previous = Tcp.setInterrupt(null);
        Tcp.setInterrupt(() -> stop[0] && Thread.currentThread() == waiter[0] || previous != null && previous.get());
        try {
            waiter[0] = new Thread(() -> accepted[0] = listener.accept());
            waiter[0].start();
            Thread.sleep(50);
            assertTrue(waiter[0].isAlive(), "accept waits");
            stop[0] = true;
            waiter[0].join();
            assertTrue(accepted[0] == null, "accept ends with nothing");
        } finally {
            Tcp.setInterrupt(previous);
            listener.close();
        }
    }

    static void testOneListenerAPort() {
        Tcp.Listener listener = Tcp.listen(7004);
        assertTrue(listener != null, "first listener");
        assertTrue(Tcp.listen(7004) == null, "second listener on the same port");
        listener.close();
        Tcp.Listener again = Tcp.listen(7004);
        assertTrue(again != null, "listening again after closing");
        again.close();
    }

    // Each end sends size bytes and closes, while reading the other's until it closes too.
    private static void transfer(int address, int port, int size, int dropEvery) throws InterruptedException {
        Tcp.Listener listener = Tcp.listen(port);
        assertTrue(listener != null, "listen");
        Tcp.dropEvery(dropEvery);
        try {
            String[] server = new String[1];
            Thread serverThread = new Thread(() -> {
                Tcp.Connection accepted = listener.accept();
                server[0] = accepted == null ? "accept failed" : exchange(accepted, size, 1, 2);
            });
            serverThread.start();
            Tcp.Connection client = new Tcp.Connection();
            int connected = client.connect(address, port);
            assertEquals(0, connected, "connect: " + Tcp.describe(connected));
            assertEquals(port, client.remotePort(), "remote port");
            String result = exchange(client, size, 2, 1);
            serverThread.join();
            assertEquals("ok", result, "client end");
            assertEquals("ok", server[0], "server end");
        } finally {
            Tcp.dropEvery(0);
            listener.close();
        }
    }

    // Writes pattern `mine` from another thread while reading pattern `theirs` here; "ok" or what went wrong.
    private static String exchange(Tcp.Connection connection, int size, int mine, int theirs) {
        String[] written = new String[1];
        Thread writer = new Thread(() -> {
            byte[] chunk = new byte[4000];
            int sent = 0;
            while (sent < size) {
                int n = Math.min(chunk.length, size - sent);
                for (int i = 0; i < n; i++) {
                    chunk[i] = pattern(sent + i, mine);
                }
                int result = connection.write(chunk, 0, n);
                if (result != n) {
                    written[0] = "write at " + sent + " returned " + result;
                    break;
                }
                sent += n;
            }
            connection.close();
        });
        writer.start();
        connection.setReadTimeout(TIMEOUT_NANOS);
        byte[] buffer = new byte[3000];
        int received = 0;
        String problem = null;
        while (problem == null) {
            int n = connection.read(buffer, 0, buffer.length);
            if (n == 0) {
                break;
            }
            if (n < 0) {
                problem = "read after " + received + " bytes: " + Tcp.describe(n);
                break;
            }
            for (int i = 0; i < n; i++) {
                if (buffer[i] != pattern(received + i, theirs)) {
                    problem = "byte " + (received + i) + " is wrong";
                    break;
                }
            }
            received += n;
        }
        try {
            writer.join();
        } catch (InterruptedException e) {
            return "interrupted";
        }
        if (written[0] != null) {
            return written[0];
        }
        if (problem != null) {
            return problem;
        }
        return received == size ? "ok" : "received " + received + " of " + size + " bytes";
    }

    // Not periodic in 256, so a segment that's resent or skipped can't line up by accident.
    private static byte pattern(int i, int seed) {
        return (byte) (i + (i >>> 8) * 7 + (i >>> 16) * 13 + seed * 101);
    }

    private static void awaitAddress() throws InterruptedException {
        long deadline = HpetClock.nanos() + 10_000_000_000L;
        while (Network.address() == 0 && HpetClock.nanos() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(Network.address() != 0, "no address");
    }
}
