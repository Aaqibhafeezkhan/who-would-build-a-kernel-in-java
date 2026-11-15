package duke.kernel.net;

import duke.kernel.Console;
import duke.kernel.time.HpetClock;

/** ICMP echo: answers pings, and sends them for the shell's {@code ping}. */
public final class Icmp {

    private static final int HEADER = 8;
    private static final int ECHO_REPLY = 0;
    private static final int ECHO_REQUEST = 8;
    private static final int PAYLOAD = 56;
    private static final int IDENTIFIER = 0x4455;
    private static final long TIMEOUT_NANOS = 1_000_000_000L;

    private static final Object LOCK = new Object();
    // The echo ping is waiting for, and when its reply came in; 0 until then.
    private static int awaitedSource;
    private static int awaitedSequence = -1;
    private static long answeredAt;

    private Icmp() {
    }

    static void input(long sourceMac, int source, byte[] frame, int at, int length) {
        if (length < HEADER || Wire.checksum(frame, at, length) != 0) {
            return;
        }
        int type = frame[at] & 0xFF;
        if (type == ECHO_REQUEST && frame[at + 1] == 0) {
            // Back to the MAC it came from, which needs no ARP while we're the one reading frames.
            byte[] reply = Ipv4.packet(sourceMac, source, Ipv4.PROTOCOL_ICMP, length);
            int out = Network.ETHERNET_HEADER + Ipv4.HEADER;
            System.arraycopy(frame, at, reply, out, length);
            reply[out] = ECHO_REPLY;
            Wire.put16(reply, out + 2, 0);
            Wire.put16(reply, out + 2, Wire.checksum(reply, out, length));
            Network.send(reply);
        } else if (type == ECHO_REPLY && Wire.get16(frame, at + 4) == IDENTIFIER) {
            int sequence = Wire.get16(frame, at + 6);
            synchronized (LOCK) {
                if (source == awaitedSource && sequence == awaitedSequence && answeredAt == 0) {
                    answeredAt = HpetClock.nanos();
                }
            }
        }
    }

    /** Sends {@code count} echo requests to {@code destination} a second apart, printing each reply. */
    public static void ping(int destination, int count) {
        String name = Ipv4.format(destination);
        long nextHop = Ipv4.nextHop(destination);
        if (nextHop < 0) {
            Console.println("ping: " + name + ": no ARP reply from "
                    + Ipv4.format(Network.onLink(destination) ? destination : Network.GATEWAY));
            return;
        }
        int received = 0;
        for (int sequence = 1; sequence <= count; sequence++) {
            long sentAt = HpetClock.nanos();
            synchronized (LOCK) {
                awaitedSource = destination;
                awaitedSequence = sequence;
                answeredAt = 0;
            }
            Network.send(echo(nextHop, destination, sequence));
            long rtt = await(sentAt);
            if (rtt >= 0) {
                received++;
                String micros = Long.toString(1000 + rtt / 1000 % 1000).substring(1);
                Console.println("reply from " + name + ": seq=" + sequence + " time=" + rtt / 1_000_000 + "." + micros + " ms");
            } else {
                Console.println("no reply from " + name + ": seq=" + sequence);
            }
            long wait = sentAt + 1_000_000_000L - HpetClock.nanos();
            if (sequence < count && wait > 0) {
                try {
                    Thread.sleep(wait / 1_000_000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        Console.println(count + " sent, " + received + " received");
    }

    // The round trip in nanoseconds, or -1 after a second.
    private static long await(long sentAt) {
        while (HpetClock.nanos() < sentAt + TIMEOUT_NANOS) {
            if (!Network.poll()) {
                Thread.yield();
            }
            synchronized (LOCK) {
                if (answeredAt != 0) {
                    return answeredAt - sentAt;
                }
            }
        }
        return -1;
    }

    private static byte[] echo(long nextHop, int destination, int sequence) {
        byte[] frame = Ipv4.packet(nextHop, destination, Ipv4.PROTOCOL_ICMP, HEADER + PAYLOAD);
        int at = Network.ETHERNET_HEADER + Ipv4.HEADER;
        frame[at] = ECHO_REQUEST;
        Wire.put16(frame, at + 4, IDENTIFIER);
        Wire.put16(frame, at + 6, sequence);
        for (int i = 0; i < PAYLOAD; i++) {
            frame[at + HEADER + i] = (byte) i;
        }
        Wire.put16(frame, at + 2, Wire.checksum(frame, at, HEADER + PAYLOAD));
        return frame;
    }
}
