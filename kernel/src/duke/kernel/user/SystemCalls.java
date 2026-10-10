package duke.kernel.user;

import duke.kernel.Console;
import duke.kernel.Scheduler;
import duke.kernel.mm.PageTable;
import duke.kernel.mm.PhysicalMemory;
import duke.kernel.x86.Frame;
import duke.rt.Magic;

/**
 * System calls, numbered and passed the way Linux's are on x86-64: the number in rax, arguments
 * in rdi, rsi, rdx, r10, r8 and r9, and the result, or a negated errno, back in rax.
 */
public final class SystemCalls {

    public static final int WRITE = 1;
    public static final int EXIT = 60;
    public static final int EXIT_GROUP = 231;

    public static final int EBADF = 9;
    public static final int EFAULT = 14;
    public static final int ENOSYS = 38;

    /** The saved registers, then the user's rsp, then the frame record enterUser left (Compiler.emitSyscallEntry). */
    private static final int FRAME_BYTES = 128;

    private SystemCalls() {
    }

    /** Called by the compiler's syscall entry stub on the thread's kernel stack, interrupts off. */
    static void dispatch(long frame) {
        Magic.enableInterrupts();
        long number = Frame.register(frame, Frame.RAX);
        long a0 = Frame.register(frame, Frame.RDI);
        long result;
        if (number == WRITE) {
            result = write(a0, Frame.register(frame, Frame.RSI), Frame.register(frame, Frame.RDX));
        } else if (number == EXIT || number == EXIT_GROUP) {
            Magic.disableInterrupts();
            Magic.leaveUser(frame + FRAME_BYTES, (int) a0);
            return;
        } else {
            result = -ENOSYS;
        }
        Frame.setRegister(frame, Frame.RAX, result);
        Magic.disableInterrupts();
        Magic.setKernelStack(frame + FRAME_BYTES);
    }

    private static long write(long fd, long buffer, long count) {
        if (fd != 1 && fd != 2) {
            return -EBADF;
        }
        if (!userReadable(buffer, count)) {
            return -EFAULT;
        }
        for (long i = 0; i < count; i++) {
            Console.write(Magic.peekByte(buffer + i) & 0xFF);
        }
        return count;
    }

    /** Whether every page of [address, address + length) is mapped for ring 3 in this thread's process. */
    static boolean userReadable(long address, long length) {
        if (address < 0 || length < 0 || length > UserMode.END - address) {
            return false;
        }
        PageTable table = Scheduler.pageTable();
        long end = address + length;
        for (long page = address & -PhysicalMemory.PAGE_SIZE; page < end; page += PhysicalMemory.PAGE_SIZE) {
            if ((table.flags(page) & PageTable.USER) == 0) {
                return false;
            }
        }
        return true;
    }
}
