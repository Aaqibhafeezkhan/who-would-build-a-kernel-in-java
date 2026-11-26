package duke.kernel.user;

import duke.kernel.fs.Fat;
import java.util.List;

/**
 * What a file descriptor refers to: the console, a file or directory on the FAT disk, or one end
 * of a pipe. Several descriptors can refer to the same one, in one process or across a fork, and
 * {@link FileTable} tells it as each starts and stops doing so.
 */
abstract class OpenFile {

    static final int SEEK_SET = 0;
    static final int SEEK_CUR = 1;
    static final int SEEK_END = 2;

    // st_mode's file types, with the permission bits a read-only filesystem would show.
    static final int MODE_REGULAR = 0100444;
    static final int MODE_DIRECTORY = 0040555;
    static final int MODE_CHARACTER = 0020620;
    static final int MODE_FIFO = 0010600;

    /** The keyboard and screen: one, shared by every process and the shell. */
    static final OpenFile CONSOLE = new Terminal();

    static OpenFile file(byte[] contents, long inode) {
        return new DiskFile(contents, null, inode);
    }

    static OpenFile directory(List<Fat.Entry> listing, long inode) {
        return new DiskFile(null, listing, inode);
    }

    /** Copies up to {@code count} bytes into user memory at {@code buffer}, already checked writable. */
    abstract long read(long buffer, long count);

    /** From user memory at {@code buffer}, already checked readable. */
    abstract long write(long buffer, long count);

    long seek(long offset, int whence) {
        return -SystemCalls.ESPIPE;
    }

    abstract int mode();

    long size() {
        return 0;
    }

    long inode() {
        return 0;
    }

    /** linux_dirent64 records into user memory at {@code buffer}, already checked writable. */
    long getdents(long buffer, long count) {
        return -SystemCalls.ENOTDIR;
    }

    /** One more descriptor refers to this. */
    void retain() {
    }

    /** One fewer descriptor refers to this. */
    void release() {
    }
}
