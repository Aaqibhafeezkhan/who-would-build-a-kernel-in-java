package duke.kernel.mm;

import duke.rt.SpinLock;
import java.util.Arrays;

/**
 * A process's memory: its own lower half, and the kernel's higher half shared with everyone. Every
 * frame mapped into it belongs to it and goes back with {@link #free}.
 *
 * <p>Pages are mapped up front with {@link #allocate}, or reserved with {@link #reserve} and mapped
 * zeroed the first time they're touched ({@link #pageIn}), so a process can ask for far more than
 * it uses, as Linux lets it.
 *
 * <p>A process's threads share it, on any CPU at once. Page faults map pages under a spin lock, since
 * they come with interrupts off, and search the reservations under it too: {@link #unmap} can take
 * one away while a fault is looking. Everything else that changes them holds the monitor as well.
 */
public final class AddressSpace {

    /** One past the highest user address: the lower half. */
    public static final long END = 0x0000_8000_0000_0000L;

    private static final long PAGE = PhysicalMemory.PAGE_SIZE;

    private final PageTable table = PageTable.sharingHigherHalf(KernelAddressSpace.table());
    /** Pages unmap clears before flushing TLBs and freeing them, so it never holds the spin lock long. */
    private static final int UNMAP_BATCH = 512;

    // Reserved ranges as (start, end, flags), so pageIn can search them without allocating.
    private long[] ranges = new long[3 * 4];
    private int reserved;
    private final int[] pageInLock = new int[1];
    // Where mmap looks for room: the gap between the executable and the stack, filled from the top.
    private long mapFloor = END;
    private long mapTop = END;

    public PageTable table() {
        return table;
    }

    /** Maps a zeroed frame at {@code virtual} for ring 3 and returns the frame's physical address. */
    public long allocate(long virtual, long flags) {
        checkUser(virtual, PAGE);
        long frame = PhysicalMemory.allocateZeroed();
        table.map(virtual, frame, flags | PageTable.USER);
        return frame;
    }

    /**
     * Reserves [start, start + length) for {@link #pageIn} to fill with {@code flags}. Both must be
     * page-aligned, and the range can't overlap another reservation.
     */
    public synchronized void reserve(long start, long length, long flags) {
        checkUser(start, length);
        if ((start | length) % PAGE != 0) {
            throw new IllegalArgumentException("unaligned reservation");
        }
        for (int i = 0; i < reserved; i++) {
            if (start < ranges[3 * i + 1] && ranges[3 * i] < start + length) {
                throw new IllegalArgumentException("overlaps another reservation");
            }
        }
        long[] r = 3 * reserved == ranges.length ? Arrays.copyOf(ranges, 2 * ranges.length) : ranges;
        long interrupts = SpinLock.lockInterruptsOff(pageInLock);
        r[3 * reserved] = start;
        r[3 * reserved + 1] = start + length;
        r[3 * reserved + 2] = flags;
        ranges = r;
        reserved++;
        SpinLock.unlockInterruptsOff(pageInLock, interrupts);
    }

    /**
     * Maps a zeroed frame for a page fault at {@code address} if it's in a reservation and not
     * mapped yet. False otherwise, or when memory's run out. From the page-fault handler with
     * interrupts off, so it allocates nothing on the heap.
     */
    public boolean pageIn(long address) {
        long page = address & -PAGE;
        long interrupts = SpinLock.lockInterruptsOff(pageInLock);
        try {
            for (int i = 0; i < reserved; i++) {
                if (page >= ranges[3 * i] && page < ranges[3 * i + 1]) {
                    // Another thread's fault may have mapped it since this one was raised.
                    if (table.flags(page) != 0) {
                        return true;
                    }
                    long frame = PhysicalMemory.tryAllocateZeroed();
                    if (frame < 0) {
                        return false;
                    }
                    table.map(page, frame, ranges[3 * i + 2] | PageTable.USER);
                    return true;
                }
            }
            return false;
        } finally {
            SpinLock.unlockInterruptsOff(pageInLock, interrupts);
        }
    }

    /**
     * munmap: takes [start, start + length) out of the reservations and frees every page mapped
     * there, reserved or not, once no CPU can still reach it. Any of it may be unmapped already.
     * Both must be page-aligned. Interrupts must be on, for {@link Tlb#flush}.
     */
    public synchronized void unmap(long start, long length) {
        checkUser(start, length);
        if ((start | length) % PAGE != 0) {
            throw new IllegalArgumentException("unaligned unmapping");
        }
        long end = start + length;
        // What's left of each reservation: below start, above end, or both, which splits it.
        // Reservations don't overlap, so only one can split.
        long[] r = new long[ranges.length + 3];
        int n = 0;
        for (int i = 0; i < reserved; i++) {
            long from = ranges[3 * i];
            long to = ranges[3 * i + 1];
            if (from < start) {
                r[3 * n] = from;
                r[3 * n + 1] = Math.min(to, start);
                r[3 * n + 2] = ranges[3 * i + 2];
                n++;
            }
            if (to > end) {
                r[3 * n] = Math.max(from, end);
                r[3 * n + 1] = to;
                r[3 * n + 2] = ranges[3 * i + 2];
                n++;
            }
        }
        long interrupts = SpinLock.lockInterruptsOff(pageInLock);
        ranges = r;
        reserved = n;
        SpinLock.unlockInterruptsOff(pageInLock, interrupts);

        // No fault can map into the range now, and the monitor keeps mmap from handing it out again.
        long[] frames = new long[UNMAP_BATCH];
        long at = start;
        while (at < end) {
            int count = 0;
            interrupts = SpinLock.lockInterruptsOff(pageInLock);
            for (at = table.nextMapped(at, end); at < end && count < frames.length; at = table.nextMapped(at + PAGE, end)) {
                frames[count++] = table.remove(at);
            }
            SpinLock.unlockInterruptsOff(pageInLock, interrupts);
            Tlb.flush(table);
            for (int i = 0; i < count; i++) {
                PhysicalMemory.free(frames[i]);
            }
        }
    }

    /** Lets {@link #map} hand out [floor, top), top down. */
    public synchronized void setMapRange(long floor, long top) {
        mapFloor = floor;
        mapTop = top;
    }

    /** Reserves {@code length} bytes (rounded up to pages) somewhere free, or returns -1 if there's no room. */
    public synchronized long map(long length, long flags) {
        long bytes = (length + PAGE - 1) & -PAGE;
        if (length <= 0 || bytes <= 0) {
            return -1;
        }
        // The highest gap that fits, so what munmap freed gets used again.
        long top = mapTop;
        while (top - mapFloor >= bytes) {
            long start = top - bytes;
            long blocked = top;
            for (int i = 0; i < reserved; i++) {
                if (ranges[3 * i] < top && start < ranges[3 * i + 1]) {
                    blocked = Math.min(blocked, ranges[3 * i]);
                }
            }
            if (blocked == top) {
                reserve(start, bytes, flags);
                return start;
            }
            top = blocked;
        }
        return -1;
    }

    /**
     * A copy for fork: every mapped page copied, every reservation and the mmap range kept, so
     * the child pages in what the parent never touched. Frees what it took if memory runs out.
     */
    public synchronized AddressSpace copy() {
        AddressSpace child = new AddressSpace();
        try {
            table.copyLowerHalfInto(child.table);
        } catch (OutOfMemoryError e) {
            child.free();
            throw e;
        }
        child.ranges = Arrays.copyOf(ranges, ranges.length);
        child.reserved = reserved;
        child.mapFloor = mapFloor;
        child.mapTop = mapTop;
        return child;
    }

    /** Frees every page and table. No thread may still be running in it. */
    public void free() {
        table.freeLowerHalf();
    }

    private static void checkUser(long start, long length) {
        if (start < 0 || length < 0 || length > END - start) {
            throw new IllegalArgumentException("0x" + Long.toHexString(start) + " isn't in the lower half");
        }
    }
}
