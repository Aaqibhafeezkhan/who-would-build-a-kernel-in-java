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
    public static final int FORK = 57;
    public static final int EXECVE = 59;
    public static final int WAIT4 = 61;
    public static final int UNAME = 63;
    public static final int GETCWD = 79;
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
    public static final int E2BIG = 7;
    public static final int ENOEXEC = 8;
    public static final int EBADF = 9;
    public static final int ECHILD = 10;
    public static final int ENOMEM = 12;
    public static final int EACCES = 13;
    public static final int EFAULT = 14;
    public static final int ENOTDIR = 20;
    public static final int EISDIR = 21;
    public static final int EINVAL = 22;
    public static final int EMFILE = 24;
    public static final int ESPIPE = 29;
    public static final int EROFS = 30;
    public static final int ENAMETOOLONG = 36;
    public static final int ERANGE = 34;
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
    /** More argv or envp entries than this get E2BIG; they'd never fit the stack's top page anyway. */
    private static final int MAX_ARGS = 1024;
    /** Where the syscall stub saved the user's rsp, after the registers. */
    private static final int USER_RSP = 120;
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
        } else if (number == UNAME) {
            result = uname(a0);
        } else if (number == GETCWD) {
            result = getcwd(a0, a1);
        } else if (number == FORK) {
            result = fork(frame);
        } else if (number == EXECVE) {
            result = execve(a0, a1, a2);
            if (result == 0) {
                // The new image is in place; leave the old one's system call the way exit does.
                Magic.disableInterrupts();
                Magic.leaveUser(frame + FRAME_BYTES, UserMode.EXECED);
                return;
            }
        } else if (number == EXIT || number == EXIT_GROUP) {
            Magic.disableInterrupts();
            Magic.leaveUser(frame + FRAME_BYTES, (int) a0);
            return;
        } else {
            result = -ENOSYS;
        }
        Frame.setRegister(frame, Frame.RAX, result);
        Magic.disableInterrupts();
        if (Process.killedHere()) {
            Magic.leaveUser(frame + FRAME_BYTES, UserMode.KILLED);
        }
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

    /**
     * The child carries on from this system call, returning 0, with a copy of everything: memory,
     * registers, FS, GS and FPU state, and the same open files.
     */
    private static long fork(long frame) {
        Process self = Process.current();
        if (self == null) {
            return -ENOSYS;
        }
        long[] registers = new long[Frame.REGISTERS.length];
        for (int i = 0; i < registers.length; i++) {
            registers[i] = Frame.register(frame, i);
        }
        registers[Frame.RAX] = 0;
        AddressSpace space;
        try {
            space = Scheduler.addressSpace().copy();
        } catch (OutOfMemoryError e) {
            return -ENOMEM;
        }
        // syscall left the return address in rcx.
        long rip = Frame.register(frame, Frame.RCX);
        long rsp = Magic.peekLong(frame + USER_RSP);
        return Process.fork(self, space, rip, rsp, registers, UserState.copyOfThisCpu()).pid;
    }

    /**
     * Loads the new image into a fresh address space first, so a failure returns an error to the
     * old one. Returns 0 once the new one is ready for the caller to switch to.
     */
    private static long execve(long pathAddress, long argvAddress, long envpAddress) {
        Process self = Process.current();
        if (self == null) {
            return -ENOSYS;
        }
        String path = userString(pathAddress);
        String[] argv = userStrings(argvAddress);
        String[] envp = userStrings(envpAddress);
        if (path == null || argv == null || envp == null) {
            return -EFAULT;
        }
        if (argv.length > MAX_ARGS || envp.length > MAX_ARGS) {
            return -E2BIG;
        }
        Fat fs = Fat.mounted();
        if (fs == null) {
            return -ENOENT;
        }
        byte[] file;
        try {
            if (fs.lookup(path).directory) {
                return -EACCES;
            }
            file = fs.read(path);
        } catch (IllegalArgumentException e) {
            return -ENOENT;
        } catch (IllegalStateException e) {
            return -EIO;
        }
        try {
            self.replaceImage(Elf.load(file, argv, envp));
        } catch (IllegalArgumentException e) {
            return e.getMessage().equals("arguments too long") ? -E2BIG : -ENOEXEC;
        } catch (OutOfMemoryError e) {
            return -ENOMEM;
        }
        return 0;
    }

    /**
     * The strings a NULL-terminated array of pointers points at, none for a null array, or null
     * if any of it isn't readable. Stops counting past MAX_ARGS.
     */
    private static String[] userStrings(long array) {
        if (array == 0) {
            return new String[0];
        }
        int count = 0;
        while (count <= MAX_ARGS) {
            if (!userReadable(array + 8L * count, 8)) {
                return null;
            }
            if (Magic.peekLong(array + 8L * count) == 0) {
                break;
            }
            count++;
        }
        String[] strings = new String[count];
        for (int i = 0; i < count; i++) {
            strings[i] = userString(Magic.peekLong(array + 8L * i));
            if (strings[i] == null) {
                return null;
            }
        }
        return strings;
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
    private static long getcwd(long buffer, long size) {
        if (size < 2) {
            return -ERANGE;
        }
        if (!userWritable(buffer, 2)) {
            return -EFAULT;
        }
        Magic.pokeByte(buffer, (byte) '/');
        Magic.pokeByte(buffer + 1, (byte) 0);
        return 2;
    }

    private static final int UTSNAME_FIELD_BYTES = 65;
    private static final int UTSNAME_BYTES = 6 * UTSNAME_FIELD_BYTES;

    /**
     * Writes the six fields of the x86-64 struct utsname.
     * Validates the entire output buffer before writing anything.
     */
    private static long uname(long buffer) {
        if (!userWritable(buffer, UTSNAME_BYTES)) {
            return -EFAULT;
        }

        writeUnameField(buffer, "Duke");
        writeUnameField(buffer + UTSNAME_FIELD_BYTES, "duke");
        writeUnameField(buffer + 2L * UTSNAME_FIELD_BYTES, "0.8");
        writeUnameField(buffer + 3L * UTSNAME_FIELD_BYTES, "duke");
        writeUnameField(buffer + 4L * UTSNAME_FIELD_BYTES, "x86_64");
        writeUnameField(buffer + 5L * UTSNAME_FIELD_BYTES, "(none)");

        return 0;
    }

    /** Writes one 65-byte, NUL-terminated and zero-padded field. */
    private static void writeUnameField(long address, String value) {
        for (int i = 0; i < UTSNAME_FIELD_BYTES; i++) {
            byte b = i < value.length() ? (byte) value.charAt(i) : 0;
            Magic.pokeByte(address + i, b);
        }
    }

}
