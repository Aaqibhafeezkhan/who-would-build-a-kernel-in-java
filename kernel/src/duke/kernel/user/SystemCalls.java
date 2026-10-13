package duke.kernel.user;

import duke.kernel.Scheduler;
import duke.kernel.fs.Fat;
import duke.kernel.mm.AddressSpace;
import duke.kernel.mm.KernelAddressSpace;
import duke.kernel.mm.PageTable;
import duke.kernel.mm.PhysicalMemory;
import duke.kernel.x86.Frame;
import duke.kernel.x86.UserState;
import duke.rt.Magic;

/**
 * System calls, numbered and passed the way Linux's are on x86-64: the number in rax, arguments
 * in rdi, rsi, rdx, r10, r8 and r9, and the result, or a negated errno, back in rax.
 */
public final class SystemCalls {

    public static final int READ = 0;
    public static final int WRITE = 1;
    public static final int OPEN = 2;
    public static final int CLOSE = 3;
    public static final int FSTAT = 5;
    public static final int LSEEK = 8;
    public static final int MMAP = 9;
    public static final int GETPID = 39;
    public static final int WAIT4 = 61;
    public static final int GETPPID = 110;
    public static final int ARCH_PRCTL = 158;
    public static final int EXIT = 60;
    public static final int EXIT_GROUP = 231;
    public static final int OPENAT = 257;

    public static final int ESRCH = 3;
    public static final int EINTR = 4;
    public static final int EPERM = 1;
    public static final int ENOENT = 2;
    public static final int EIO = 5;
    public static final int EBADF = 9;
    public static final int ECHILD = 10;
    public static final int ENOMEM = 12;
    public static final int EFAULT = 14;
    public static final int ENOTDIR = 20;
    public static final int EISDIR = 21;
    public static final int EINVAL = 22;
    public static final int EMFILE = 24;
    public static final int ESPIPE = 29;
    public static final int EROFS = 30;
    public static final int ENAMETOOLONG = 36;
    public static final int ENOSYS = 38;

    public static final int PROT_READ = 1;
    public static final int PROT_WRITE = 2;
    public static final int PROT_EXEC = 4;
    public static final int MAP_PRIVATE = 2;
    public static final int MAP_FIXED = 0x10;
    public static final int MAP_ANONYMOUS = 0x20;
    public static final int MAP_NORESERVE = 0x4000;
    public static final int WNOHANG = 1;
    public static final int O_ACCMODE = 3;
    public static final int O_CREAT = 0x40;
    public static final int O_TRUNC = 0x200;
    public static final int O_DIRECTORY = 0x10000;
    public static final int AT_FDCWD = -100;
    /** sizeof(struct stat) on x86-64. */
    public static final int STAT_BYTES = 144;
    private static final int PATH_MAX = 4096;
    public static final int ARCH_SET_GS = 0x1001;
    public static final int ARCH_SET_FS = 0x1002;
    public static final int ARCH_GET_FS = 0x1003;
    public static final int ARCH_GET_GS = 0x1004;
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
        long a1 = Frame.register(frame, Frame.RSI);
        long a2 = Frame.register(frame, Frame.RDX);
        if (number == READ) {
            result = read(a0, a1, a2);
        } else if (number == WRITE) {
            result = write(a0, a1, a2);
        } else if (number == OPEN) {
            result = open(AT_FDCWD, a0, a1);
        } else if (number == OPENAT) {
            result = open((int) a0, a1, a2);
        } else if (number == CLOSE) {
            result = files().close(a0) ? 0 : -EBADF;
        } else if (number == FSTAT) {
            result = fstat(a0, a1);
        } else if (number == LSEEK) {
            OpenFile file = files().get(a0);
            result = file == null ? -EBADF : file.seek(a1, (int) a2);
        } else if (number == MMAP) {
            result = mmap(Frame.register(frame, Frame.RSI), Frame.register(frame, Frame.RDX),
                    Frame.register(frame, Frame.R10), Frame.register(frame, Frame.R8), Frame.register(frame, Frame.R9));
        } else if (number == GETPID || number == GETPPID) {
            Process self = Process.current();
            result = self == null ? -ESRCH : number == GETPID ? self.pid : self.parentPid();
        } else if (number == ARCH_PRCTL) {
            result = archPrctl(a0, Frame.register(frame, Frame.RSI));
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

    /**
     * The bases are in the CPU's MSRs while this thread runs, and the scheduler saves them from
     * there when it switches away, so reading and writing the MSRs is reading and writing the thread's.
     */
    private static long archPrctl(long code, long address) {
        if (code == ARCH_SET_FS || code == ARCH_SET_GS) {
            if (address < 0 || address >= AddressSpace.END) {
                return -EPERM;
            }
            Magic.writeMsr(code == ARCH_SET_FS ? UserState.IA32_FS_BASE : UserState.IA32_KERNEL_GS_BASE, address);
            return 0;
        }
        if (code == ARCH_GET_FS || code == ARCH_GET_GS) {
            if (!userWritable(address, 8)) {
                return -EFAULT;
            }
            Magic.pokeLong(address, Magic.readMsr(code == ARCH_GET_FS ? UserState.IA32_FS_BASE : UserState.IA32_KERNEL_GS_BASE));
            return 0;
        }
        return -EINVAL;
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

    private static FileTable files() {
        Process self = Process.current();
        return self != null ? self.files : FileTable.NO_PROCESS;
    }

    private static long read(long fd, long buffer, long count) {
        OpenFile file = files().get(fd);
        if (file == null) {
            return -EBADF;
        }
        if (!userWritable(buffer, count)) {
            return -EFAULT;
        }
        return file.read(buffer, count);
    }

    private static long write(long fd, long buffer, long count) {
        OpenFile file = files().get(fd);
        if (file == null) {
            return -EBADF;
        }
        if (!userReadable(buffer, count)) {
            return -EFAULT;
        }
        return file.write(buffer, count);
    }

    /**
     * Read-only, so any flag that would write is refused. With no working directory yet, a relative
     * path is relative to the root.
     */
    private static long open(int directory, long pathAddress, long flags) {
        if (directory != AT_FDCWD) {
            return -EINVAL;
        }
        if ((flags & (O_ACCMODE | O_CREAT | O_TRUNC)) != 0) {
            return -EROFS;
        }
        String path = userString(pathAddress);
        if (path == null) {
            return -EFAULT;
        }
        if (path.length() >= PATH_MAX) {
            return -ENAMETOOLONG;
        }
        Fat fs = Fat.mounted();
        if (fs == null) {
            return -ENOENT;
        }
        OpenFile file;
        try {
            Fat.Entry entry = fs.lookup(path);
            if (entry.directory) {
                file = OpenFile.directory();
            } else if ((flags & O_DIRECTORY) != 0) {
                return -ENOTDIR;
            } else {
                file = OpenFile.file(fs.read(path));
            }
        } catch (IllegalArgumentException e) {
            return -ENOENT;
        } catch (IllegalStateException e) {
            return -EIO;
        }
        return files().add(file);
    }

    private static long fstat(long fd, long buffer) {
        OpenFile file = files().get(fd);
        if (file == null) {
            return -EBADF;
        }
        if (!userWritable(buffer, STAT_BYTES)) {
            return -EFAULT;
        }
        Magic.zeroMemoryWords(buffer, STAT_BYTES / 8);
        Magic.pokeLong(buffer + 16, 1);                          // st_nlink
        Magic.pokeInt(buffer + 24, file.mode());                // st_mode
        Magic.pokeLong(buffer + 48, file.size());               // st_size
        Magic.pokeLong(buffer + 56, 512);                       // st_blksize
        Magic.pokeLong(buffer + 64, (file.size() + 511) / 512); // st_blocks
        return 0;
    }

    /** The NUL-terminated string at {@code address}, at most PATH_MAX bytes, or null if it isn't all readable. */
    private static String userString(long address) {
        StringBuilder sb = new StringBuilder();
        for (long at = address; sb.length() < PATH_MAX; at++) {
            if ((at == address || (at & (PhysicalMemory.PAGE_SIZE - 1)) == 0) && !userReadable(at, 1)) {
                return null;
            }
            int c = Magic.peekByte(at) & 0xFF;
            if (c == 0) {
                return sb.toString();
            }
            sb.append((char) c);
        }
        return sb.toString();
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
