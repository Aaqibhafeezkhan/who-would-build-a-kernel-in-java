package duke.kernel.net;

import java.util.HashMap;

/** UDP: each datagram to whatever is bound to its port, and out with the checksum over the pseudo-header. */
public final class Udp {

    static final int HEADER = 8;
    public static final int MAX_PAYLOAD = 1500 - Ipv4.HEADER - HEADER;

    /** Gets the datagrams for one port, on whichever thread read the frame, so it mustn't wait on the network. */
    public interface Receiver {
        void receive(int source, int sourcePort, byte[] data);
    }

    private static final HashMap<Integer, Receiver> receivers = new HashMap<>();

    private Udp() {
    }

    /** Sends what arrives for {@code port} to {@code receiver}; false if something has the port already. */
    public static boolean bind(int port, Receiver receiver) {
        synchronized (receivers) {
            if (receivers.containsKey(port)) {
                return false;
            }
            receivers.put(port, receiver);
            return true;
        }
    }

    public static void unbind(int port) {
        synchronized (receivers) {
            receivers.remove(port);
        }
    }

    static void input(int source, int destination, byte[] frame, int at, int length) {
        if (length < HEADER) {
            return;
        }
        int udpLength = Wire.get16(frame, at + 4);
        if (udpLength < HEADER || udpLength > length) {
            return;
        }
        // A zero checksum means the sender didn't compute one, which UDP over IPv4 allows.
        if (Wire.get16(frame, at + 6) != 0
                && Wire.checksum(frame, at, udpLength, pseudoHeader(source, destination, udpLength)) != 0) {
            return;
        }
        Receiver receiver;
        synchronized (receivers) {
            receiver = receivers.get(Wire.get16(frame, at + 2));
        }
        if (receiver != null) {
            byte[] data = new byte[udpLength - HEADER];
            System.arraycopy(frame, at + HEADER, data, 0, data.length);
            receiver.receive(source, Wire.get16(frame, at), data);
        }
    }

    /** Sends {@code data} as one datagram; false if ARP found no next hop. */
    public static boolean send(int destination, int sourcePort, int destinationPort, byte[] data) {
        if (data.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("a datagram of " + data.length + " bytes");
        }
        long nextHop = Ipv4.nextHop(destination);
        if (nextHop < 0) {
            return false;
        }
        int length = HEADER + data.length;
        byte[] frame = Ipv4.packet(nextHop, destination, Ipv4.PROTOCOL_UDP, length);
        int at = Network.ETHERNET_HEADER + Ipv4.HEADER;
        Wire.put16(frame, at, sourcePort);
        Wire.put16(frame, at + 2, destinationPort);
        Wire.put16(frame, at + 4, length);
        System.arraycopy(data, 0, frame, at + HEADER, data.length);
        int checksum = Wire.checksum(frame, at, length, pseudoHeader(Network.address(), destination, length));
        // 0 would say there's no checksum; its ones' complement twin means the same sum.
        Wire.put16(frame, at + 6, checksum == 0 ? 0xFFFF : checksum);
        Network.send(frame);
        return true;
    }

    private static long pseudoHeader(int source, int destination, int length) {
        return (source >>> 16) + (source & 0xFFFF) + (destination >>> 16) + (destination & 0xFFFF)
                + Ipv4.PROTOCOL_UDP + length;
    }
}
