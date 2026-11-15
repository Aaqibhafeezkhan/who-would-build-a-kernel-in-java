package duke.rt;

import duke.kernel.Panic;
import duke.kernel.Scheduler;

/**
 * The Java heap. Early boot bump-allocates from a small arena the compiler reserves in .bss; once
 * paging is up, {@link #growInto} moves allocation to a large virtual region that its Backing
 * commits as zeroed frames on demand. When the committed heap reaches the collection threshold,
 * {@link Collector} runs a mark-sweep; afterwards allocation bumps first and then takes holes
 * from the free list the sweep built.
 *
 * <p>Free memory inside the heap is always walkable, so the sweeper can step from object to object:
 * a hole is a word holding {@link #FILLER_8} (8 bytes), {@link #FILLER_16} (16 bytes), or
 * {@link #HOLE} followed by its size and the next hole on the free list. TIB pointers are upper-half
 * addresses, so they never collide with those markers.
 */
public final class Heap {

    /** Makes [address, address + bytes) usable and zeroed, or returns false when memory has run out. */
    public interface Backing {
        boolean commit(long address, long bytes);
    }

    static final long FILLER_8 = 1;
    static final long FILLER_16 = 2;
    static final long HOLE = 3;

    private static final int ARRAY_LENGTH_OFFSET = 8;
    private static final int ARRAY_DATA_OFFSET = 16;
    private static final long COMMIT_CHUNK = 2 * 1024 * 1024;
    private static final long MIN_THRESHOLD = 32L * 1024 * 1024;

    /** Must match Compiler.HEAP_ARENA_SIZE. One mark bit per 8 bytes: 512 heap bytes per word. */
    static final int ARENA_BYTES = 4 * 1024 * 1024;
    static final long[] ARENA_MARKS = new long[ARENA_BYTES / 512];

    // Set lazily rather than in <clinit>: other initializers may allocate before ours runs.
    static long next;
    static long end;
    static long arenaStart;
    /** Where the arena's objects end, once allocation has moved to the growable region. */
    static long arenaEnd;
    static long regionStart;
    static long regionLimit;
    /** Mark bitmap for the growable region, committed alongside it. */
    static long regionMarks;
    static Backing backing;
    /** First hole on the free list, or 0. Kept in address order by the sweep. */
    static long freeList;
    private static long threshold = MIN_THRESHOLD;
    private static boolean collecting;
    /**
     * The lock word, then the holding CPU plus one. Allocating and collecting both hold it, and a
     * CPU waiting for it spins at a safepoint, so it can stop for the collection it's waiting on.
     */
    private static final int[] LOCK = new int[2];
    private static int stressInterval;
    private static int allocationsSinceCollection;

    private Heap() {
    }

    /** Target of every {@code new}; the compiler passes the class's TIB and instance size. */
    static Object allocateObject(long tib, int size) {
        long address = allocate(size);
        Magic.pokeLong(address, tib);
        return Magic.toObject(address);
    }

    /** A new array of the same runtime type as {@code array}, for Arrays.copyOf. */
    public static Object allocateLike(Object array, int length) {
        long tib = Tib.of(array);
        return allocateArray(tib, length, Tib.size(tib));
    }

    /** Target of {@code newarray} and {@code anewarray}. */
    static Object allocateArray(long tib, int length, int elementSize) {
        if (length < 0) {
            throw new NegativeArraySizeException(Integer.toString(length));
        }
        long address = allocate(ARRAY_DATA_OFFSET + (long) length * elementSize);
        Magic.pokeLong(address, tib);
        Magic.pokeInt(address + ARRAY_LENGTH_OFFSET, length);
        return Magic.toObject(address);
    }

    /**
     * Target of {@code multianewarray}. {@code descriptor} points at a compiler-emitted table:
     * int dimensions, int padding, then per level a TIB pointer and an element size (8 bytes each).
     * {@code counts} points at the dimension counts still on the caller's operand stack, first
     * dimension at the highest address.
     */
    static Object allocateMultiArray(long descriptor, long counts) {
        int dimensions = Magic.peekInt(descriptor);
        for (int i = 0; i < dimensions; i++) {
            if (count(counts, dimensions, i) < 0) {
                throw new NegativeArraySizeException(Integer.toString(count(counts, dimensions, i)));
            }
        }
        return allocateLevel(descriptor, counts, dimensions, 0);
    }

    private static Object allocateLevel(long descriptor, long counts, int dimensions, int level) {
        long entry = descriptor + 8 + 16L * level;
        int length = count(counts, dimensions, level);
        Object array = allocateArray(Magic.peekLong(entry), length, (int) Magic.peekLong(entry + 8));
        if (level + 1 < dimensions) {
            // The collector doesn't move objects, so the address stays good across allocations.
            long elements = Magic.addressOf(array) + ARRAY_DATA_OFFSET;
            for (int i = 0; i < length; i++) {
                Object child = allocateLevel(descriptor, counts, dimensions, level + 1);
                Magic.pokeLong(elements + 8L * i, Magic.addressOf(child));
            }
        }
        return array;
    }

    private static int count(long counts, int dimensions, int level) {
        return Magic.peekInt(counts + 8L * (dimensions - 1 - level));
    }

    /**
     * Switches allocation to [base, limit), committed through {@code backing} as it fills, with
     * its mark bitmap at {@code marks} (one bit per 8 bytes) and the collector's mark stack at
     * {@code markStack}, both committed through the same backing.
     * Objects already in the arena stay valid and are collected along with the rest.
     */
    public static void growInto(long base, long limit, long marks, long markStack, Backing backing) {
        ensureArena();
        Collector.markStack = markStack;
        arenaEnd = next;
        Heap.backing = backing;
        regionStart = base;
        regionLimit = limit;
        regionMarks = marks;
        next = base;
        end = base;
    }

    private static void ensureArena() {
        if (arenaStart == 0) {
            arenaStart = Magic.heapArenaStart();
            next = arenaStart;
            end = Magic.heapArenaEnd();
            if (end - arenaStart != ARENA_BYTES) {
                Panic.panic("Heap.ARENA_BYTES disagrees with the compiler's arena size");
            }
        }
    }

    /**
     * This CPU is inside an allocation or collection, which mustn't be preempted: neither is
     * reentrant, and the thread mustn't move to another CPU while it holds the lock.
     */
    public static boolean allocating() {
        return LOCK[1] == Magic.cpuIndex() + 1;
    }

    // The holder's stack limit, for unlock to put back: a pending preemption request comes back with it.
    private static long holderStackLimit;

    /** Takes the lock unless this CPU holds it already; true if it did. */
    private static boolean lock() {
        if (allocating()) {
            return false;
        }
        if (Scheduler.lockedHere()) {
            // A CPU spinning on the scheduler lock can't stop, so the collection would wait forever.
            Panic.panic("allocating under the scheduler lock");
        }
        SpinLock.lock(LOCK);
        // No safepoint since the lock was taken, so this is still the CPU that took it.
        LOCK[1] = Magic.cpuIndex() + 1;
        // Stack checks off until unlock. A StackOverflowError thrown from a prologue in here would
        // unwind past the unlock and leave the lock held, and with the checks off no preemption
        // request can land either. Allocating and collecting fit in the 16 KiB below the limit.
        holderStackLimit = Magic.stackLimit();
        Magic.setStackLimit(0);
        return true;
    }

    private static void unlock() {
        long limit = holderStackLimit;
        LOCK[1] = 0;
        SpinLock.unlock(LOCK);
        Magic.setStackLimit(limit);
    }

    private static long allocate(long size) {
        boolean outer = lock();
        long address = allocateUnpreempted(size);
        if (outer) {
            unlock();
        }
        return address;
    }

    private static long allocateUnpreempted(long size) {
        ensureArena();
        size = (size + 7) & ~7L;
        if (backing != null && stressInterval > 0 && ++allocationsSinceCollection >= stressInterval) {
            collect();
        }
        long address = next;
        long bumped = address + size;
        // Both regions are in the upper half, negative as a signed long. The largest possible
        // request (2^31 longs) can't cross zero from there, so signed comparison is safe.
        if (bumped <= end) {
            next = bumped;
            return address;
        }
        if (backing == null) {
            // Fatal rather than thrown: there's no memory left to allocate the error object in.
            Panic.panic("OutOfMemoryError: Java heap space");
        }
        address = takeHole(size);
        if (address != 0) {
            return address;
        }
        if (end - regionStart + COMMIT_CHUNK > threshold && !collecting) {
            collect();
            address = takeAfterCollection(size);
            if (address != 0) {
                return address;
            }
        }
        if (!commitUpTo(next + size)) {
            if (!collecting) {
                collect();
                address = takeAfterCollection(size);
                if (address != 0) {
                    return address;
                }
            }
            if (!commitUpTo(next + size)) {
                Panic.panic("OutOfMemoryError: Java heap space");
            }
        }
        address = next;
        next += size;
        return address;
    }

    /** A collection can free a hole or hand the dead tail back to the bump pointer; try both. */
    private static long takeAfterCollection(long size) {
        if (next + size <= end) {
            long address = next;
            next += size;
            return address;
        }
        return takeHole(size);
    }

    /** First fit from the free list; the block comes back zeroed. 0 if nothing fits. */
    private static long takeHole(long size) {
        long previous = 0;
        for (long hole = freeList; hole != 0; hole = Magic.peekLong(hole + 16)) {
            long holeSize = Magic.peekLong(hole + 8);
            long rest = holeSize - size;
            if (rest == 0 || rest >= 24 || rest == 8 || rest == 16) {
                long after = Magic.peekLong(hole + 16);
                long replacement = after;
                if (rest >= 24) {
                    long remainder = hole + size;
                    Magic.pokeLong(remainder, HOLE);
                    Magic.pokeLong(remainder + 8, rest);
                    Magic.pokeLong(remainder + 16, after);
                    replacement = remainder;
                } else if (rest > 0) {
                    Magic.pokeLong(hole + size, rest == 8 ? FILLER_8 : FILLER_16);
                }
                if (previous == 0) {
                    freeList = replacement;
                } else {
                    Magic.pokeLong(previous + 16, replacement);
                }
                Magic.zeroMemoryWords(hole, size >>> 3);
                return hole;
            }
            previous = hole;
        }
        return 0;
    }

    /** Commits the growable region and its mark bitmap up to at least {@code needed}. */
    private static boolean commitUpTo(long needed) {
        if (needed <= end) {
            return true;
        }
        if (needed > regionLimit) {
            return false;
        }
        long newEnd = Math.min(regionLimit, (needed + COMMIT_CHUNK - 1) & -COMMIT_CHUNK);
        long marksFrom = regionMarks + (end - regionStart) / 64;
        long marksTo = regionMarks + (newEnd - regionStart) / 64;
        if (!backing.commit(end, newEnd - end) || !backing.commit(marksFrom, marksTo - marksFrom)) {
            return false;
        }
        end = newEnd;
        return true;
    }

    /** Runs a full collection. Called by allocation, System.gc(), and the stress mode. */
    public static void collect() {
        boolean outer = lock();
        collectLocked();
        if (outer) {
            unlock();
        }
    }

    private static void collectLocked() {
        if (backing == null || collecting) {
            return;
        }
        collecting = true;
        allocationsSinceCollection = 0;
        long live = Collector.collect();
        threshold = Math.max(MIN_THRESHOLD, 2 * live);
        collecting = false;
    }

    /** Collects every {@code interval} allocations (0 to stop), for shaking out stack-map bugs in tests. */
    public static void stress(int interval) {
        stressInterval = interval;
        allocationsSinceCollection = 0;
    }

    /** Address-derived, which only works because the collector never moves objects. */
    public static int identityHash(Object object) {
        long address = Magic.addressOf(object);
        return (int) (address >>> 3) ^ (int) (address >>> 35);
    }

    public static long collections() {
        return Collector.collections;
    }

    /** Live bytes found by the last collection. */
    public static long lastLive() {
        return Collector.lastLive;
    }

    /** Bytes committed to the growable heap so far (the arena isn't counted). */
    public static long committed() {
        return backing == null ? 0 : end - regionStart;
    }

    /** Bytes between the region start and the bump pointer, holes included. */
    public static long used() {
        if (backing != null) {
            return next - regionStart;
        }
        return arenaStart == 0 ? 0 : next - arenaStart;
    }

    public static boolean inGrowableRegion(Object object) {
        long address = Magic.addressOf(object);
        return backing != null && address >= regionStart && address < regionLimit;
    }
}
