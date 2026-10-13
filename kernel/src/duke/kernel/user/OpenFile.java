package duke.kernel.user;

import duke.kernel.Console;
import duke.kernel.Shell;
import duke.rt.Magic;
import duke.rt.Tib;

/**
 * What a file descriptor refers to: the console, or a file or directory on the FAT disk. Files are
 * read whole when they're opened (the disk is read-only), so reads just copy out of memory.
 */
final class OpenFile {

    static final int SEEK_SET = 0;
    static final int SEEK_CUR = 1;
    static final int SEEK_END = 2;

    // st_mode's file types, with the permission bits a read-only filesystem would show.
    static final int MODE_REGULAR = 0100444;
    static final int MODE_DIRECTORY = 0040555;
    static final int MODE_CHARACTER = 0020620;

    /** The keyboard and screen: one, shared by every process and the shell. */
    static final OpenFile CONSOLE = new OpenFile(null, false);

    // A line typed at the console that a read didn't take all of, kept for the next read.
    private static byte[] pendingLine;
    private static int pendingAt;

    private final byte[] contents;
    private final boolean directory;
    private long position;

    private OpenFile(byte[] contents, boolean directory) {
        this.contents = contents;
        this.directory = directory;
    }

    static OpenFile file(byte[] contents) {
        return new OpenFile(contents, false);
    }

    static OpenFile directory() {
        return new OpenFile(null, true);
    }

    /** Copies up to {@code count} bytes into user memory at {@code buffer}, already checked writable. */
    long read(long buffer, long count) {
        if (directory) {
            return -SystemCalls.EISDIR;
        }
        if (this == CONSOLE) {
            return readConsole(buffer, count);
        }
        long n = Math.max(0, Math.min(count, contents.length - position));
        if (n > 0) {
            Magic.copyMemory(buffer, Magic.addressOf(contents) + Tib.ARRAY_DATA + position, n);
            position += n;
        }
        return n;
    }

    /**
     * A line at a time, as a terminal in canonical mode gives it: the shell's line editor echoes and
     * edits it, and the read returns once Enter is pressed.
     */
    private static long readConsole(long buffer, long count) {
        synchronized (CONSOLE) {
            if (pendingLine == null) {
                String line = Shell.readLine() + "\n";
                pendingLine = new byte[line.length()];
                for (int i = 0; i < line.length(); i++) {
                    pendingLine[i] = (byte) line.charAt(i);
                }
                pendingAt = 0;
            }
            long n = Math.min(count, pendingLine.length - pendingAt);
            Magic.copyMemory(buffer, Magic.addressOf(pendingLine) + Tib.ARRAY_DATA + pendingAt, n);
            pendingAt += (int) n;
            if (pendingAt == pendingLine.length) {
                pendingLine = null;
            }
            return n;
        }
    }

    /** From user memory at {@code buffer}, already checked readable. */
    long write(long buffer, long count) {
        if (this != CONSOLE) {
            return -SystemCalls.EBADF;
        }
        for (long i = 0; i < count; i++) {
            Console.write(Magic.peekByte(buffer + i) & 0xFF);
        }
        return count;
    }

    long seek(long offset, int whence) {
        if (this == CONSOLE) {
            return -SystemCalls.ESPIPE;
        }
        long base = whence == SEEK_SET ? 0 : whence == SEEK_CUR ? position : whence == SEEK_END ? size() : -1;
        if (base < 0 || directory) {
            return -SystemCalls.EINVAL;
        }
        long target = base + offset;
        if (target < 0) {
            return -SystemCalls.EINVAL;
        }
        position = target;
        return target;
    }

    int mode() {
        return this == CONSOLE ? MODE_CHARACTER : directory ? MODE_DIRECTORY : MODE_REGULAR;
    }

    long size() {
        return contents == null ? 0 : contents.length;
    }
}
