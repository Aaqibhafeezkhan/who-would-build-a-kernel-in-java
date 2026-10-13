package duke.kernel.user;

/** A process's file descriptors. A new one has the console on 0, 1 and 2, as a shell would leave it. */
final class FileTable {

    /** Linux's default soft limit is 1024; nothing here needs more than a few. */
    static final int MAX_FILES = 64;

    /**
     * For kernel tests that run user code with no process around it. Here rather than in
     * SystemCalls: the syscall stub enters SystemCalls without running its static initializer.
     */
    static final FileTable NO_PROCESS = new FileTable();

    private final OpenFile[] files = new OpenFile[MAX_FILES];

    FileTable() {
        files[0] = OpenFile.CONSOLE;
        files[1] = OpenFile.CONSOLE;
        files[2] = OpenFile.CONSOLE;
    }

    /** The file {@code fd} refers to, or null if it's not open. */
    synchronized OpenFile get(long fd) {
        return fd >= 0 && fd < MAX_FILES ? files[(int) fd] : null;
    }

    /** Puts {@code file} at the lowest free descriptor and returns it, or -EMFILE if there's none. */
    synchronized int add(OpenFile file) {
        for (int fd = 0; fd < MAX_FILES; fd++) {
            if (files[fd] == null) {
                files[fd] = file;
                return fd;
            }
        }
        return -SystemCalls.EMFILE;
    }

    synchronized boolean close(long fd) {
        if (get(fd) == null) {
            return false;
        }
        files[(int) fd] = null;
        return true;
    }
}
