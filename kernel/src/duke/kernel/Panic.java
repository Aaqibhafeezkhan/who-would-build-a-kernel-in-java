package duke.kernel;

import duke.rt.Backtrace;
import duke.rt.Magic;

public final class Panic {

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

    /** Prints the panic line but keeps running, so the caller can add detail before haltForever(). */
    public static void begin(String... parts) {
        Console.print("\nPANIC: ");
        for (String part : parts) {
            Console.print(part);
        }
        Console.println("");
    }

    /** An exception no frame caught. */
    public static void uncaught(Throwable exception) {
        begin("uncaught ", exception.toString());
        exception.printEnclosedTrace();
        haltForever();
    }

    /** {@code rbp} is the panicking method's own frame; the trace starts at whoever called it. */
    private static void haltWithTrace(long rbp) {
        Backtrace.print(Magic.peekLong(rbp + 8) - 1, Magic.peekLong(rbp));
        haltForever();
    }

    public static void haltForever() {
        Magic.disableInterrupts();
        while (true) {
            Magic.halt();
        }
    }
}
