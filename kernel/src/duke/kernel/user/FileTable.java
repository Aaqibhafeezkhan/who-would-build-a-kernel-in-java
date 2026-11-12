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

    FileTable() {
        files[0] = OpenFile.CONSOLE;
        files[1] = OpenFile.CONSOLE;
        files[2] = OpenFile.CONSOLE;
    }

    /** For fork: the same open files as {@code parent}, sharing their positions as Linux's do. */
    void copyFrom(FileTable parent) {
        synchronized (parent) {
            synchronized (this) {
                for (int fd = 0; fd < MAX_FILES; fd++) {
                    set(fd, parent.files[fd]);
                    closeOnExec[fd] = parent.closeOnExec[fd];
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
        for (int fd = 0; fd < MAX_FILES; fd++) {
            if (files[fd] == null) {
                set(fd, file);
                closeOnExec[fd] = cloexec;
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
        OpenFile file = get(oldfd);
        return file == null ? -SystemCalls.EBADF : add(file);
    }

    synchronized int dup2(long oldfd, long newfd) {
        OpenFile file = get(oldfd);
        if (file == null || newfd < 0 || newfd >= MAX_FILES) {
            return -SystemCalls.EBADF;
        }
        if (oldfd != newfd) {
            set((int) newfd, file);
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
        if (old != null) {
            old.release();
        }
    }
}
