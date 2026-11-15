package duke.kernel.net;

import duke.kernel.time.HpetClock;

/**
 * A DHCP client for the boot (RFC 2131): discover, take the first offer, request it, and configure
 * the interface from the ack. No lease renewal: QEMU's server leases for a day. Without an answer
 * the interface gets QEMU's usual address instead.
 */
final class Dhcp {

    static final long TIMEOUT_NANOS = 1_000_000_000L;
    static final int ATTEMPTS = 3;

    private static final int CLIENT_PORT = 68;
    private static final int SERVER_PORT = 67;
    private static final int REQUEST = 1;
    private static final int REPLY = 2;
    private static final int DISCOVER = 1;
    private static final int OFFER = 2;
    private static final int REQUEST_TYPE = 3;
    private static final int ACK = 5;
    private static final int NAK = 6;
    private static final int OPTION_PAD = 0;
    private static final int OPTION_NETMASK = 1;
    private static final int OPTION_ROUTER = 3;
    private static final int OPTION_DNS = 6;
    private static final int OPTION_REQUESTED_ADDRESS = 50;
    private static final int OPTION_MESSAGE_TYPE = 53;
    private static final int OPTION_SERVER = 54;
    private static final int OPTION_PARAMETERS = 55;
    private static final int OPTION_END = 255;
    private static final int COOKIE = 0x63825363;
    // The fixed part, then the cookie and options padded to BOOTP's 312 bytes, which some servers want.
    private static final int OPTIONS = 236;
    private static final int LENGTH = OPTIONS + 312;
    private static final int BROADCAST_FLAG = 0x8000;

    private static final Object LOCK = new Object();
    private static int transaction;
    // The last reply for our transaction: its type, the address in it and the options we use.
    private static int replyType;
    private static int offered;
    private static int server;
    private static int netmask;
    private static int router;
    private static int dns;

    private Dhcp() {
    }

    /** On the net thread, before it starts reading frames on its own: it reads them here while it waits. */
    static void configure() {
        if (!Udp.bind(CLIENT_PORT, Dhcp::receive)) {
            Network.configure(Network.FALLBACK_ADDRESS, Network.FALLBACK_NETMASK, Network.FALLBACK_GATEWAY, 0, 0);
            return;
        }
        try {
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                synchronized (LOCK) {
                    transaction = (int) HpetClock.nanos() ^ (int) Network.mac() ^ attempt;
                }
                if (exchange(DISCOVER, 0, 0) != OFFER) {
                    continue;
                }
                int address;
                int from;
                synchronized (LOCK) {
                    address = offered;
                    from = server;
                }
                if (exchange(REQUEST_TYPE, address, from) == ACK) {
                    synchronized (LOCK) {
                        Network.configure(offered, netmask != 0 ? netmask : Network.FALLBACK_NETMASK, router, dns, server);
                    }
                    return;
                }
            }
            Network.configure(Network.FALLBACK_ADDRESS, Network.FALLBACK_NETMASK, Network.FALLBACK_GATEWAY, 0, 0);
        } finally {
            Udp.unbind(CLIENT_PORT);
        }
    }

    // Broadcasts one message and waits for the server's reply to it; its type, or 0 after the timeout.
    private static int exchange(int type, int requested, int serverId) {
        synchronized (LOCK) {
            replyType = 0;
        }
        Udp.send(Ipv4.BROADCAST, CLIENT_PORT, SERVER_PORT, message(type, requested, serverId));
        long deadline = HpetClock.nanos() + TIMEOUT_NANOS;
        while (HpetClock.nanos() < deadline) {
            if (!Network.poll()) {
                Thread.yield();
            }
            synchronized (LOCK) {
                if (replyType != 0) {
                    return replyType;
                }
            }
        }
        return 0;
    }

    private static byte[] message(int type, int requested, int serverId) {
        byte[] m = new byte[LENGTH];
        m[0] = REQUEST;
        m[1] = 1;
        m[2] = 6;
        synchronized (LOCK) {
            Wire.put32(m, 4, transaction);
        }
        // We can't take unicast to an address we don't have yet.
        Wire.put16(m, 10, BROADCAST_FLAG);
        Wire.put48(m, 28, Network.mac());
        Wire.put32(m, OPTIONS, COOKIE);
        int at = OPTIONS + 4;
        m[at++] = OPTION_MESSAGE_TYPE;
        m[at++] = 1;
        m[at++] = (byte) type;
        if (requested != 0) {
            m[at++] = OPTION_REQUESTED_ADDRESS;
            m[at++] = 4;
            Wire.put32(m, at, requested);
            at += 4;
        }
        if (serverId != 0) {
            m[at++] = OPTION_SERVER;
            m[at++] = 4;
            Wire.put32(m, at, serverId);
            at += 4;
        }
        m[at++] = OPTION_PARAMETERS;
        m[at++] = 3;
        m[at++] = OPTION_NETMASK;
        m[at++] = OPTION_ROUTER;
        m[at++] = OPTION_DNS;
        m[at] = (byte) OPTION_END;
        return m;
    }

    private static void receive(int source, int sourcePort, byte[] m) {
        if (sourcePort != SERVER_PORT || m.length < OPTIONS + 4 || m[0] != REPLY
                || Wire.get32(m, OPTIONS) != COOKIE || Wire.get48(m, 28) != Network.mac()) {
            return;
        }
        int type = 0;
        int serverId = 0;
        int mask = 0;
        int gateway = 0;
        int nameServer = 0;
        for (int at = OPTIONS + 4; at < m.length; ) {
            int option = m[at] & 0xFF;
            if (option == OPTION_END) {
                break;
            }
            if (option == OPTION_PAD) {
                at++;
                continue;
            }
            if (at + 1 >= m.length) {
                break;
            }
            int length = m[at + 1] & 0xFF;
            int value = at + 2;
            if (value + length > m.length) {
                break;
            }
            if (option == OPTION_MESSAGE_TYPE && length >= 1) {
                type = m[value] & 0xFF;
            } else if (length >= 4) {
                switch (option) {
                    case OPTION_SERVER -> serverId = Wire.get32(m, value);
                    case OPTION_NETMASK -> mask = Wire.get32(m, value);
                    case OPTION_ROUTER -> gateway = Wire.get32(m, value);
                    case OPTION_DNS -> nameServer = Wire.get32(m, value);
                    default -> {
                    }
                }
            }
            at = value + length;
        }
        synchronized (LOCK) {
            if (Wire.get32(m, 4) != transaction || replyType != 0 || (type != OFFER && type != ACK && type != NAK)) {
                return;
            }
            replyType = type;
            offered = Wire.get32(m, 16);
            server = serverId != 0 ? serverId : source;
            netmask = mask;
            router = gateway;
            dns = nameServer;
        }
    }
}
