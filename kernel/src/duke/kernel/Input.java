package duke.kernel;

import duke.kernel.user.Process;

/**
 * Typed characters from every input device, in arrival order. Interrupt handlers put, the shell
 * takes. One producer at a time (handlers run with interrupts off) and one consumer, so plain
 * head and tail indices are enough. Image arrays, so putting never allocates.
 */
public final class Input {

    public static final int KEY_LEFT   = 0x101;
    public static final int KEY_RIGHT  = 0x102;
    public static final int KEY_HOME   = 0x103;
    public static final int KEY_END    = 0x104;
    public static final int KEY_DELETE = 0x105;
    /** What {@link #take} returns instead of a character once the calling process has been killed. */
    public static final int KILLED = -1;
    public static final int CTRL_C = 3;

    private static final int[] BUFFER = new int[256];
    private static volatile int head;
    private static volatile int tail;
    // While a program has the foreground, Ctrl-C is the shell's signal to stop it, not the program's input.
    private static volatile boolean foreground;
    private static volatile boolean interrupted;

    private Input() {
    }

    /** Called from interrupt handlers. Drops the character when the buffer is full. */
    public static void put(int c) {
        if (c == CTRL_C && foreground) {
            interrupted = true;
            return;
        }
        int next = (tail + 1) & (BUFFER.length - 1);
        if (next != head) {
            BUFFER[tail] = c;
            tail = next;
        }
    }

    /** Waits for the next character, letting other threads run meanwhile. */
    public static int take() {
        while (head == tail) {
            if (Process.killedHere()) {
                return KILLED;
            }
            Scheduler.pause();
        }
        int c = BUFFER[head];
        head = (head + 1) & (BUFFER.length - 1);
        return c;
    }

    /** Whether Ctrl-C is an interrupt for {@link #takeInterrupt} rather than input. Clears any pending one. */
    public static void setForeground(boolean on) {
        interrupted = false;
        foreground = on;
    }

    /** True once for each Ctrl-C typed while a program had the foreground. */
    public static boolean takeInterrupt() {
        if (!interrupted) {
            return false;
        }
        interrupted = false;
        return true;
    }
}
