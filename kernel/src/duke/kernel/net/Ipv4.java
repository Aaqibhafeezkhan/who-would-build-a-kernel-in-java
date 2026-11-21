package duke.kernel.net;

/**
 * IPv4 without options, fragments or forwarding: what's addressed to us goes up, the rest is dropped.
 * Before we have an address, UDP to anyone comes up too, since DHCP may answer to the offered one.
 * 127.0.0.0/8 never reaches the NIC: {@link Network} hands it straight back.
 */
public final class Ipv4 {

    static final int HEADER = 20;
    static final int PROTOCOL_ICMP = 1;
    static final int PROTOCOL_TCP = 6;
    static final int PROTOCOL_UDP = 17;
    static final int BROADCAST = 0xFFFF_FFFF;

    private static final int DONT_FRAGMENT = 0x4000;
    private static final int TTL = 64;

    private static final Object LOCK = new Object();
    private static int nextId;

    private Ipv4() {
    }

    public static int address(int a, int b, int c, int d) {
        return a << 24 | b << 16 | c << 8 | d;
    }

    /** {@code a.b.c.d} as an address, or null if it isn't one. */
    public static Integer parse(String text) {
        int address = 0;
        int octets = 0;
        int value = -1;
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : '.';
            if (c >= '0' && c <= '9' && value < 256) {
                value = (value < 0 ? 0 : value * 10) + (c - '0');
            } else if (c == '.' && value >= 0 && value < 256 && octets < 4) {
                address = address << 8 | value;
                octets++;
                value = -1;
            } else {
                return null;
            }
        }
        return octets == 4 ? address : null;
    }

    public static String format(int address) {
        return (address >>> 24) + "." + (address >>> 16 & 0xFF) + "." + (address >>> 8 & 0xFF) + "." + (address & 0xFF);
    }

    public static boolean loopback(int address) {
        return address >>> 24 == 127;
    }

    /**
     * The address we send to {@code destination} from. Linux sends to any of 127.0.0.0/8 from
     * 127.0.0.1, but with one connection table keyed on the peer's address, a reply from where it was
     * sent is what keeps 127.0.0.2 working.
     */
    static int source(int destination) {
        return loopback(destination) ? destination : Network.address();
    }

    /** {@code looped} says the frame came back from {@link Network#send}, the only way to 127.0.0.0/8. */
    static void input(long sourceMac, byte[] frame, boolean looped) {
        int at = Network.ETHERNET_HEADER;
        if (frame.length < at + HEADER || (frame[at] & 0xFF) != 0x45) {
            return;
        }
        int total = Wire.get16(frame, at + 2);
        if (total < HEADER || at + total > frame.length || Wire.checksum(frame, at, HEADER) != 0
                || (Wire.get16(frame, at + 6) & 0x3FFF) != 0) {
            return;
        }
        int source = Wire.get32(frame, at + 12);
        int destination = Wire.get32(frame, at + 16);
        int protocol = frame[at + 9] & 0xFF;
        int ours = looped && loopback(destination) ? destination : Network.address();
        if (destination != ours && destination != BROADCAST && !(ours == 0 && protocol == PROTOCOL_UDP)
                || loopback(source) != looped) {
            return;
        }
        if (protocol == PROTOCOL_ICMP && destination == ours) {
            Icmp.input(sourceMac, source, frame, at + HEADER, total - HEADER);
        } else if (protocol == PROTOCOL_TCP && destination == ours) {
            Tcp.input(sourceMac, source, destination, frame, at + HEADER, total - HEADER);
        } else if (protocol == PROTOCOL_UDP) {
            Udp.input(source, destination, frame, at + HEADER, total - HEADER);
        }
    }

    /** A frame for {@code payloadLength} bytes of {@code protocol} to {@code destination}, the IP header filled in. */
    static byte[] packet(long nextHopMac, int destination, int protocol, int payloadLength) {
        byte[] frame = Network.frame(nextHopMac, Network.TYPE_IPV4, HEADER + payloadLength);
        int at = Network.ETHERNET_HEADER;
        frame[at] = 0x45;
        Wire.put16(frame, at + 2, HEADER + payloadLength);
        synchronized (LOCK) {
            Wire.put16(frame, at + 4, nextId++);
        }
        Wire.put16(frame, at + 6, DONT_FRAGMENT);
        frame[at + 8] = (byte) TTL;
        frame[at + 9] = (byte) protocol;
        Wire.put32(frame, at + 12, source(destination));
        Wire.put32(frame, at + 16, destination);
        Wire.put16(frame, at + 10, Wire.checksum(frame, at, HEADER));
        return frame;
    }

    /**
     * The MAC to send to for {@code destination}: everyone's for the broadcast address, its own on
     * our link, the gateway's otherwise; -1 if ARP got no answer.
     */
    static long nextHop(int destination) {
        if (loopback(destination)) {
            return 0;
        }
        if (destination == BROADCAST) {
            return Network.BROADCAST;
        }
        return Arp.resolve(Network.onLink(destination) ? destination : Network.gateway());
    }
}
