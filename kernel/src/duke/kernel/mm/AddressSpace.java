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
 * they come with interrupts off, and see reservations without one: {@link #reserve} only ever appends,
 * and publishes each before counting it.
 */
public final class AddressSpace {

    /** One past the highest user address: the lower half. */
    public static final long END = 0x0000_8000_0000_0000L;

    private static final long PAGE = PhysicalMemory.PAGE_SIZE;

    private final PageTable table = PageTable.sharingHigherHalf(KernelAddressSpace.table());
    // Reserved ranges as (start, end, flags), so pageIn can search them without allocating.
    private long[] ranges = new long[3 * 4];
    private volatile int reserved;
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
        r[3 * reserved] = start;
        r[3 * reserved + 1] = start + length;
        r[3 * reserved + 2] = flags;
        // The entry and the array before the count: a fault on another CPU that sees the count sees them.
        ranges = r;
        reserved = reserved + 1;
    }

    /**
     * Maps a zeroed frame for a page fault at {@code address} if it's in a reservation and not
     * mapped yet. False otherwise, or when memory's run out. From the page-fault handler with
     * interrupts off, so it allocates nothing on the heap.
     */
    public boolean pageIn(long address) {
        long page = address & -PAGE;
        int n = reserved;
        long[] r = ranges;
        for (int i = 0; i < n; i++) {
            if (page >= r[3 * i] && page < r[3 * i + 1]) {
                long flags = SpinLock.lockInterruptsOff(pageInLock);
                try {
                    // Another thread's fault may have mapped it since this one was raised.
                    if (table.flags(page) != 0) {
                        return true;
                    }
                    long frame = PhysicalMemory.tryAllocateZeroed();
                    if (frame < 0) {
                        return false;
                    }
                    table.map(page, frame, r[3 * i + 2] | PageTable.USER);
                    return true;
                } finally {
                    SpinLock.unlockInterruptsOff(pageInLock, flags);
                }
            }
        }
        return false;
    }

    /** Lets {@link #map} hand out [floor, top), top down. */
    public synchronized void setMapRange(long floor, long top) {
        mapFloor = floor;
        mapTop = top;
    }

    /** Reserves {@code length} bytes (rounded up to pages) somewhere free, or returns -1 if there's no room. */
    public synchronized long map(long length, long flags) {
        long bytes = (length + PAGE - 1) & -PAGE;
        if (length <= 0 || bytes <= 0 || bytes > mapTop - mapFloor) {
            return -1;
        }
        long start = mapTop - bytes;
        reserve(start, bytes, flags);
        mapTop = start;
        return start;
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
