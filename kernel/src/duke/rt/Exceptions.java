package duke.rt;

import duke.kernel.Monitors;
import duke.kernel.Panic;

/**
 * athrow compiles to a call here. Walks the rbp chain looking each frame up in the compiler's
 * exception tables (layout on Compiler.emitMethodTable) and resumes in the first matching handler.
 */
final class Exceptions {

    private Exceptions() {
    }

    static void raise(Throwable exception) {
        long tib = Tib.of(exception);
        long self = Magic.framePointer();
        long pc = Magic.peekLong(self + 8) - 1;
        long rbp = Magic.peekLong(self);
        while (rbp != 0) {
            long entry = Backtrace.find(pc);
            if (entry == 0 || (Magic.peekInt(entry + Backtrace.ENTRY_FLAGS) & Backtrace.INTERRUPT_ENTRY) != 0) {
                break;
            }
            long table = Magic.peekLong(entry + Backtrace.ENTRY_EXCEPTIONS);
            if (table != 0) {
                long start = Magic.peekLong(entry);
                int offset = (int) (pc - start);
                int rows = Magic.peekInt(table);
                for (int i = 0; i < rows; i++) {
                    long row = table + 8 + 24L * i;
                    if (offset >= Magic.peekInt(row) && offset < Magic.peekInt(row + 4)) {
                        long catchTib = Magic.peekLong(row + 16);
                        if (catchTib == 0 || Tib.isAssignable(tib, catchTib)) {
                            long frameBytes = Magic.peekInt(table + 4);
                            Magic.resetStackLimit();
                            Magic.resumeAt(start + Magic.peekInt(row + 8), rbp - frameBytes, rbp, exception);
                        }
                    }
                }
            }
            int flags = Magic.peekInt(entry + Backtrace.ENTRY_FLAGS);
            if ((flags & Backtrace.SYNCHRONIZED) != 0
                    && pc - Magic.peekLong(entry) >= Magic.peekInt(entry + Backtrace.ENTRY_MONITOR_FROM)) {
                // Leaving a synchronized method, which keeps its lock object at [rbp - 8].
                Monitors.exitUnwinding(Magic.toObject(Magic.peekLong(rbp - 8)));
            }
            pc = Magic.peekLong(rbp + 8) - 1;
            rbp = Magic.peekLong(rbp);
        }
        Panic.uncaught(exception);
    }
}
