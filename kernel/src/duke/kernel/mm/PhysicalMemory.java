package duke.kernel.mm;

import duke.boot.Limine;
import duke.rt.Magic;
import duke.rt.SpinLock;

/**
 * Physical page frames from the Limine memory map. Frames are named by physical address; the
 * kernel touches them through the higher-half direct map. Only "usable" regions are handed out.
 * Bootloader-reclaimable memory still holds Limine's page tables and responses until we have our
 * own (#15), so it stays reserved for now.
 */
public final class PhysicalMemory {

    public static final long PAGE_SIZE = 4096;

    /** Guards the bitmap: the heap grows under its own lock and thread stacks under the scheduler's. */
    private static final int[] LOCK = new int[1];
    private static FrameBitmap frames;
    /** Per frame, how many page tables map it besides the first. Made by the first fork. */
    private static int[] sharers;
    private static long hhdm;

    private PhysicalMemory() {
    }

    public static void init() {
        hhdm = Limine.hhdmOffset();
        // Size the bitmap for reclaimable memory too: some of it sits above the last usable region.
        long top = 0;
        for (int i = 0; i < Limine.memoryMapSize(); i++) {
            int type = Limine.memoryMapType(i);
            if (type == Limine.MEMMAP_USABLE || type == Limine.MEMMAP_BOOTLOADER_RECLAIMABLE) {
                top = Math.max(top, Limine.memoryMapBase(i) + Limine.memoryMapLength(i));
            }
        }
        frames = new FrameBitmap(top / PAGE_SIZE);
        releaseRegions(Limine.MEMMAP_USABLE);
    }

    /**
     * Hands Limine's leftovers to the allocator: its page tables, GDT, stack and responses. Only
     * safe once we run on our own page tables and GDT, and Limine.snapshot() has copied the
     * responses we still need. Returns the bytes reclaimed.
     */
    public static long reclaimBootloaderMemory() {
        long before = frames.freePages();
        releaseRegions(Limine.MEMMAP_BOOTLOADER_RECLAIMABLE);
        return (frames.freePages() - before) * PAGE_SIZE;
    }

    private static void releaseRegions(int type) {
        for (int i = 0; i < Limine.memoryMapSize(); i++) {
            if (Limine.memoryMapType(i) == type) {
                // Regions are page-aligned per the protocol; round inward anyway.
                // Never hand out frame 0: 0 reads as "no frame" everywhere, and it's the real-mode IVT.
                long first = Math.max(1, (Limine.memoryMapBase(i) + PAGE_SIZE - 1) / PAGE_SIZE);
                long end = (Limine.memoryMapBase(i) + Limine.memoryMapLength(i)) / PAGE_SIZE;
                if (end > first) {
                    frames.release(first, end - first);
                }
            }
        }
    }

    /** A free frame's physical address. Contents are whatever was there before. */
    public static long allocate() {
        long frame = tryAllocate();
        if (frame < 0) {
            throw new OutOfMemoryError("out of physical memory");
        }
        return frame;
    }

    /** Like allocate(), but -1 instead of throwing: for paths that can't allocate an exception. */
    public static long tryAllocate() {
        long flags = SpinLock.lockInterruptsOff(LOCK);
        long page = frames.allocate();
        SpinLock.unlockInterruptsOff(LOCK, flags);
        return page < 0 ? -1 : page * PAGE_SIZE;
    }

    public static long allocateZeroed() {
        long frame = tryAllocateZeroed();
        if (frame < 0) {
            throw new OutOfMemoryError("out of physical memory");
        }
        return frame;
    }

    /** Like allocateZeroed(), but -1 instead of throwing when no frame is available. */
    public static long tryAllocateZeroed() {
        long frame = tryAllocate();
        if (frame >= 0) {
            Magic.zeroMemoryWords(toVirtual(frame), PAGE_SIZE / 8);
        }
        return frame;
    }

    public static void free(long physical) {
        if (physical % PAGE_SIZE != 0) {
            throw new IllegalArgumentException("not a frame address: 0x" + Long.toHexString(physical));
        }
        long flags = SpinLock.lockInterruptsOff(LOCK);
        try {
            frames.free(physical / PAGE_SIZE);
        } finally {
            SpinLock.unlockInterruptsOff(LOCK, flags);
        }
    }

    /** Makes room to count sharers, so {@link #share} allocates nothing under a spin lock. */
    public static void prepareSharing() {
        if (sharers == null) {
            int[] counts = new int[(int) frames.pages()];
            long flags = SpinLock.lockInterruptsOff(LOCK);
            if (sharers == null) {
                sharers = counts;
            }
            SpinLock.unlockInterruptsOff(LOCK, flags);
        }
    }

    /** One more page table maps {@code physical}, which then takes one more {@link #drop} to free. */
    public static void share(long physical) {
        long flags = SpinLock.lockInterruptsOff(LOCK);
        sharers[(int) (physical / PAGE_SIZE)]++;
        SpinLock.unlockInterruptsOff(LOCK, flags);
    }

    /** Whether some other page table maps {@code physical} too. */
    public static boolean shared(long physical) {
        long flags = SpinLock.lockInterruptsOff(LOCK);
        boolean shared = sharers != null && sharers[(int) (physical / PAGE_SIZE)] > 0;
        SpinLock.unlockInterruptsOff(LOCK, flags);
        return shared;
    }

    /** A page table stops mapping {@code physical}: frees it if that was the last one. */
    public static void drop(long physical) {
        long flags = SpinLock.lockInterruptsOff(LOCK);
        int page = (int) (physical / PAGE_SIZE);
        boolean last = sharers == null || sharers[page] == 0;
        if (!last) {
            sharers[page]--;
        }
        SpinLock.unlockInterruptsOff(LOCK, flags);
        if (last) {
            free(physical);
        }
    }

    public static long toVirtual(long physical) {
        return physical + hhdm;
    }

    public static long freeFrames() {
        return frames.freePages();
    }
}
