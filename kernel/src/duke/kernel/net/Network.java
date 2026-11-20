package duke.kernel.net;

import duke.kernel.Console;
import duke.kernel.time.HpetClock;
import duke.kernel.virtio.VirtioNet;

/**
 * The one network interface, on the virtio-net device, its address from DHCP. A kernel thread asks
 * for one, then reads frames every tick; anything waiting on an answer, like ping, polls as well so
 * its round trip isn't rounded up to the next tick.
 */
public final class Network {

    // What QEMU's user-mode network hands out, for when no DHCP server answers.
    static final int FALLBACK_ADDRESS = Ipv4.address(10, 0, 2, 15);
    static final int FALLBACK_NETMASK = Ipv4.address(255, 255, 255, 0);
    static final int FALLBACK_GATEWAY = Ipv4.address(10, 0, 2, 2);

    static final long BROADCAST = 0xFFFF_FFFF_FFFFL;
    static final int ETHERNET_HEADER = 14;
    static final int TYPE_IPV4 = 0x0800;
    static final int TYPE_ARP = 0x0806;

    private static final Object LOCK = new Object();
    private static VirtioNet nic;
    // 0 until DHCP or the fallback configures the interface.
    private static volatile int address;
    private static volatile int netmask;
    private static volatile int gateway;
    private static volatile int dns;
    private static volatile int dhcpServer;

    private Network() {
    }

    public static void init() {
        VirtioNet.init();
        nic = VirtioNet.device();
        if (nic == null) {
            return;
        }
        Thread reader = new Thread(() -> {
            Dhcp.configure();
            while (true) {
                poll();
                Tcp.tick();
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

    /** Our address, or 0 before the interface is configured. */
    public static int address() {
        return address;
    }

    public static int netmask() {
        return netmask;
    }

    public static int gateway() {
        return gateway;
    }

    /** The DNS server DHCP named, or 0. */
    public static int dns() {
        return dns;
    }

    /** {@code server} is the DHCP server's address, or 0 for the fallback. */
    static void configure(int address, int netmask, int gateway, int dns, int server) {
        Network.netmask = netmask;
        Network.gateway = gateway;
        Network.dns = dns;
        Network.dhcpServer = server;
        // Last: a nonzero address is what says the rest is set.
        Network.address = address;
    }

    /** For the boot log: waits for the interface to be configured, then prints how it was. */
    public static void printConfiguration() {
        if (nic == null) {
            return;
        }
        // Each attempt is a discover and a request, each waiting out its timeout at worst.
        long deadline = HpetClock.nanos() + (2L * Dhcp.ATTEMPTS + 1) * Dhcp.TIMEOUT_NANOS;
        while (address == 0 && HpetClock.nanos() < deadline) {
            Thread.yield();
        }
        if (address == 0) {
            Console.println("net: virtio-net " + Wire.mac(nic.mac()) + ", no address yet");
            return;
        }
        Console.println("net: virtio-net " + Wire.mac(nic.mac()) + ", " + Ipv4.format(address) + "/"
                + Integer.bitCount(netmask) + " via " + Ipv4.format(gateway)
                + (dhcpServer != 0 ? " from DHCP server " + Ipv4.format(dhcpServer) : ", no DHCP answer so QEMU's usual address"));
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

    /** Whether {@code destination} is on our link, so it's reached directly rather than through the gateway. */
    static boolean onLink(int destination) {
        return (destination & netmask) == (address & netmask);
    }
}
