package duke.kernel;

import duke.rt.Magic;
import duke.rt.SpinLock;

/**
 * Kernel text output: the serial port always, the framebuffer once it's up. One CPU at a time, a
 * whole string at a time. The lock is reentrant on its CPU, so a panic raised while printing can
 * still print.
 */
public final class Console {

    /** The lock word, then the holding CPU plus one. */
    private static final int[] LOCK = new int[2];
    private static final long NESTED = -1;

    private Console() {
    }

    public static void write(int c) {
        long flags = lock();
        put(c);
        unlock(flags);
    }

    public static void print(String s) {
        // Before locking: a NullPointerException in here would leave the lock held.
        if (s == null) {
            s = "null";
        }
        long flags = lock();
        for (int i = 0; i < s.length(); i++) {
            put(s.charAt(i));
        }
        unlock(flags);
    }

    /** System.err: the kernel has one console, so the same as print. */
    public static void printError(String s) {
        print(s);
    }

    public static void println(String s) {
        long flags = lock();
        print(s);
        put('\n');
        unlock(flags);
    }

    private static void put(int c) {
        if (c == '\n') {
            Serial.write('\r');
        }
        Serial.write(c);
        FramebufferConsole.write(c);
    }

    private static long lock() {
        int self = Magic.cpuIndex() + 1;
        if (LOCK[1] == self) {
            return NESTED;
        }
        long flags = SpinLock.lockInterruptsOff(LOCK);
        LOCK[1] = self;
        return flags;
    }

    private static void unlock(long flags) {
        if (flags != NESTED) {
            LOCK[1] = 0;
            SpinLock.unlockInterruptsOff(LOCK, flags);
        }
    }

    public static void print(long value) {
        // Work in negatives: Long.MIN_VALUE has no positive counterpart.
        if (value < 0) {
            write('-');
        } else {
            value = -value;
        }
        long divisor = 1;
        while (value / divisor <= -10) {
            divisor *= 10;
        }
        while (divisor > 0) {
            write('0' - (int) (value / divisor));
            value %= divisor;
            divisor /= 10;
        }
    }

    public static void printHex(long value) {
        print(hex(value));
    }

    /** Formats the 64-bit value with a 0x prefix and sixteen lowercase hexadecimal digits. */
    public static String hex(long value) {
        StringBuilder result = new StringBuilder(18);
        result.append("0x");
        for (int shift = 60; shift >= 0; shift -= 4) {
            int digit = (int) ((value >>> shift) & 0xF);
            result.append((char) (digit < 10 ? '0' + digit : 'a' + digit - 10));
        }
        return result.toString();
    }
}
