package duke.kernel;

import duke.rt.Backtrace;
import duke.rt.Magic;

/** The kernel's panic, for a user program: the message on stderr, then exit_group(1). */
public final class Panic {

    private static final int EXIT_GROUP = 231;

    private Panic() {
    }

    public static void panic(String message) {
        begin(message);
        haltWithTrace(Magic.framePointer());
    }

    public static void panic(String... parts) {
        begin(parts);
        haltWithTrace(Magic.framePointer());
    }

    public static void panic(String message, int a, int b) {
        begin(message, " (", Integer.toString(a), ", ", Integer.toString(b), ")");
        haltWithTrace(Magic.framePointer());
    }

    public static void begin(String... parts) {
        StringBuilder line = new StringBuilder("PANIC: ");
        for (String part : parts) {
            line.append(part);
        }
        Console.writeTo(Console.STDERR, line.toString(), true);
    }

    private static void haltWithTrace(long rbp) {
        Backtrace.print(Magic.peekLong(rbp + 8) - 1, Magic.peekLong(rbp));
        haltForever();
    }

    public static void haltForever() {
        while (true) {
            Magic.syscall(EXIT_GROUP, 1, 0, 0, 0, 0, 0);
        }
    }
}
