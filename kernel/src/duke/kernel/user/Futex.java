package duke.kernel.user;

import duke.kernel.mm.AddressSpace;
import duke.kernel.time.HpetClock;
import duke.rt.Magic;
import java.util.ArrayList;
import java.util.List;

/**
 * futex(2)'s WAIT and WAKE: threads sleeping on a word of their process's memory until another
 * thread wakes them. A word is its address space and address, so the same address in two
 * processes is two futexes.
 *
 * <p>One lock and one list for every futex: a wake notifies every waiter and each checks whether
 * it was the one woken. Waiters also wake every {@link #POLL_MILLIS} to notice that their process
 * was killed or is exiting, as a console read does.
 */
final class Futex {

    private static final long POLL_MILLIS = 10;

    private static final class Waiter {
        final AddressSpace space;
        final long address;
        boolean woken;

        Waiter(AddressSpace space, long address) {
            this.space = space;
            this.address = address;
        }
    }

    private static final Object LOCK = new Object();
    private static final List<Waiter> WAITERS = new ArrayList<>();

    private Futex() {
    }

    /**
     * Sleeps while the word at {@code address} holds {@code expected}, until woken or
     * {@code timeoutNanos} pass (forever when negative). The word must be readable in the calling
     * thread's address space, which is {@code space}.
     */
    static long wait(AddressSpace space, long address, int expected, long timeoutNanos) {
        long deadline = timeoutNanos < 0 ? Long.MAX_VALUE : HpetClock.nanos() + Math.min(timeoutNanos, Long.MAX_VALUE / 2);
        synchronized (LOCK) {
            // Under the lock a waker takes too: a wake after the word changes can't slip in before the wait.
            if (Magic.peekInt(address) != expected) {
                return -SystemCalls.EAGAIN;
            }
            Waiter self = new Waiter(space, address);
            WAITERS.add(self);
            try {
                while (!self.woken) {
                    if (Process.killedHere()) {
                        return -SystemCalls.EINTR;
                    }
                    long left = deadline - HpetClock.nanos();
                    if (left <= 0) {
                        return -SystemCalls.ETIMEDOUT;
                    }
                    LOCK.wait(Math.min(POLL_MILLIS, (left + 999_999) / 1_000_000));
                }
                return 0;
            } catch (InterruptedException e) {
                return -SystemCalls.EINTR;
            } finally {
                WAITERS.remove(self);
            }
        }
    }

    /** Wakes up to {@code count} threads waiting on the word, oldest first, and returns how many. */
    static int wake(AddressSpace space, long address, int count) {
        synchronized (LOCK) {
            int woken = 0;
            for (int i = 0; i < WAITERS.size() && woken < count; i++) {
                Waiter w = WAITERS.get(i);
                if (!w.woken && w.space == space && w.address == address) {
                    w.woken = true;
                    woken++;
                }
            }
            if (woken > 0) {
                LOCK.notifyAll();
            }
            return woken;
        }
    }
}
