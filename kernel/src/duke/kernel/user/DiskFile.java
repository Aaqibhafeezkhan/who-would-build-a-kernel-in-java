package duke.kernel.user;

import duke.kernel.fs.Fat;
import java.util.List;

/**
 * A file or directory on the FAT disk. Files are read whole when they're opened (the disk is
 * read-only), so reads just copy out of memory. A directory is listed when it's opened, and its
 * position counts entries.
 */
final class DiskFile extends OpenFile {

    private static final int DT_DIR = 4;
    private static final int DT_REG = 8;
    /** d_ino, d_off, d_reclen and d_type, before the name. */
    private static final int DIRENT_HEADER = 19;

    private final byte[] contents;
    private final List<Fat.Entry> listing;
    private final long inode;
    private long position;

    DiskFile(byte[] contents, List<Fat.Entry> listing, long inode) {
        this.contents = contents;
        this.listing = listing;
        this.inode = inode;
    }

    @Override
    synchronized long read(long buffer, long count) {
        if (listing != null) {
            return -SystemCalls.EISDIR;
        }
        long n = Math.max(0, Math.min(count, contents.length - position));
        if (n > 0) {
            if (!SystemCalls.putBytes(buffer, contents, position, n)) {
                return -SystemCalls.EFAULT;
            }
            position += n;
        }
        return n;
    }

    @Override
    long write(long buffer, long count) {
        return -SystemCalls.EBADF;
    }

    /** A directory takes SEEK_SET to a d_off it handed out, which is how rewinddir and seekdir work. */
    @Override
    synchronized long seek(long offset, int whence) {
        if (listing != null) {
            if (whence != SEEK_SET || offset < 0) {
                return -SystemCalls.EINVAL;
            }
            position = offset;
            return offset;
        }
        long base = whence == SEEK_SET ? 0 : whence == SEEK_CUR ? position : whence == SEEK_END ? size() : -1;
        if (base < 0) {
            return -SystemCalls.EINVAL;
        }
        long target = base + offset;
        if (target < 0) {
            return -SystemCalls.EINVAL;
        }
        position = target;
        return target;
    }

    @Override
    int mode() {
        return listing != null ? MODE_DIRECTORY : MODE_REGULAR;
    }

    @Override
    long size() {
        return contents == null ? 0 : contents.length;
    }

    @Override
    long inode() {
        return inode;
    }

    @Override
    synchronized long getdents(long buffer, long count) {
        if (listing == null) {
            return -SystemCalls.ENOTDIR;
        }
        Fat fs = Fat.mounted();
        byte[] out = new byte[(int) Math.min(count, 1 << 16)];
        int n = 0;
        for (; position < listing.size(); position++) {
            Fat.Entry e = listing.get((int) position);
            byte[] name = utf8(e.name);
            int length = (DIRENT_HEADER + name.length + 1 + 7) & ~7;
            if (n + length > out.length) {
                if (n == 0) {
                    return -SystemCalls.EINVAL;
                }
                break;
            }
            putLong(out, n, fs.inode(e));
            putLong(out, n + 8, position + 1);
            out[n + 16] = (byte) length;
            out[n + 17] = (byte) (length >> 8);
            out[n + 18] = (byte) (e.directory ? DT_DIR : DT_REG);
            System.arraycopy(name, 0, out, n + DIRENT_HEADER, name.length);
            n += length;
        }
        return SystemCalls.putBytes(buffer, out, 0, n) ? n : -SystemCalls.EFAULT;
    }

    private static void putLong(byte[] out, int at, long value) {
        for (int i = 0; i < 8; i++) {
            out[at + i] = (byte) (value >>> 8 * i);
        }
    }

    /** Strings are Latin-1, so a char is one byte of UTF-8 or two. */
    private static byte[] utf8(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            n += s.charAt(i) < 0x80 ? 1 : 2;
        }
        byte[] out = new byte[n];
        int at = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                out[at++] = (byte) c;
            } else {
                out[at++] = (byte) (0xC0 | c >> 6);
                out[at++] = (byte) (0x80 | c & 0x3F);
            }
        }
        return out;
    }
}
