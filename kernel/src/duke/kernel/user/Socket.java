package duke.kernel.user;

import duke.kernel.net.Ipv4;
import duke.kernel.net.Network;
import duke.kernel.net.Tcp;
import duke.rt.Magic;
import duke.rt.Tib;

/**
 * An AF_INET stream socket: unconnected, then either listening or one end of a connection. The
 * port from bind is only taken at listen, since TCP has listeners and connections but nothing bound
 * in between, and a listener takes connections to any of our addresses whatever bind named.
 *
 * <p>Like a pipe, whoever makes one holds a reference until descriptors hold it instead, and the
 * last descriptor to go closes the connection or the listener.
 */
final class Socket extends OpenFile {

    static final int AF_INET = 2;
    static final int SOCK_STREAM = 1;
    static final int SOCKADDR_IN_BYTES = 16;
    static final int MODE_SOCKET = 0140777;
    // Linux's errno numbers, which Tcp's are too.
    static final int ENOTSOCK = 88;
    static final int EPROTONOSUPPORT = 93;
    static final int EOPNOTSUPP = 95;
    static final int EAFNOSUPPORT = 97;
    static final int EADDRINUSE = 98;
    static final int EADDRNOTAVAIL = 99;
    static final int ENOPROTOOPT = 92;
    static final int EISCONN = 106;
    static final int ENOTCONN = 107;
    // What a read or write moves at once, through a kernel buffer.
    private static final int CHUNK = 16384;

    static {
        Tcp.setInterrupt(Process::killedHere);
    }

    private int references = 1;
    private int boundAddress;
    private int boundPort;
    private Tcp.Listener listener;
    private Tcp.Connection connection;
    private boolean readShut;

    Socket() {
    }

    private Socket(Tcp.Connection connection) {
        this.connection = connection;
    }

    @Override
    long read(long buffer, long count) {
        Tcp.Connection c = connection;
        if (c == null) {
            return -ENOTCONN;
        }
        if (count == 0 || readShut) {
            return 0;
        }
        byte[] chunk = new byte[(int) Math.min(count, CHUNK)];
        int n = c.read(chunk, 0, chunk.length);
        if (n <= 0) {
            return n;
        }
        return SystemCalls.putBytes(buffer, chunk, 0, n) ? n : -SystemCalls.EFAULT;
    }

    @Override
    long write(long buffer, long count) {
        Tcp.Connection c = connection;
        if (c == null) {
            return -ENOTCONN;
        }
        byte[] chunk = new byte[(int) Math.min(count, CHUNK)];
        long done = 0;
        while (done < count) {
            int n = (int) Math.min(chunk.length, count - done);
            if (Magic.copyUserMemory(Magic.addressOf(chunk) + Tib.ARRAY_DATA, buffer + done, n) != 0) {
                return done > 0 ? done : -SystemCalls.EFAULT;
            }
            int written = c.write(chunk, 0, n);
            if (written < 0) {
                return done > 0 ? done : written;
            }
            done += written;
            if (written < n) {
                break;
            }
        }
        return done;
    }

    @Override
    int mode() {
        return MODE_SOCKET;
    }

    @Override
    synchronized void retain() {
        references++;
    }

    @Override
    void release() {
        synchronized (this) {
            if (--references > 0) {
                return;
            }
        }
        if (connection != null) {
            connection.close();
        }
        if (listener != null) {
            listener.close();
        }
    }

    synchronized long bind(int address, int port) {
        if (connection != null || listener != null || boundPort != 0) {
            return -SystemCalls.EINVAL;
        }
        if (address != 0 && !Ipv4.loopback(address) && address != Network.address()) {
            return -EADDRNOTAVAIL;
        }
        boundAddress = address;
        boundPort = port;
        return 0;
    }

    /** The backlog is TCP's own, whatever the caller asks for. */
    synchronized long listen() {
        if (connection != null) {
            return -SystemCalls.EINVAL;
        }
        if (listener == null) {
            listener = Tcp.listen(boundPort);
            if (listener == null) {
                return -EADDRINUSE;
            }
        }
        return 0;
    }

    /** The new connection's socket, or null with {@code error[0]} set. */
    Socket accept(int[] error) {
        Tcp.Listener l;
        synchronized (this) {
            l = listener;
        }
        if (l == null) {
            error[0] = -SystemCalls.EINVAL;
            return null;
        }
        Tcp.Connection accepted = l.accept();
        if (accepted == null) {
            error[0] = -SystemCalls.EINTR;
            return null;
        }
        return new Socket(accepted);
    }

    long connect(int address, int port) {
        Tcp.Connection c;
        synchronized (this) {
            if (connection != null) {
                return -EISCONN;
            }
            if (listener != null) {
                return -SystemCalls.EINVAL;
            }
            c = new Tcp.Connection();
            connection = c;
        }
        int result = c.connect(address, port);
        if (result < 0) {
            synchronized (this) {
                // A refused or timed out connection can be tried again, as on Linux.
                connection = null;
            }
        }
        return result;
    }

    long shutdown(int how) {
        Tcp.Connection c = connection;
        if (c == null) {
            return -ENOTCONN;
        }
        if (how < 0 || how > 2) {
            return -SystemCalls.EINVAL;
        }
        if (how != 1) {
            readShut = true;
        }
        if (how != 0) {
            c.close();
        }
        return 0;
    }

    /** {@code [address, port]} for getsockname. */
    synchronized int[] local() {
        if (connection != null) {
            return new int[] {connection.localAddress(), connection.localPort()};
        }
        return new int[] {boundAddress, listener != null ? listener.port() : boundPort};
    }

    /** {@code [address, port]} for getpeername, or null if not connected. */
    synchronized int[] peer() {
        if (connection == null) {
            return null;
        }
        return new int[] {connection.remoteAddress(), connection.remotePort()};
    }
}
