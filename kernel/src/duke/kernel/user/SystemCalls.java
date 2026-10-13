package duke.kernel.user;

import duke.kernel.Console;
import duke.kernel.Scheduler;
import duke.kernel.mm.AddressSpace;
import duke.kernel.mm.KernelAddressSpace;
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
    public static final int MMAP = 9;
    public static final int GETPID = 39;
    public static final int WAIT4 = 61;
    public static final int GETPPID = 110;
    public static final int EXIT = 60;
    public static final int EXIT_GROUP = 231;

    public static final int ESRCH = 3;
    public static final int EINTR = 4;
    public static final int EBADF = 9;
    public static final int ECHILD = 10;
    public static final int ENOMEM = 12;
    public static final int EFAULT = 14;
    public static final int EINVAL = 22;
    public static final int ENOSYS = 38;

    public static final int PROT_READ = 1;
    public static final int PROT_WRITE = 2;
    public static final int PROT_EXEC = 4;
    public static final int MAP_PRIVATE = 2;
    public static final int MAP_FIXED = 0x10;
    public static final int MAP_ANONYMOUS = 0x20;
    public static final int MAP_NORESERVE = 0x4000;
    public static final int WNOHANG = 1;
    /** sizeof(struct rusage), which wait4 zeroes: nothing's accounted yet. */
    private static final int RUSAGE_BYTES = 144;

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
        } else if (number == MMAP) {
            result = mmap(Frame.register(frame, Frame.RSI), Frame.register(frame, Frame.RDX),
                    Frame.register(frame, Frame.R10), Frame.register(frame, Frame.R8), Frame.register(frame, Frame.R9));
        } else if (number == GETPID || number == GETPPID) {
            Process self = Process.current();
            result = self == null ? -ESRCH : number == GETPID ? self.pid : self.parentPid();
        } else if (number == WAIT4) {
            result = wait4((int) a0, Frame.register(frame, Frame.RSI), Frame.register(frame, Frame.RDX),
                    Frame.register(frame, Frame.R10));
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

    /** Children only, by pid or -1 for any: there are no process groups to wait on. */
    private static long wait4(int pid, long status, long options, long usage) {
        Process self = Process.current();
        if (self == null) {
            return -ECHILD;
        }
        if ((options & ~(long) WNOHANG) != 0 || pid == 0 || pid < -1) {
            return -EINVAL;
        }
        if (status != 0 && !userWritable(status, 4) || usage != 0 && !userWritable(usage, RUSAGE_BYTES)) {
            return -EFAULT;
        }
        Process.Reaped reaped;
        try {
            reaped = Process.reap(self, pid, (options & WNOHANG) == 0);
        } catch (InterruptedException e) {
            return -EINTR;
        }
        if (reaped == Process.NO_CHILD) {
            return -ECHILD;
        }
        if (reaped.pid > 0) {
            if (status != 0) {
                Magic.pokeInt(status, reaped.status);
            }
            if (usage != 0) {
                Magic.zeroMemoryWords(usage, RUSAGE_BYTES / 8);
            }
        }
        return reaped.pid;
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

    /**
     * Private anonymous memory only, placed wherever there's room (the address is a hint Linux
     * may ignore too), and zeroed a page at a time as it's touched. Every mapping is reserved
     * lazily, so MAP_NORESERVE changes nothing.
     */
    private static long mmap(long length, long prot, long flags, long fd, long offset) {
        long supported = MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE;
        if ((flags & ~supported) != 0 || (flags & (MAP_PRIVATE | MAP_ANONYMOUS)) != (MAP_PRIVATE | MAP_ANONYMOUS)
                || fd != -1 || offset != 0 || length <= 0) {
            return -EINVAL;
        }
        if ((prot & ~(long) (PROT_READ | PROT_WRITE | PROT_EXEC)) != 0 || prot == 0) {
            return -EINVAL;
        }
        long pageFlags = (prot & PROT_WRITE) != 0 ? PageTable.WRITABLE : 0;
        if ((prot & PROT_EXEC) == 0 && KernelAddressSpace.noExecuteSupported()) {
            pageFlags |= PageTable.NO_EXECUTE;
        }
        long address = Scheduler.addressSpace().map(length, pageFlags);
        return address < 0 ? -ENOMEM : address;
    }

    static boolean userReadable(long address, long length) {
        return userAccessible(address, length, PageTable.USER);
    }

    static boolean userWritable(long address, long length) {
        return userAccessible(address, length, PageTable.USER | PageTable.WRITABLE);
    }

    /** Whether every page of [address, address + length) is mapped in this thread's process with {@code flags}. */
    private static boolean userAccessible(long address, long length, long flags) {
        if (address < 0 || length < 0 || length > AddressSpace.END - address) {
            return false;
        }
        AddressSpace space = Scheduler.addressSpace();
        PageTable table = space.table();
        long end = address + length;
        for (long page = address & -PhysicalMemory.PAGE_SIZE; page < end; page += PhysicalMemory.PAGE_SIZE) {
            // Reserved and never touched counts: on Linux the copy's own fault would page it in.
            if ((table.flags(page) & PageTable.USER) == 0) {
                space.pageIn(page);
            }
            if ((table.flags(page) & flags) != flags) {
                return false;
            }
        }
        return true;
    }
}
