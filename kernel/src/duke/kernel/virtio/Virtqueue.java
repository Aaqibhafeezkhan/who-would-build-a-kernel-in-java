package duke.kernel.virtio;

import duke.kernel.mm.PhysicalMemory;
import duke.kernel.time.HpetClock;
import duke.rt.Magic;

/**
 * A split virtqueue in one frame: descriptor table, then the available ring, then the used ring.
 * Polled: the device is told not to interrupt. {@link #run} keeps one request in flight and waits
 * for it; {@link #post} and {@link #poll} keep several, for buffers the device fills when it likes.
 */
final class Virtqueue {

    static final int SIZE = 16;
    static final int NEXT = 1;
    static final int WRITE = 2;

    private static final int AVAILABLE = 256;
    private static final int USED = 512;
    private static final int NO_INTERRUPT = 1;
    private static final long TIMEOUT_NANOS = 5_000_000_000L;

    private final int index;
    private final int size;
    private final long doorbell;
    private final long frame;
    private final long base;
    private int nextAvailable;
    private int lastUsed;

    Virtqueue(int index, int size, long doorbell) {
        this.index = index;
        this.size = size;
        this.doorbell = doorbell;
        frame = PhysicalMemory.allocateZeroed();
        base = PhysicalMemory.toVirtual(frame);
        Magic.pokeShort(base + AVAILABLE, (short) NO_INTERRUPT);
    }

    long descriptors() {
        return frame;
    }

    long available() {
        return frame + AVAILABLE;
    }

    long used() {
        return frame + USED;
    }

    void descriptor(int i, long physical, int length, int flags, int next) {
        long d = base + 16L * i;
        Magic.pokeLong(d, physical);
        Magic.pokeInt(d + 8, length);
        Magic.pokeShort(d + 12, (short) flags);
        Magic.pokeShort(d + 14, (short) next);
    }

    int size() {
        return size;
    }

    /** Hands the chain starting at {@code head} to the device without waiting for it. */
    void post(int head) {
        Magic.pokeShort(base + AVAILABLE + 4 + 2L * (nextAvailable % size), (short) head);
        // x86 keeps stores in order, so the device sees the ring entry before the index that publishes it.
        nextAvailable = (nextAvailable + 1) & 0xFFFF;
        Magic.pokeShort(base + AVAILABLE + 2, (short) nextAvailable);
    }

    void notifyDevice() {
        Magic.pokeShort(doorbell, (short) index);
    }

    /** The next chain the device has finished with, as its head index << 32 | bytes written, or -1. */
    long poll() {
        if ((Magic.peekShort(base + USED + 2) & 0xFFFF) == lastUsed) {
            return -1;
        }
        long entry = base + USED + 4 + 8L * (lastUsed % size);
        long used = (long) Magic.peekInt(entry) << 32 | Magic.peekInt(entry + 4) & 0xFFFF_FFFFL;
        lastUsed = (lastUsed + 1) & 0xFFFF;
        return used;
    }

    /** Hands the chain starting at {@code head} to the device and waits for it to come back. */
    void run(int head) {
        post(head);
        notifyDevice();
        long deadline = HpetClock.nanos() + TIMEOUT_NANOS;
        while ((Magic.peekShort(base + USED + 2) & 0xFFFF) == lastUsed) {
            if (HpetClock.nanos() > deadline) {
                throw new IllegalStateException("virtio queue " + index + ": no answer in 5 s");
            }
            Magic.pause();
        }
        lastUsed = (lastUsed + 1) & 0xFFFF;
    }
}
