package duke.kernel.user;

/** The socket system calls, for AF_INET stream sockets. */
final class SocketCalls {

    static final int SOCKET = 41;
    static final int CONNECT = 42;
    static final int ACCEPT = 43;
    static final int SENDTO = 44;
    static final int RECVFROM = 45;
    static final int SHUTDOWN = 48;
    static final int BIND = 49;
    static final int LISTEN = 50;
    static final int GETSOCKNAME = 51;
    static final int GETPEERNAME = 52;
    static final int SETSOCKOPT = 54;
    static final int ACCEPT4 = 288;

    private static final int SOCK_TYPE_MASK = 0xF;
    private static final int SOCK_CLOEXEC = SystemCalls.O_CLOEXEC;
    private static final int IPPROTO_TCP = 6;
    private static final int SOL_SOCKET = 1;
    private static final int SO_REUSEADDR = 2;
    private static final int SO_KEEPALIVE = 9;
    private static final int SO_REUSEPORT = 15;
    private static final int TCP_NODELAY = 1;
    // There are no signals to suppress.
    private static final int MSG_NOSIGNAL = 0x4000;

    private SocketCalls() {
    }

    /** SOCK_CLOEXEC, but not SOCK_NONBLOCK: nothing here can return EAGAIN yet. */
    static long socket(long domain, long type, long protocol) {
        if (domain != Socket.AF_INET) {
            return -Socket.EAFNOSUPPORT;
        }
        if ((type & ~(long) (SOCK_TYPE_MASK | SOCK_CLOEXEC)) != 0) {
            return -SystemCalls.EINVAL;
        }
        if ((type & SOCK_TYPE_MASK) != Socket.SOCK_STREAM || protocol != 0 && protocol != IPPROTO_TCP) {
            return -Socket.EPROTONOSUPPORT;
        }
        Socket socket = new Socket();
        int fd = SystemCalls.files().add(socket, (type & SOCK_CLOEXEC) != 0);
        // The descriptor holds it now, or nothing does.
        socket.release();
        return fd;
    }

    /** connect or bind: both take a sockaddr_in. */
    static long connectOrBind(long fd, long address, long length, boolean connect) {
        Socket socket = socket(fd);
        if (socket == null) {
            return notSocket(fd);
        }
        if (length < Socket.SOCKADDR_IN_BYTES) {
            return -SystemCalls.EINVAL;
        }
        if (!SystemCalls.userReadable(address, Socket.SOCKADDR_IN_BYTES)) {
            return -SystemCalls.EFAULT;
        }
        byte[] in = SystemCalls.getBytes(address, Socket.SOCKADDR_IN_BYTES);
        if (in == null) {
            return -SystemCalls.EFAULT;
        }
        if ((in[0] & 0xFF | (in[1] & 0xFF) << 8) != Socket.AF_INET) {
            return -Socket.EAFNOSUPPORT;
        }
        int port = (in[2] & 0xFF) << 8 | in[3] & 0xFF;
        int ip = (in[4] & 0xFF) << 24 | (in[5] & 0xFF) << 16 | (in[6] & 0xFF) << 8 | in[7] & 0xFF;
        return connect ? socket.connect(ip, port) : socket.bind(ip, port);
    }

    static long listen(long fd) {
        Socket socket = socket(fd);
        return socket == null ? notSocket(fd) : socket.listen();
    }

    /** accept and accept4, which takes SOCK_CLOEXEC. */
    static long accept(long fd, long address, long length, long flags) {
        Socket socket = socket(fd);
        if (socket == null) {
            return notSocket(fd);
        }
        if ((flags & ~(long) SOCK_CLOEXEC) != 0) {
            return -SystemCalls.EINVAL;
        }
        if (address != 0 && !SystemCalls.userWritable(length, 4)) {
            return -SystemCalls.EFAULT;
        }
        int[] error = new int[1];
        Socket accepted = socket.accept(error);
        if (accepted == null) {
            return error[0];
        }
        int newFd = SystemCalls.files().add(accepted, (flags & SOCK_CLOEXEC) != 0);
        accepted.release();
        if (newFd < 0 || address == 0) {
            return newFd;
        }
        // As on Linux, the connection is accepted whatever happens to the address.
        long written = putAddress(address, length, accepted.peer());
        return written < 0 ? written : newFd;
    }

    /** sendto without an address, which a connected stream socket ignores anyway. */
    static long sendto(long fd, long buffer, long count, long flags) {
        Socket socket = socket(fd);
        if (socket == null) {
            return notSocket(fd);
        }
        if ((flags & ~(long) MSG_NOSIGNAL) != 0) {
            return -Socket.EOPNOTSUPP;
        }
        if (!SystemCalls.userReadable(buffer, count)) {
            return -SystemCalls.EFAULT;
        }
        return socket.write(buffer, count);
    }

    /** recvfrom with no flags. A stream socket leaves the source address alone, as Linux's does. */
    static long recvfrom(long fd, long buffer, long count, long flags) {
        Socket socket = socket(fd);
        if (socket == null) {
            return notSocket(fd);
        }
        if (flags != 0) {
            return -Socket.EOPNOTSUPP;
        }
        if (!SystemCalls.userWritable(buffer, count)) {
            return -SystemCalls.EFAULT;
        }
        return socket.read(buffer, count);
    }

    static long shutdown(long fd, long how) {
        Socket socket = socket(fd);
        return socket == null ? notSocket(fd) : socket.shutdown((int) how);
    }

    static long name(long fd, long address, long length, boolean peer) {
        Socket socket = socket(fd);
        if (socket == null) {
            return notSocket(fd);
        }
        int[] name = peer ? socket.peer() : socket.local();
        if (name == null) {
            return -Socket.ENOTCONN;
        }
        if (!SystemCalls.userWritable(length, 4)) {
            return -SystemCalls.EFAULT;
        }
        return putAddress(address, length, name);
    }

    /** Options that change nothing here: Nagle is never on, and nothing waits on a port to be reused. */
    static long setsockopt(long fd, long level, long option, long value, long length) {
        Socket socket = socket(fd);
        if (socket == null) {
            return notSocket(fd);
        }
        boolean known = level == SOL_SOCKET && (option == SO_REUSEADDR || option == SO_KEEPALIVE || option == SO_REUSEPORT)
                || level == IPPROTO_TCP && option == TCP_NODELAY;
        if (!known) {
            return -Socket.ENOPROTOOPT;
        }
        if (length < 4) {
            return -SystemCalls.EINVAL;
        }
        return SystemCalls.userReadable(value, 4) ? 0 : -SystemCalls.EFAULT;
    }

    private static Socket socket(long fd) {
        return SystemCalls.files().get(fd) instanceof Socket s ? s : null;
    }

    private static long notSocket(long fd) {
        return SystemCalls.files().get(fd) == null ? -SystemCalls.EBADF : -Socket.ENOTSOCK;
    }

    /**
     * Writes {@code name} as a sockaddr_in, cut to the length at {@code length} (already checked
     * writable), and sets that to the full length, as Linux does.
     */
    private static long putAddress(long address, long length, int[] name) {
        byte[] lengthBytes = SystemCalls.getBytes(length, 4);
        if (lengthBytes == null) {
            return -SystemCalls.EFAULT;
        }
        int room = lengthBytes[0] & 0xFF | (lengthBytes[1] & 0xFF) << 8 | (lengthBytes[2] & 0xFF) << 16 | lengthBytes[3] << 24;
        if (room < 0) {
            return -SystemCalls.EINVAL;
        }
        byte[] out = new byte[Socket.SOCKADDR_IN_BYTES];
        out[0] = (byte) Socket.AF_INET;
        out[2] = (byte) (name[1] >>> 8);
        out[3] = (byte) name[1];
        out[4] = (byte) (name[0] >>> 24);
        out[5] = (byte) (name[0] >>> 16);
        out[6] = (byte) (name[0] >>> 8);
        out[7] = (byte) name[0];
        int n = Math.min(room, out.length);
        if (n > 0 && !SystemCalls.userWritable(address, n)) {
            return -SystemCalls.EFAULT;
        }
        if (n > 0 && !SystemCalls.putBytes(address, out, 0, n) || !SystemCalls.putInt(length, out.length)) {
            return -SystemCalls.EFAULT;
        }
        return 0;
    }
}
