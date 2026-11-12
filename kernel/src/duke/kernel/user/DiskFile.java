package duke.kernel.user;

/**
 * A file or directory on the FAT disk. Files are read whole when they're opened (the disk is
 * read-only), so reads just copy out of memory.
 */
final class DiskFile extends OpenFile {

    private final byte[] contents;
    private final boolean directory;
    private long position;

    DiskFile(byte[] contents, boolean directory) {
        this.contents = contents;
        this.directory = directory;
    }

    @Override
    synchronized long read(long buffer, long count) {
        if (directory) {
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

    @Override
    synchronized long seek(long offset, int whence) {
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

    @Override
    int mode() {
        return directory ? MODE_DIRECTORY : MODE_REGULAR;
    }

    @Override
    long size() {
        return contents == null ? 0 : contents.length;
    }
}
