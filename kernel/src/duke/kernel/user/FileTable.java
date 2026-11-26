package duke.kernel.user;

/**
 * A process's file descriptors. A new one has the console on 0, 1 and 2, as a shell would leave it.
 * Every slot holds a reference to its open file ({@link OpenFile#retain}), dropped when the slot is
 * closed or overwritten, or the process ends: that's how a pipe knows its last end has gone.
 */
final class FileTable {

    /** Linux's default soft limit is 1024; nothing here needs more than a few. */
    static final int MAX_FILES = 64;

    /**
     * For kernel tests that run user code with no process around it. Here rather than in
     * SystemCalls: the syscall stub enters SystemCalls without running its static initializer.
     */
    static final FileTable NO_PROCESS = new FileTable();

    private final OpenFile[] files = new OpenFile[MAX_FILES];
    /** FD_CLOEXEC: execve closes these. */
    private final boolean[] closeOnExec = new boolean[MAX_FILES];
    /** O_RDONLY, O_WRONLY, or O_RDWR for each descriptor. */
    private final int[] accessModes = new int[MAX_FILES];

    FileTable() {
        files[0] = OpenFile.CONSOLE;
        files[1] = OpenFile.CONSOLE;
        files[2] = OpenFile.CONSOLE;
        accessModes[0] = SystemCalls.O_RDWR;
        accessModes[1] = SystemCalls.O_RDWR;
        accessModes[2] = SystemCalls.O_RDWR;
    }

    /** For fork: the same open files as {@code parent}, sharing their positions as Linux's do. */
    void copyFrom(FileTable parent) {
        synchronized (parent) {
            synchronized (this) {
                for (int fd = 0; fd < MAX_FILES; fd++) {
                    set(fd, parent.files[fd]);
                    closeOnExec[fd] = parent.closeOnExec[fd];
                    accessModes[fd] = parent.accessModes[fd];
                }
            }
        }
    }

    /** The file {@code fd} refers to, or null if it's not open. */
    synchronized OpenFile get(long fd) {
        return fd >= 0 && fd < MAX_FILES ? files[(int) fd] : null;
    }

    /** Puts {@code file} at the lowest free descriptor and returns it, or -EMFILE if there's none. */
    synchronized int add(OpenFile file, boolean cloexec) {
        return add(file, cloexec, SystemCalls.O_RDONLY);
    }

    synchronized int add(OpenFile file, boolean cloexec, int accessMode) {
        for (int fd = 0; fd < MAX_FILES; fd++) {
            if (files[fd] == null) {
                set(fd, file);
                closeOnExec[fd] = cloexec;
                accessModes[fd] = accessMode;
                return fd;
            }
        }
        return -SystemCalls.EMFILE;
    }

    synchronized int add(OpenFile file) {
        return add(file, false);
    }

    synchronized boolean close(long fd) {
        if (get(fd) == null) {
            return false;
        }
        set((int) fd, null);
        return true;
    }

    synchronized int dup(long oldfd) {
        return dupFrom(oldfd, 0, false);
    }

    /** Duplicates {@code oldfd} to the lowest unused descriptor at or above {@code minFd}. */
    synchronized int dupFrom(long oldfd, long minFd, boolean cloexec) {
        OpenFile file = get(oldfd);
        if (file == null) {
            return -SystemCalls.EBADF;
        }
        if (minFd < 0 || minFd >= MAX_FILES) {
            return -SystemCalls.EINVAL;
        }
        for (int fd = (int) minFd; fd < MAX_FILES; fd++) {
            if (files[fd] == null) {
                set(fd, file);
                closeOnExec[fd] = cloexec;
                accessModes[fd] = accessModes[(int) oldfd];
                return fd;
            }
        }
        return -SystemCalls.EMFILE;
    }

    synchronized int accessMode(long fd) {
        return get(fd) == null ? -SystemCalls.EBADF : accessModes[(int) fd];
    }

    synchronized int descriptorFlags(long fd) {
        return get(fd) == null ? -SystemCalls.EBADF : closeOnExec[(int) fd] ? SystemCalls.FD_CLOEXEC : 0;
    }

    synchronized int setDescriptorFlags(long fd, long flags) {
        if (get(fd) == null) {
            return -SystemCalls.EBADF;
        }
        closeOnExec[(int) fd] = (flags & SystemCalls.FD_CLOEXEC) != 0;
        return 0;
    }

    synchronized int dup2(long oldfd, long newfd) {
        OpenFile file = get(oldfd);
        if (file == null || newfd < 0 || newfd >= MAX_FILES) {
            return -SystemCalls.EBADF;
        }
        if (oldfd != newfd) {
            set((int) newfd, file);
            accessModes[(int) newfd] = accessModes[(int) oldfd];
        }
        return (int) newfd;
    }

    synchronized int dup3(long oldfd, long newfd, long flags) {
        if ((flags & ~(long) SystemCalls.O_CLOEXEC) != 0) {
            return -SystemCalls.EINVAL;
        }
        if (oldfd == newfd) {
            return -SystemCalls.EINVAL;
        }
        OpenFile file = get(oldfd);
        if (file == null || newfd < 0 || newfd >= MAX_FILES) {
            return -SystemCalls.EBADF;
        }
        set((int) newfd, file);
        accessModes[(int) newfd] = accessModes[(int) oldfd];
        if ((flags & SystemCalls.O_CLOEXEC) != 0) {
            closeOnExec[(int) newfd] = true;
        }
        return (int) newfd;
    }

    /** Points {@code fd} at {@code file}, closing whatever was there, as dup2 would. */
    synchronized void replace(int fd, OpenFile file) {
        set(fd, file);
    }

    /** For execve: closes what was opened with O_CLOEXEC. */
    synchronized void closeForExec() {
        for (int fd = 0; fd < MAX_FILES; fd++) {
            if (closeOnExec[fd]) {
                set(fd, null);
            }
        }
    }

    /** For a process that has ended. */
    synchronized void closeAll() {
        for (int fd = 0; fd < MAX_FILES; fd++) {
            set(fd, null);
        }
    }

    /** Points {@code fd} at {@code file}, or closes it for null, keeping the references right. */
    private void set(int fd, OpenFile file) {
        OpenFile old = files[fd];
        if (file != null) {
            file.retain();
        }
        files[fd] = file;
        closeOnExec[fd] = false;
        accessModes[fd] = SystemCalls.O_RDONLY;
        if (old != null) {
            old.release();
        }
    }
}
