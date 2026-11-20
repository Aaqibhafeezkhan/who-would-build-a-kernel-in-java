package duke.kernel.net;

import duke.kernel.time.HpetClock;
import java.util.ArrayList;

/**
 * TCP (RFC 9293): connect, listen and accept, a window each way and retransmission. Segments that
 * arrive out of order are dropped and the sender resends them, from the first byte not
 * acknowledged, after a timeout or three duplicate acknowledgments. No congestion control, window
 * scaling or selective acknowledgments.
 *
 * <p>One lock guards every connection. Frames arrive under the network's lock and segments go out
 * under this one, so nothing here may wait for the network while holding it: replies go to the MAC
 * a connection's segments come from, and only {@link Connection#connect} asks ARP, before locking.
 */
public final class Tcp {

    public static final int EINTR = 4;
    public static final int EPIPE = 32;
    public static final int EADDRNOTAVAIL = 99;
    public static final int ENETUNREACH = 101;
    public static final int ECONNRESET = 104;
    public static final int EISCONN = 106;
    public static final int ENOTCONN = 107;
    public static final int ETIMEDOUT = 110;
    public static final int ECONNREFUSED = 111;
    public static final int EHOSTUNREACH = 113;

    static final int HEADER = 20;

    private static final int FIN = 0x01;
    private static final int SYN = 0x02;
    private static final int RST = 0x04;
    private static final int PSH = 0x08;
    private static final int ACK = 0x10;

    private static final int CLOSED = 0;
    private static final int SYN_SENT = 1;
    private static final int SYN_RECEIVED = 2;
    private static final int ESTABLISHED = 3;
    private static final int FIN_WAIT_1 = 4;
    private static final int FIN_WAIT_2 = 5;
    private static final int CLOSE_WAIT = 6;
    private static final int CLOSING = 7;
    private static final int LAST_ACK = 8;
    private static final int TIME_WAIT = 9;

    private static final int MSS = 1500 - Ipv4.HEADER - HEADER;
    // What to assume when a SYN doesn't say (RFC 9293 3.7.1).
    private static final int DEFAULT_MSS = 536;
    // Each way: the most a window can offer without scaling.
    private static final int BUFFER = 65535;
    private static final int BACKLOG = 16;
    private static final long INITIAL_RTO = 1_000_000_000L;
    // RFC 6298 says a second; 200 ms is what Linux uses, and what keeps a lost segment cheap.
    private static final long MIN_RTO = 200_000_000L;
    private static final long MAX_RTO = 60_000_000_000L;
    // tick() runs on the net thread's 10 ms sleep.
    private static final long GRANULARITY = 10_000_000L;
    private static final int SYN_RETRIES = 5;
    private static final int RETRIES = 8;
    private static final long TIME_WAIT_NANOS = 60_000_000_000L;
    private static final int FIRST_EPHEMERAL = 49152;

    private static final Object LOCK = new Object();
    private static final ArrayList<Connection> connections = new ArrayList<>();
    private static final ArrayList<Listener> listeners = new ArrayList<>();
    private static int nextPort;
    private static int dropEvery;
    private static int segmentsSent;

    private Tcp() {
    }

    /** One end of a connection: open it with {@link #connect}, or get one from {@link Listener#accept}. */
    public static final class Connection {
        int state = CLOSED;
        int localPort;
        int remoteAddress;
        int remotePort;
        long peerMac;
        // Negative, once a reset or a timeout has ended the connection.
        int error;
        long readTimeoutNanos;
        // The listener a connection from accept is queued on once its handshake completes.
        Listener listener;

        // Sending: the oldest sequence number not acknowledged, the next to send, the highest sent.
        int iss;
        int sndUna;
        int sndNxt;
        int sndMax;
        int sndWnd;
        int mss = DEFAULT_MSS;
        // The data from sendBase on: sent and not yet acknowledged, then not sent yet.
        Ring out = new Ring();
        int sendBase;
        // close() was called: a FIN follows the data.
        boolean finQueued;

        // Receiving: the next sequence number expected, and the window's right edge we last offered.
        int rcvNxt;
        Ring in = new Ring();
        boolean finReceived;
        int offeredEdge;
        boolean ackPending;

        long retransmitAt;
        int retries;
        // The timeout from the round trip estimate, and the one in use, doubled after each timeout.
        long rtoEstimate = INITIAL_RTO;
        long rto = INITIAL_RTO;
        long srtt;
        long rttvar;
        // One segment at a time is timed for the round trip, never a resent one (Karn).
        boolean timing;
        int timedSeq;
        long timedAt;
        int duplicateAcks;
        // Resending after duplicate acknowledgments, which the rest of that window's keep arriving.
        boolean recovering;
        long timeWaitUntil;

        public Connection() {
        }

        /** Opens a connection to {@code address} and {@code port}: 0 once it's established, or a negative errno. */
        public int connect(int address, int port) {
            if (Network.address() == 0) {
                return -ENETUNREACH;
            }
            long nextHop = Ipv4.nextHop(address);
            if (nextHop < 0) {
                return -EHOSTUNREACH;
            }
            synchronized (LOCK) {
                if (state != CLOSED || error != 0) {
                    return -EISCONN;
                }
                localPort = ephemeralPort();
                if (localPort == 0) {
                    return -EADDRNOTAVAIL;
                }
                peerMac = nextHop;
                remoteAddress = address;
                remotePort = port;
                start(this);
                state = SYN_SENT;
                connections.add(this);
                segment(this, iss, SYN, 0, 0);
                sent(this, iss + 1, HpetClock.nanos());
            }
            int waited = await(() -> state != SYN_SENT, 0);
            synchronized (LOCK) {
                if (error != 0) {
                    return error;
                }
                if (state == SYN_SENT) {
                    // Interrupted: give up on it.
                    remove(this);
                    state = CLOSED;
                    return waited;
                }
                return 0;
            }
        }

        /** Queues all of {@code length} bytes to send, waiting for room: how many were queued, or a negative errno if none. */
        public int write(byte[] b, int at, int length) {
            int written = 0;
            while (written < length) {
                int waited = await(() -> error != 0 || finQueued || !sending(state) || out.free() > 0, 0);
                synchronized (LOCK) {
                    if (error != 0 || finQueued || !sending(state)) {
                        if (written > 0) {
                            return written;
                        }
                        return error != 0 ? error : finQueued ? -EPIPE : -ENOTCONN;
                    }
                    if (out.free() == 0) {
                        return written > 0 ? written : waited;
                    }
                    written += out.put(b, at + written, length - written);
                    output(this);
                }
            }
            return written;
        }

        /**
         * Waits for data: how many bytes were read, 0 once the peer has closed and everything it
         * sent has been read, or a negative errno.
         */
        public int read(byte[] b, int at, int length) {
            long deadline = readTimeoutNanos == 0 ? 0 : HpetClock.nanos() + readTimeoutNanos;
            int waited = await(() -> in.count > 0 || finReceived || error != 0 || state == CLOSED, deadline);
            synchronized (LOCK) {
                if (in.count > 0) {
                    int n = in.take(b, at, length);
                    offerWindow(this);
                    return n;
                }
                if (error != 0) {
                    return error;
                }
                if (finReceived) {
                    return 0;
                }
                return state == CLOSED ? -ENOTCONN : waited;
            }
        }

        /** How long {@link #read} waits for data before it gives up with -ETIMEDOUT, or 0 to wait for good. */
        public void setReadTimeout(long nanos) {
            readTimeoutNanos = nanos;
        }

        /** Sends a FIN once everything queued has gone, without waiting for it. Reading still works until the peer closes. */
        public void close() {
            synchronized (LOCK) {
                switch (state) {
                    case SYN_SENT -> {
                        remove(this);
                        state = CLOSED;
                        LOCK.notifyAll();
                    }
                    case SYN_RECEIVED -> abort(this);
                    case ESTABLISHED, CLOSE_WAIT -> {
                        state = state == ESTABLISHED ? FIN_WAIT_1 : LAST_ACK;
                        finQueued = true;
                        output(this);
                    }
                    default -> {
                    }
                }
            }
        }

        public int localPort() {
            return localPort;
        }

        public int remoteAddress() {
            return remoteAddress;
        }

        public int remotePort() {
            return remotePort;
        }
    }

    /** Connections coming in to one port, from {@link #listen}. */
    public static final class Listener {
        final int port;
        // Established, waiting for accept.
        final ArrayList<Connection> ready = new ArrayList<>();
        boolean closed;

        Listener(int port) {
            this.port = port;
        }

        /** Waits for the next connection; null once the listener is closed, or if interrupted. */
        public Connection accept() {
            await(() -> !ready.isEmpty() || closed, 0);
            synchronized (LOCK) {
                return closed || ready.isEmpty() ? null : ready.remove(0);
            }
        }

        /** Stops listening, resetting connections not accepted yet. */
        public void close() {
            synchronized (LOCK) {
                closed = true;
                listeners.remove(this);
                for (int i = connections.size() - 1; i >= 0; i--) {
                    Connection c = connections.get(i);
                    if (c.listener == this) {
                        abort(c);
                    }
                }
                for (Connection c : ready) {
                    abort(c);
                }
                ready.clear();
                LOCK.notifyAll();
            }
        }

        public int port() {
            return port;
        }
    }

    /** Listens on {@code port}; null if something listens there already. */
    public static Listener listen(int port) {
        synchronized (LOCK) {
            for (Listener l : listeners) {
                if (l.port == port) {
                    return null;
                }
            }
            Listener listener = new Listener(port);
            listeners.add(listener);
            return listener;
        }
    }

    /** What a negative errno from a connection means, for messages. */
    public static String describe(int error) {
        return switch (-error) {
            case ECONNREFUSED -> "connection refused";
            case ECONNRESET -> "connection reset";
            case ETIMEDOUT -> "timed out";
            case EHOSTUNREACH -> "no ARP reply";
            case ENETUNREACH -> "no address yet";
            case EPIPE -> "closed for writing";
            case ENOTCONN -> "not connected";
            case EINTR -> "interrupted";
            case EADDRNOTAVAIL -> "no free port";
            default -> "error " + -error;
        };
    }

    /** For tests: throws away every {@code n}th segment instead of sending it, or none for 0. */
    public static void dropEvery(int n) {
        synchronized (LOCK) {
            dropEvery = n;
            segmentsSent = 0;
        }
    }

    // Called holding LOCK.
    private interface Condition {
        boolean met();
    }

    /**
     * Reads frames until {@code done}, or until {@code deadline} (0 for none) passes: 0, -ETIMEDOUT
     * or -EINTR. Reading them here rather than waiting for the net thread saves up to a tick a round trip.
     */
    private static int await(Condition done, long deadline) {
        while (true) {
            synchronized (LOCK) {
                if (done.met()) {
                    return 0;
                }
                if (deadline != 0 && HpetClock.nanos() >= deadline) {
                    return -ETIMEDOUT;
                }
            }
            if (!Network.up() || !Network.poll()) {
                synchronized (LOCK) {
                    if (done.met()) {
                        return 0;
                    }
                    try {
                        LOCK.wait(1);
                    } catch (InterruptedException e) {
                        return -EINTR;
                    }
                }
            }
        }
    }

    /** From the net thread every tick: retransmissions, and the end of TIME_WAIT. */
    static void tick() {
        synchronized (LOCK) {
            if (connections.isEmpty()) {
                return;
            }
            long now = HpetClock.nanos();
            for (int i = connections.size() - 1; i >= 0; i--) {
                Connection c = connections.get(i);
                if (c.state == TIME_WAIT) {
                    if (now >= c.timeWaitUntil) {
                        connections.remove(i);
                        c.state = CLOSED;
                    }
                } else if (c.retransmitAt != 0 && now >= c.retransmitAt) {
                    timeout(c, now);
                }
            }
        }
    }

    // What arrived, for the code that takes it apart.
    private static final class Segment {
        int seq;
        int ack;
        int flags;
        int window;
        int mss;
        byte[] frame;
        int dataAt;
        int dataLength;
    }

    static void input(long sourceMac, int source, int destination, byte[] frame, int at, int length) {
        if (length < HEADER) {
            return;
        }
        int headerLength = (frame[at + 12] & 0xFF) >>> 4 << 2;
        if (headerLength < HEADER || headerLength > length
                || Wire.checksum(frame, at, length, pseudoHeader(source, destination, length)) != 0) {
            return;
        }
        int sourcePort = Wire.get16(frame, at);
        int destinationPort = Wire.get16(frame, at + 2);
        Segment s = new Segment();
        s.seq = Wire.get32(frame, at + 4);
        s.ack = Wire.get32(frame, at + 8);
        s.flags = frame[at + 13] & 0x3F;
        s.window = Wire.get16(frame, at + 14);
        s.mss = mssOption(frame, at + HEADER, headerLength - HEADER);
        s.frame = frame;
        s.dataAt = at + headerLength;
        s.dataLength = length - headerLength;
        synchronized (LOCK) {
            Connection c = find(destinationPort, source, sourcePort);
            if (c != null) {
                arrive(c, s);
                return;
            }
            Listener listener = null;
            for (Listener l : listeners) {
                if (l.port == destinationPort) {
                    listener = l;
                }
            }
            if (listener != null && (s.flags & (SYN | ACK | RST)) == SYN) {
                incoming(listener, sourceMac, source, sourcePort, s);
            } else if ((s.flags & RST) == 0) {
                refuse(sourceMac, source, destinationPort, sourcePort, s);
            }
        }
    }

    // A SYN for a listener: answer it, and queue the connection for accept once the handshake's done.
    private static void incoming(Listener listener, long sourceMac, int source, int sourcePort, Segment s) {
        int halfOpen = 0;
        for (Connection c : connections) {
            if (c.listener == listener) {
                halfOpen++;
            }
        }
        if (halfOpen + listener.ready.size() >= BACKLOG) {
            // Dropped: the peer will send its SYN again.
            return;
        }
        Connection c = new Connection();
        c.listener = listener;
        c.localPort = listener.port;
        c.remoteAddress = source;
        c.remotePort = sourcePort;
        c.peerMac = sourceMac;
        c.rcvNxt = s.seq + 1;
        c.sndWnd = s.window;
        c.mss = s.mss != 0 ? Math.min(s.mss, MSS) : DEFAULT_MSS;
        start(c);
        c.state = SYN_RECEIVED;
        connections.add(c);
        segment(c, c.iss, SYN | ACK, 0, 0);
        sent(c, c.iss + 1, HpetClock.nanos());
    }

    private static void arrive(Connection c, Segment s) {
        if (c.state == SYN_SENT) {
            synSent(c, s);
            return;
        }
        long now = HpetClock.nanos();
        boolean syn = (s.flags & SYN) != 0;
        boolean fin = (s.flags & FIN) != 0;
        int length = s.dataLength + (syn ? 1 : 0) + (fin ? 1 : 0);
        int ahead = s.seq - c.rcvNxt;
        if ((s.flags & RST) != 0) {
            // Only one starting exactly where we expect, so a blind guess at the window can't end us (RFC 5961).
            if (ahead == 0) {
                if (c.listener != null || c.state == TIME_WAIT) {
                    remove(c);
                    c.state = CLOSED;
                } else {
                    fail(c, -ECONNRESET);
                }
            }
            return;
        }
        boolean old = length > 0 ? ahead + length <= 0 : ahead < 0;
        if (old || ahead >= Math.max(c.in.free(), 1) || syn && ahead == 0) {
            // All old, past the window, or a SYN where data should be: say what we expect instead.
            acknowledge(c);
            return;
        }
        int dataAt = s.dataAt;
        int dataLength = s.dataLength;
        if (ahead > 0) {
            // Out of order: the data waits for the sender to send it again, but what it acknowledges counts.
            if (length > 0) {
                c.ackPending = true;
            }
            dataLength = 0;
            fin = false;
        } else {
            int trim = -ahead;
            if (syn && trim > 0) {
                trim--;
            }
            int skip = Math.min(trim, dataLength);
            dataAt += skip;
            dataLength -= skip;
        }
        if ((s.flags & ACK) == 0) {
            return;
        }
        if (c.state == SYN_RECEIVED) {
            if (s.ack - c.sndUna <= 0 || s.ack - c.sndMax > 0) {
                reply(c.peerMac, c.remoteAddress, c.localPort, c.remotePort, s.ack, 0, RST);
                return;
            }
            c.state = ESTABLISHED;
            if (c.listener != null) {
                c.listener.ready.add(c);
                c.listener = null;
            }
            LOCK.notifyAll();
        }
        if (s.ack - c.sndMax > 0) {
            // Acknowledges something never sent.
            acknowledge(c);
            return;
        }
        int acked = s.ack - c.sndUna;
        if (acked > 0) {
            c.sndUna = s.ack;
            if (c.sndNxt - s.ack < 0) {
                // Got there before we resent it.
                c.sndNxt = s.ack;
            }
            int data = Math.min(s.ack - c.sendBase, c.out.count);
            if (data > 0) {
                c.out.drop(data);
                c.sendBase += data;
            }
            if (c.timing && s.ack - c.timedSeq >= 0) {
                sample(c, now - c.timedAt);
                c.timing = false;
            }
            // Back from any backoff: resending goes on long enough that waiting for a round trip
            // measurement (never of a resent segment) to undo it lets the timeout grow without end.
            c.rto = c.rtoEstimate;
            c.retries = 0;
            c.duplicateAcks = 0;
            c.recovering = false;
            c.retransmitAt = c.sndUna == c.sndMax ? 0 : now + c.rto;
            LOCK.notifyAll();
        } else if (acked == 0 && length == 0 && s.window == c.sndWnd && c.sndUna != c.sndMax) {
            if (++c.duplicateAcks >= 3 && !c.recovering) {
                c.recovering = true;
                c.timing = false;
                c.sndNxt = c.sndUna;
            }
        }
        if (acked >= 0) {
            c.sndWnd = s.window;
            if (s.window == 0) {
                // It's there, its window's just shut: keep probing it, for as long as that takes.
                c.retries = 0;
            }
        }
        if (c.finQueued && c.sndUna == finSeq(c) + 1) {
            if (c.state == FIN_WAIT_1) {
                c.state = FIN_WAIT_2;
            } else if (c.state == CLOSING) {
                timeWait(c, now);
            } else if (c.state == LAST_ACK) {
                remove(c);
                c.state = CLOSED;
                LOCK.notifyAll();
                return;
            }
        }
        boolean taken = true;
        if (dataLength > 0 && receiving(c.state)) {
            int n = c.in.put(s.frame, dataAt, dataLength);
            c.rcvNxt += n;
            taken = n == dataLength;
            c.ackPending = true;
            LOCK.notifyAll();
        }
        if (fin && taken && receiving(c.state)) {
            c.rcvNxt++;
            c.finReceived = true;
            c.ackPending = true;
            if (c.state == ESTABLISHED) {
                c.state = CLOSE_WAIT;
            } else if (c.state == FIN_WAIT_1) {
                c.state = CLOSING;
            } else {
                timeWait(c, now);
            }
            LOCK.notifyAll();
        }
        output(c);
        if (c.ackPending) {
            acknowledge(c);
        }
    }

    private static void synSent(Connection c, Segment s) {
        boolean acceptable = (s.flags & ACK) != 0 && s.ack == c.iss + 1;
        if ((s.flags & ACK) != 0 && !acceptable) {
            if ((s.flags & RST) == 0) {
                reply(c.peerMac, c.remoteAddress, c.localPort, c.remotePort, s.ack, 0, RST);
            }
            return;
        }
        if ((s.flags & RST) != 0) {
            if (acceptable) {
                fail(c, -ECONNREFUSED);
            }
            return;
        }
        if ((s.flags & SYN) == 0) {
            return;
        }
        c.rcvNxt = s.seq + 1;
        c.sndWnd = s.window;
        c.mss = s.mss != 0 ? Math.min(s.mss, MSS) : DEFAULT_MSS;
        if (acceptable) {
            c.sndUna = s.ack;
            c.state = ESTABLISHED;
            c.retransmitAt = 0;
            c.retries = 0;
            if (c.timing) {
                sample(c, HpetClock.nanos() - c.timedAt);
                c.timing = false;
            }
            acknowledge(c);
        } else {
            // Both ends sent a SYN at once.
            c.state = SYN_RECEIVED;
            segment(c, c.iss, SYN | ACK, 0, 0);
        }
        LOCK.notifyAll();
    }

    /** Sends what the peer's window has room for, then the FIN once all the data's out. */
    private static void output(Connection c) {
        if (!sending(c.state) && c.state != FIN_WAIT_1 && c.state != CLOSING && c.state != LAST_ACK) {
            return;
        }
        long now = HpetClock.nanos();
        while (true) {
            int offset = c.sndNxt - c.sendBase;
            int unsent = c.out.count - offset;
            int room = c.sndWnd - (c.sndNxt - c.sndUna);
            if (unsent > 0 && room > 0) {
                int n = Math.min(Math.min(unsent, room), c.mss);
                segment(c, c.sndNxt, ACK | (n == unsent ? PSH : 0), offset, n);
                sent(c, c.sndNxt + n, now);
            } else if (unsent == 0 && c.finQueued && c.sndNxt == finSeq(c)) {
                segment(c, c.sndNxt, FIN | ACK, 0, 0);
                sent(c, c.sndNxt + 1, now);
            } else {
                if (unsent > 0 && c.retransmitAt == 0) {
                    // The window's shut with nothing out: the timer sends a byte anyway to see if it's open.
                    c.retransmitAt = now + c.rto;
                }
                return;
            }
        }
    }

    // sndNxt moves up to next: times the segment if nothing else is being timed, and starts the timer.
    private static void sent(Connection c, int next, long now) {
        if (!c.timing && next - c.sndMax > 0) {
            c.timing = true;
            c.timedSeq = next;
            c.timedAt = now;
        }
        c.sndNxt = next;
        if (next - c.sndMax > 0) {
            c.sndMax = next;
        }
        if (c.retransmitAt == 0) {
            c.retransmitAt = now + c.rto;
        }
    }

    private static void timeout(Connection c, long now) {
        boolean handshake = c.state == SYN_SENT || c.state == SYN_RECEIVED;
        if (++c.retries > (handshake ? SYN_RETRIES : RETRIES)) {
            if (c.listener != null) {
                remove(c);
                c.state = CLOSED;
            } else {
                fail(c, -ETIMEDOUT);
            }
            return;
        }
        c.rto = Math.min(c.rto * 2, MAX_RTO);
        c.timing = false;
        c.recovering = false;
        c.duplicateAcks = 0;
        c.retransmitAt = now + c.rto;
        if (handshake) {
            segment(c, c.iss, c.state == SYN_SENT ? SYN : SYN | ACK, 0, 0);
            return;
        }
        // Everything after the first unacknowledged byte goes again, starting with one segment.
        c.sndNxt = c.sndUna;
        int offset = c.sndNxt - c.sendBase;
        int unsent = c.out.count - offset;
        if (unsent > 0) {
            // Even into a shut window: that's the probe that finds out it has opened.
            int n = Math.min(Math.min(unsent, c.mss), Math.max(c.sndWnd, 1));
            segment(c, c.sndNxt, ACK, offset, n);
            sent(c, c.sndNxt + n, now);
        } else if (c.finQueued && c.sndNxt == finSeq(c)) {
            segment(c, c.sndNxt, FIN | ACK, 0, 0);
            sent(c, c.sndNxt + 1, now);
        }
    }

    // RFC 6298's estimate of the round trip and the timeout from it.
    private static void sample(Connection c, long rtt) {
        rtt = Math.max(rtt, 1);
        if (c.srtt == 0) {
            c.srtt = rtt;
            c.rttvar = rtt / 2;
        } else {
            c.rttvar = (3 * c.rttvar + Math.abs(c.srtt - rtt)) / 4;
            c.srtt = (7 * c.srtt + rtt) / 8;
        }
        c.rtoEstimate = Math.max(MIN_RTO, Math.min(MAX_RTO, c.srtt + Math.max(GRANULARITY, 4 * c.rttvar)));
        c.rto = c.rtoEstimate;
    }

    // After reading: tells the peer about the room that made, once there's enough to be worth a segment.
    private static void offerWindow(Connection c) {
        if (receiving(c.state) && c.rcvNxt + c.in.free() - c.offeredEdge >= Math.min(BUFFER / 2, c.mss)) {
            acknowledge(c);
        }
    }

    private static void acknowledge(Connection c) {
        if (c.state == SYN_RECEIVED) {
            segment(c, c.iss, SYN | ACK, 0, 0);
        } else {
            // From the highest sent, not sndNxt: while resending, sndNxt is behind what the peer has seen.
            segment(c, c.sndMax, ACK, 0, 0);
        }
    }

    private static void segment(Connection c, int seq, int flags, int offset, int length) {
        boolean ack = (flags & ACK) != 0;
        int window = c.in.free();
        byte[] frame = frame(c.peerMac, c.remoteAddress, c.localPort, c.remotePort, seq, ack ? c.rcvNxt : 0, flags,
                window, length);
        c.out.copy(offset, frame, Network.ETHERNET_HEADER + Ipv4.HEADER + headerLength(flags), length);
        if (ack) {
            c.ackPending = false;
            c.offeredEdge = c.rcvNxt + window;
        }
        transmit(frame, c.remoteAddress);
    }

    // An answer that belongs to no connection, like a reset.
    private static void reply(long mac, int destination, int sourcePort, int destinationPort, int seq, int ack, int flags) {
        transmit(frame(mac, destination, sourcePort, destinationPort, seq, ack, flags, 0, 0), destination);
    }

    // RFC 9293 3.10.7.1: a segment for no connection gets a reset that it will accept.
    private static void refuse(long mac, int source, int localPort, int remotePort, Segment s) {
        if ((s.flags & ACK) != 0) {
            reply(mac, source, localPort, remotePort, s.ack, 0, RST);
        } else {
            int length = s.dataLength + ((s.flags & SYN) != 0 ? 1 : 0) + ((s.flags & FIN) != 0 ? 1 : 0);
            reply(mac, source, localPort, remotePort, 0, s.seq + length, RST | ACK);
        }
    }

    // A frame with the TCP header filled in but for the checksum, and room for {@code length} bytes of data after it.
    private static byte[] frame(long mac, int destination, int sourcePort, int destinationPort, int seq, int ack,
            int flags, int window, int length) {
        int header = headerLength(flags);
        byte[] frame = Ipv4.packet(mac, destination, Ipv4.PROTOCOL_TCP, header + length);
        int at = Network.ETHERNET_HEADER + Ipv4.HEADER;
        Wire.put16(frame, at, sourcePort);
        Wire.put16(frame, at + 2, destinationPort);
        Wire.put32(frame, at + 4, seq);
        Wire.put32(frame, at + 8, ack);
        frame[at + 12] = (byte) (header / 4 << 4);
        frame[at + 13] = (byte) flags;
        Wire.put16(frame, at + 14, window);
        if ((flags & SYN) != 0) {
            frame[at + HEADER] = 2;
            frame[at + HEADER + 1] = 4;
            Wire.put16(frame, at + HEADER + 2, MSS);
        }
        return frame;
    }

    // A SYN carries our MSS.
    private static int headerLength(int flags) {
        return (flags & SYN) != 0 ? HEADER + 4 : HEADER;
    }

    private static void transmit(byte[] frame, int destination) {
        int at = Network.ETHERNET_HEADER + Ipv4.HEADER;
        int length = frame.length - at;
        Wire.put16(frame, at + 16, Wire.checksum(frame, at, length, pseudoHeader(Network.address(), destination, length)));
        if (dropEvery > 0 && ++segmentsSent % dropEvery == 0) {
            return;
        }
        Network.send(frame);
    }

    // The maximum segment size from a SYN's options, or 0 if it has none.
    private static int mssOption(byte[] frame, int at, int length) {
        int end = at + length;
        while (at < end) {
            int kind = frame[at] & 0xFF;
            if (kind == 0) {
                break;
            }
            if (kind == 1) {
                at++;
                continue;
            }
            if (at + 1 >= end) {
                break;
            }
            int size = frame[at + 1] & 0xFF;
            if (size < 2 || at + size > end) {
                break;
            }
            if (kind == 2 && size == 4) {
                return Wire.get16(frame, at + 2);
            }
            at += size;
        }
        return 0;
    }

    private static long pseudoHeader(int source, int destination, int length) {
        return (source >>> 16) + (source & 0xFFFF) + (destination >>> 16) + (destination & 0xFFFF)
                + Ipv4.PROTOCOL_TCP + length;
    }

    // A fresh initial sequence number and the send state that follows from it.
    private static void start(Connection c) {
        c.iss = (int) (HpetClock.nanos() >>> 4) * 0x9E3779B1 ^ (c.localPort << 16 | c.remotePort);
        c.sndUna = c.iss;
        c.sndNxt = c.iss;
        c.sndMax = c.iss;
        c.sendBase = c.iss + 1;
    }

    private static Connection find(int localPort, int remoteAddress, int remotePort) {
        for (Connection c : connections) {
            if (c.localPort == localPort && c.remoteAddress == remoteAddress && c.remotePort == remotePort) {
                return c;
            }
        }
        return null;
    }

    private static int ephemeralPort() {
        if (nextPort == 0) {
            nextPort = FIRST_EPHEMERAL + (int) (HpetClock.nanos() % (65536 - FIRST_EPHEMERAL));
        }
        for (int tries = FIRST_EPHEMERAL; tries < 65536; tries++) {
            int port = nextPort;
            nextPort = port == 65535 ? FIRST_EPHEMERAL : port + 1;
            if (!inUse(port)) {
                return port;
            }
        }
        return 0;
    }

    private static boolean inUse(int port) {
        for (Listener l : listeners) {
            if (l.port == port) {
                return true;
            }
        }
        for (Connection c : connections) {
            if (c.localPort == port) {
                return true;
            }
        }
        return false;
    }

    private static int finSeq(Connection c) {
        return c.sendBase + c.out.count;
    }

    private static boolean sending(int state) {
        return state == ESTABLISHED || state == CLOSE_WAIT;
    }

    private static boolean receiving(int state) {
        return state == ESTABLISHED || state == FIN_WAIT_1 || state == FIN_WAIT_2;
    }

    private static void timeWait(Connection c, long now) {
        c.state = TIME_WAIT;
        c.retransmitAt = 0;
        c.timeWaitUntil = now + TIME_WAIT_NANOS;
        LOCK.notifyAll();
    }

    private static void fail(Connection c, int error) {
        c.error = error;
        c.state = CLOSED;
        remove(c);
        LOCK.notifyAll();
    }

    private static void abort(Connection c) {
        reply(c.peerMac, c.remoteAddress, c.localPort, c.remotePort, c.sndNxt, 0, RST);
        c.listener = null;
        fail(c, -ECONNRESET);
    }

    private static void remove(Connection c) {
        connections.remove(c);
        c.retransmitAt = 0;
    }

    // A byte queue in a fixed array.
    private static final class Ring {
        final byte[] data = new byte[BUFFER];
        int start;
        int count;

        int free() {
            return data.length - count;
        }

        int put(byte[] from, int at, int length) {
            int n = Math.min(length, free());
            int end = (start + count) % data.length;
            int first = Math.min(n, data.length - end);
            System.arraycopy(from, at, data, end, first);
            System.arraycopy(from, at + first, data, 0, n - first);
            count += n;
            return n;
        }

        int take(byte[] to, int at, int length) {
            int n = Math.min(length, count);
            copy(0, to, at, n);
            drop(n);
            return n;
        }

        void copy(int offset, byte[] to, int at, int length) {
            int from = (start + offset) % data.length;
            int first = Math.min(length, data.length - from);
            System.arraycopy(data, from, to, at, first);
            System.arraycopy(data, 0, to, at + first, length - first);
        }

        void drop(int n) {
            start = (start + n) % data.length;
            count -= n;
        }
    }
}
