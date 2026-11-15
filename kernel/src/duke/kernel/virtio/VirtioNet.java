package duke.kernel.virtio;

import duke.kernel.mm.PhysicalMemory;
import duke.kernel.pci.Pci;
import duke.rt.Magic;
import duke.rt.Tib;

/**
 * A virtio network device: Ethernet frames out through the transmit queue, one at a time, and in
 * through a receive queue kept full of buffers. Every frame carries a 12-byte virtio-net header in
 * front, all zeros on the way out since we offer no checksum or segmentation offload.
 */
public final class VirtioNet {

    public static final int MTU = 1500;

    private static final int TRANSITIONAL_ID = 0x1000;
    private static final int MODERN_ID = 0x1041;
    private static final long FEATURE_MAC = 1L << 5;
    private static final long FEATURE_VERSION_1 = 1L << 32;
    private static final int RECEIVE_QUEUE = 0;
    private static final int TRANSMIT_QUEUE = 1;
    private static final int HEADER = 12;
    // Without mergeable receive buffers, each one must hold a whole frame: 12 + 14 + 1500 bytes.
    private static final int BUFFER = 2048;
    private static final int BUFFERS_PER_FRAME = (int) (PhysicalMemory.PAGE_SIZE / BUFFER);

    private static VirtioNet device;

    private final VirtioPci transport;
    private final Virtqueue receive;
    private final Virtqueue transmit;
    private final long[] buffers;
    private final long transmitBuffer;
    private final long mac;

    private VirtioNet(VirtioPci transport) {
        this.transport = transport;
        transport.reset();
        transport.addStatus(VirtioPci.ACKNOWLEDGE | VirtioPci.DRIVER);
        long offered = transport.deviceFeatures();
        long wanted = FEATURE_VERSION_1 | FEATURE_MAC;
        if ((offered & wanted) != wanted || !transport.negotiate(wanted)) {
            transport.addStatus(VirtioPci.FAILED);
            throw new IllegalStateException("virtio-net at " + transport.function.address() + " refused virtio 1.0 with a MAC");
        }
        receive = transport.queue(RECEIVE_QUEUE);
        transmit = transport.queue(TRANSMIT_QUEUE);
        buffers = new long[receive.size()];
        long frame = 0;
        for (int i = 0; i < buffers.length; i++) {
            if (i % BUFFERS_PER_FRAME == 0) {
                frame = PhysicalMemory.allocateZeroed();
            }
            buffers[i] = frame + (long) (i % BUFFERS_PER_FRAME) * BUFFER;
            receive.descriptor(i, buffers[i], BUFFER, Virtqueue.WRITE, 0);
            receive.post(i);
        }
        transmitBuffer = PhysicalMemory.allocateZeroed();
        long config = transport.deviceConfig();
        long address = 0;
        for (int i = 0; i < 6; i++) {
            address = address << 8 | Magic.peekByte(config + i) & 0xFF;
        }
        mac = address;
        transport.addStatus(VirtioPci.DRIVER_OK);
        receive.notifyDevice();
    }

    /** Takes the first virtio-net device on the bus, if there is one. */
    public static void init() {
        for (Pci.Function f : Pci.functions()) {
            if (f.vendorId == VirtioPci.VENDOR && (f.deviceId == TRANSITIONAL_ID || f.deviceId == MODERN_ID)) {
                device = new VirtioNet(new VirtioPci(f));
                return;
            }
        }
    }

    /** The device, or null without one. */
    public static VirtioNet device() {
        return device;
    }

    public Pci.Function function() {
        return transport.function;
    }

    /** The 48-bit MAC address, first byte on the wire in the top bits. */
    public long mac() {
        return mac;
    }

    /** Sends the first {@code length} bytes of {@code frame}, an Ethernet frame without its FCS. */
    public void send(byte[] frame, int length) {
        if (length > 14 + MTU) {
            throw new IllegalArgumentException("a frame of " + length + " bytes");
        }
        synchronized (transmit) {
            long staging = PhysicalMemory.toVirtual(transmitBuffer);
            Magic.fillMemory(staging, 0, HEADER);
            Magic.copyMemory(staging + HEADER, Magic.addressOf(frame) + Tib.ARRAY_DATA, length);
            transmit.descriptor(0, transmitBuffer, HEADER + length, 0, 0);
            transmit.run(0);
        }
    }

    /** The next frame received, without the virtio-net header, or null if none has come in. */
    public byte[] receive() {
        synchronized (receive) {
            long used = receive.poll();
            if (used < 0) {
                return null;
            }
            int index = (int) (used >>> 32);
            int length = (int) used - HEADER;
            byte[] frame = new byte[Math.max(length, 0)];
            Magic.copyMemory(Magic.addressOf(frame) + Tib.ARRAY_DATA, PhysicalMemory.toVirtual(buffers[index]) + HEADER, frame.length);
            receive.post(index);
            receive.notifyDevice();
            return frame;
        }
    }
}
