package duke.kernel;

import duke.rt.Heap;
import duke.rt.Magic;

/** What a user program's _start runs before any class initializer: moves the heap into memory from mmap. */
public final class UserRuntime {

    private static final int MMAP = 9;
    private static final long PROT_READ_WRITE = 1 | 2;
    private static final long MAP_PRIVATE_ANONYMOUS_NORESERVE = 0x02 | 0x20 | 0x4000;
    /** Address space, not memory: Linux and Duke both page it in as it's first touched. */
    private static final long HEAP_BYTES = 1L << 30;

    private UserRuntime() {
    }

    public static void init() {
        long heap = map(HEAP_BYTES);
        // One mark bit per 8 bytes, and a mark stack deep enough for every object to be on it at once.
        long marks = map(HEAP_BYTES / 64);
        long markStack = map(HEAP_BYTES / 2);
        // The reservations are already zero-filled on demand, so committing has nothing to do.
        Heap.growInto(heap, heap + HEAP_BYTES, marks, markStack, (address, bytes) -> true);
    }

    private static long map(long bytes) {
        long address = Magic.syscall(MMAP, 0, bytes, PROT_READ_WRITE, MAP_PRIVATE_ANONYMOUS_NORESERVE, -1, 0);
        if (address < 0) {
            Panic.panic("mmap for the Java heap failed: errno ", Long.toString(-address));
        }
        return address;
    }
}
