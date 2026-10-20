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

    /** An exception that left main, reported as HotSpot does: on stderr, then exit status 1. */
    public static void uncaught(Throwable exception) {
        StringBuilder report = new StringBuilder("Exception in thread \"main\" ");
        report.append(exception.toString());
        appendFrames(report, exception);
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            report.append("\nCaused by: ").append(cause.toString());
            appendFrames(report, cause);
        }
        Console.writeTo(Console.STDERR, report.toString(), true);
        haltForever();
    }

    private static void appendFrames(StringBuilder report, Throwable exception) {
        for (StackTraceElement frame : exception.getStackTrace()) {
            // _start and anything else without a class isn't Java, which is all HotSpot shows.
            if (!frame.getClassName().isEmpty()) {
                report.append("\n\tat ").append(frame.toString());
            }
        }
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
