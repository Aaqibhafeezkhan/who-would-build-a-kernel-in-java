package duke.kernel.user;

import duke.rt.Magic;
import duke.rt.Tib;

/**
 * A buffer with a read end and a write end, each an open file. A read waits for bytes while a
 * write end is open somewhere and returns 0 once none is. A write waits for room while a read end
 * is open and fails with EPIPE once none is (there are no signals for SIGPIPE yet).
 *
 * <p>Whoever makes one holds a reference to each end, so neither looks closed before the ends are
 * handed out; it drops them with {@link OpenFile#release} once descriptors hold them instead.
 */
final class Pipe {

    /** Linux's default capacity. */
    static final int CAPACITY = 65536;
    /** PIPE_BUF: a write this size or smaller goes in whole, never interleaved with another's. */
    static final int ATOMIC_WRITE = 4096;
    // Waiters check this often for their process being killed, as console reads and futexes do.
    private static final long POLL_MILLIS = 10;

    final End reader = new End(this, true);
    final End writer = new End(this, false);

    private final byte[] ring = new byte[CAPACITY];
    private int head;
    private int size;
    private int readers = 1;
    private int writers = 1;

    static final class End extends OpenFile {
        private final Pipe pipe;
        private final boolean reads;

        End(Pipe pipe, boolean reads) {
            this.pipe = pipe;
            this.reads = reads;
        }

        @Override
        long read(long buffer, long count) {
            return reads ? pipe.read(buffer, count) : -SystemCalls.EBADF;
        }

        @Override
        long write(long buffer, long count) {
            return reads ? -SystemCalls.EBADF : pipe.write(buffer, count);
        }

        @Override
        int mode() {
            return MODE_FIFO;
        }

        @Override
        void retain() {
            pipe.count(reads, 1);
        }

        @Override
        void release() {
            pipe.count(reads, -1);
        }
    }

    private synchronized void count(boolean reads, int change) {
        if (reads) {
            readers += change;
        } else {
            writers += change;
        }
        // A reader waiting on the last writer, or the other way round.
        notifyAll();
    }

    private long read(long buffer, long count) {
        if (count == 0) {
            return 0;
        }
        byte[] chunk;
        synchronized (this) {
            while (size == 0) {
                if (writers == 0) {
                    return 0;
                }
                if (!await()) {
                    return -SystemCalls.EINTR;
                }
            }
            chunk = new byte[(int) Math.min(count, size)];
            int first = Math.min(chunk.length, CAPACITY - head);
            System.arraycopy(ring, head, chunk, 0, first);
            System.arraycopy(ring, 0, chunk, first, chunk.length - first);
            head = (head + chunk.length) % CAPACITY;
            size -= chunk.length;
            notifyAll();
        }
        // Outside the lock: a copy-on-write break here can flush TLBs and take a while.
        return SystemCalls.putBytes(buffer, chunk, 0, chunk.length) ? chunk.length : -SystemCalls.EFAULT;
    }

    private long write(long buffer, long count) {
        byte[] chunk = new byte[(int) Math.min(count, ATOMIC_WRITE)];
        for (long done = 0; done < count; done += chunk.length) {
            int n = (int) Math.min(chunk.length, count - done);
            if (Magic.copyUserMemory(Magic.addressOf(chunk) + Tib.ARRAY_DATA, buffer + done, n) != 0) {
                return done > 0 ? done : -SystemCalls.EFAULT;
            }
            synchronized (this) {
                for (int at = 0; at < n; ) {
                    if (readers == 0) {
                        return done + at > 0 ? done + at : -SystemCalls.EPIPE;
                    }
                    int room = CAPACITY - size;
                    if (room == 0 || count <= ATOMIC_WRITE && room < n) {
                        if (!await()) {
                            return done + at > 0 ? done + at : -SystemCalls.EINTR;
                        }
                        continue;
                    }
                    int m = Math.min(room, n - at);
                    int tail = (head + size) % CAPACITY;
                    int first = Math.min(m, CAPACITY - tail);
                    System.arraycopy(chunk, at, ring, tail, first);
                    System.arraycopy(chunk, at + first, ring, 0, m - first);
                    size += m;
                    at += m;
                    notifyAll();
                }
            }
        }
        return count;
    }

    /** Waits on the lock for a change, or a while; false if the calling process is to stop instead. */
    private boolean await() {
        if (Process.killedHere()) {
            return false;
        }
        try {
            wait(POLL_MILLIS);
        } catch (InterruptedException e) {
            return false;
        }
        return true;
    }
}
