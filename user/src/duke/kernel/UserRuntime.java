package duke.kernel;

import duke.rt.Heap;
import duke.rt.Magic;

/**
 * What a user program's _start runs before any class initializer: moves the heap into memory from
 * mmap. Then, if main takes them, the program's arguments.
 */
public final class UserRuntime {

    private static final int MMAP = 9;
    private static final long PROT_READ_WRITE = 1 | 2;
    private static final long MAP_PRIVATE_ANONYMOUS_NORESERVE = 0x02 | 0x20 | 0x4000;
    /** Address space, not memory: Linux and Duke both page it in as it's first touched. */
    private static final long HEAP_BYTES = 1L << 30;

    /** Where Linux left argc, then argv, envp and auxv. */
    private static long initialStack;

    private UserRuntime() {
    }

    public static void init(long stack) {
        initialStack = stack;
        long heap = map(HEAP_BYTES);
        // One mark bit per 8 bytes, and a mark stack deep enough for every object to be on it at once.
        long marks = map(HEAP_BYTES / 64);
        long markStack = map(HEAP_BYTES / 2);
        // The reservations are already zero-filled on demand, so committing has nothing to do.
        Heap.growInto(heap, heap + HEAP_BYTES, marks, markStack, (address, bytes) -> true);
    }

    /** argv without argv[0], as HotSpot passes main. */
    public static String[] arguments() {
        int count = (int) Magic.peekLong(initialStack);
        String[] arguments = new String[Math.max(count - 1, 0)];
        for (int i = 0; i < arguments.length; i++) {
            arguments[i] = decode(Magic.peekLong(initialStack + 8L * (i + 2)));
        }
        return arguments;
    }

    /** A C string of UTF-8. Our strings are Latin-1, so anything past U+00FF becomes '?'. */
    private static String decode(long address) {
        StringBuilder decoded = new StringBuilder();
        int b;
        while ((b = Magic.peekByte(address++) & 0xFF) != 0) {
            int more = b < 0x80 ? 0 : b >= 0xF0 ? 3 : b >= 0xE0 ? 2 : b >= 0xC0 ? 1 : -1;
            int c = more < 0 ? '?' : more == 0 ? b : b & (0x3F >> more);
            for (int i = 0; i < more && (Magic.peekByte(address) & 0xC0) == 0x80; i++) {
                c = c << 6 | Magic.peekByte(address++) & 0x3F;
            }
            decoded.append(c <= 0xFF ? (char) c : '?');
        }
        return decoded.toString();
    }

    private static long map(long bytes) {
        long address = Magic.syscall(MMAP, 0, bytes, PROT_READ_WRITE, MAP_PRIVATE_ANONYMOUS_NORESERVE, -1, 0);
        if (address < 0) {
            Panic.panic("mmap for the Java heap failed: errno ", Long.toString(-address));
        }
        return address;
    }
}
