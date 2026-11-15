package duke.kernel.net;

import duke.kernel.virtio.VirtioNet;

/**
 * The one network interface, on the virtio-net device, with the address QEMU's user-mode network
 * hands out by DHCP. A kernel thread reads frames every tick; anything waiting on an answer, like
 * ping, polls as well so its round trip isn't rounded up to the next tick.
 */
public final class Network {

    public static final int ADDRESS = Ipv4.address(10, 0, 2, 15);
    public static final int NETMASK = Ipv4.address(255, 255, 255, 0);
    public static final int GATEWAY = Ipv4.address(10, 0, 2, 2);

    static final long BROADCAST = 0xFFFF_FFFF_FFFFL;
    static final int ETHERNET_HEADER = 14;
    static final int TYPE_IPV4 = 0x0800;
    static final int TYPE_ARP = 0x0806;

    private static final Object LOCK = new Object();
    private static VirtioNet nic;

    private Network() {
    }

    public static void init() {
        VirtioNet.init();
        nic = VirtioNet.device();
        if (nic == null) {
            return;
        }
        Thread reader = new Thread(() -> {
            while (true) {
                poll();
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "net");
        reader.start();
    }

    public static boolean up() {
        return nic != null;
    }

    static long mac() {
        return nic.mac();
    }

    /** Handles every frame that has come in; true if there was one. */
    static boolean poll() {
        boolean any = false;
        synchronized (LOCK) {
            for (byte[] frame = nic.receive(); frame != null; frame = nic.receive()) {
                any = true;
                if (frame.length < ETHERNET_HEADER) {
                    continue;
                }
                long source = Wire.get48(frame, 6);
                switch (Wire.get16(frame, 12)) {
                    case TYPE_ARP -> Arp.input(frame);
                    case TYPE_IPV4 -> Ipv4.input(source, frame);
                    default -> {
                    }
                }
            }
        }
        return any;
    }

    /** A frame for {@code payloadLength} bytes of {@code type} to {@code destination}, its header filled in. */
    static byte[] frame(long destination, int type, int payloadLength) {
        byte[] frame = new byte[ETHERNET_HEADER + payloadLength];
        Wire.put48(frame, 0, destination);
        Wire.put48(frame, 6, nic.mac());
        Wire.put16(frame, 12, type);
        return frame;
    }

    static void send(byte[] frame) {
        nic.send(frame, frame.length);
    }

    /** Whether {@code address} is on our link, so it's reached directly rather than through the gateway. */
    static boolean onLink(int address) {
        return (address & NETMASK) == (ADDRESS & NETMASK);
    }
}
