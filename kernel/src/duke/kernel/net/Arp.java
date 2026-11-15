package duke.kernel.net;

import duke.kernel.time.HpetClock;
import java.util.HashMap;

/** ARP for IPv4 over Ethernet: answers who-has for our address and finds the MAC behind others. */
final class Arp {

    private static final int LENGTH = 28;
    private static final int REQUEST = 1;
    private static final int REPLY = 2;

    private static final HashMap<Integer, Long> cache = new HashMap<>();

    private Arp() {
    }

    static void input(byte[] frame) {
        int at = Network.ETHERNET_HEADER;
        if (frame.length < at + LENGTH || Wire.get16(frame, at) != 1 || Wire.get16(frame, at + 2) != Network.TYPE_IPV4
                || frame[at + 4] != 6 || frame[at + 5] != 4) {
            return;
        }
        int operation = Wire.get16(frame, at + 6);
        long senderMac = Wire.get48(frame, at + 8);
        int sender = Wire.get32(frame, at + 14);
        int target = Wire.get32(frame, at + 24);
        if (target != Network.address() || target == 0) {
            return;
        }
        synchronized (cache) {
            cache.put(sender, senderMac);
        }
        if (operation == REQUEST) {
            Network.send(packet(REPLY, senderMac, sender));
        }
    }

    /** The MAC for {@code address}, asking for it if we don't know it yet, or -1 after a second with no answer. */
    static long resolve(int address) {
        long known = cached(address);
        if (known >= 0) {
            return known;
        }
        Network.send(packet(REQUEST, Network.BROADCAST, address));
        long deadline = HpetClock.nanos() + 1_000_000_000L;
        while (HpetClock.nanos() < deadline) {
            if (!Network.poll()) {
                Thread.yield();
            }
            known = cached(address);
            if (known >= 0) {
                return known;
            }
        }
        return -1;
    }

    private static long cached(int address) {
        synchronized (cache) {
            Long mac = cache.get(address);
            return mac == null ? -1 : mac;
        }
    }

    private static byte[] packet(int operation, long targetMac, int target) {
        byte[] frame = Network.frame(targetMac, Network.TYPE_ARP, LENGTH);
        int at = Network.ETHERNET_HEADER;
        Wire.put16(frame, at, 1);
        Wire.put16(frame, at + 2, Network.TYPE_IPV4);
        frame[at + 4] = 6;
        frame[at + 5] = 4;
        Wire.put16(frame, at + 6, operation);
        Wire.put48(frame, at + 8, Network.mac());
        Wire.put32(frame, at + 14, Network.address());
        Wire.put48(frame, at + 18, operation == REPLY ? targetMac : 0);
        Wire.put32(frame, at + 24, target);
        return frame;
    }
}
