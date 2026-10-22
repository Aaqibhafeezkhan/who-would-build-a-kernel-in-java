package duke.kernel;

import duke.kernel.Scheduler.Task;
import duke.rt.Magic;

/**
 * Java monitors for a user program, as the kernel's: an entry in a small table keyed by the
 * object's address while some thread holds, waits on or is blocked on the monitor, and released
 * straight to the first blocked thread. Where the kernel switches away, a thread here parks on its
 * own futex word, and whoever hands it the monitor unparks it.
 *
 * <p>All of this runs under the scheduler's lock.
 */
public final class Monitors {

    static final class Monitor {
        long object;
        Task owner;
        int count;
        Task blockedHead;
        Task blockedTail;
        Task waitingHead;
        Task waitingTail;
        Monitor nextFree;
    }

    private static final long NANOS_PER_MILLI = 1_000_000;

    private static Monitor[] active = new Monitor[16];
    private static int activeCount;
    private static Monitor free;

    private Monitors() {
    }

    public static void enter(Object object) {
        if (object == null) {
            throw new NullPointerException();
        }
        Task self = Scheduler.current();
        if (self == null) {
            return;
        }
        lockWithRoom();
        long address = Magic.addressOf(object);
        Monitor monitor = find(address);
        if (monitor == null) {
            monitor = open(address);
        }
        if (monitor.owner == null) {
            monitor.owner = self;
            monitor.count = 1;
        } else if (monitor.owner == self) {
            monitor.count++;
        } else {
            self.monitor = monitor;
            self.monitorCount = 1;
            self.state = Scheduler.BLOCKED;
            monitor.blockedTail = append(monitor.blockedHead, monitor.blockedTail, self);
            if (monitor.blockedHead == null) {
                monitor.blockedHead = self;
            }
            Scheduler.unlock();
            // Comes back owning the monitor: release() hands it over.
            Scheduler.park(self, 0);
            return;
        }
        Scheduler.unlock();
    }

    public static void exit(Object object) {
        if (!tryExit(object)) {
            throw new IllegalMonitorStateException("current thread is not owner");
        }
    }

    /** For the unwinder leaving a synchronized method, where the monitor may already be gone. */
    public static void exitUnwinding(Object object) {
        tryExit(object);
    }

    public static void await(Object object, long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        Task self = Scheduler.current();
        long deadline = millis == 0 ? 0 : Scheduler.nanoTime(self) + Math.min(millis, Long.MAX_VALUE / 4 / NANOS_PER_MILLI) * NANOS_PER_MILLI;
        Scheduler.lock();
        Monitor monitor = owned(object, self);
        if (monitor == null) {
            Scheduler.unlock();
            throw new IllegalMonitorStateException("current thread is not owner");
        }
        self.monitor = monitor;
        self.monitorCount = monitor.count;
        self.state = Scheduler.WAITING;
        monitor.waitingTail = append(monitor.waitingHead, monitor.waitingTail, self);
        if (monitor.waitingHead == null) {
            monitor.waitingHead = self;
        }
        release(monitor);
        Scheduler.unlock();
        while (!Scheduler.park(self, deadline)) {
            deadline = 0;
            Scheduler.lock();
            // Unless a notify got in first, which leaves it blocked or already handed the monitor.
            boolean owns = self.state == Scheduler.WAITING && timedOut(self);
            Scheduler.unlock();
            if (owns) {
                return;
            }
        }
    }

    public static void notify(Object object, boolean all) {
        Task self = Scheduler.current();
        Scheduler.lock();
        Monitor monitor = owned(object, self);
        if (monitor == null) {
            Scheduler.unlock();
            throw new IllegalMonitorStateException("current thread is not owner");
        }
        do {
            Task waiter = monitor.waitingHead;
            if (waiter == null) {
                break;
            }
            unlinkWaiting(monitor, waiter);
            block(monitor, waiter);
        } while (all);
        Scheduler.unlock();
    }

    public static boolean holds(Object object) {
        if (object == null) {
            throw new NullPointerException();
        }
        Scheduler.lock();
        boolean held = owned(object, Scheduler.current()) != null;
        Scheduler.unlock();
        return held;
    }

    /** A timed wait ran out: takes the monitor if it's free and returns true, else queues for it. */
    private static boolean timedOut(Task task) {
        Monitor monitor = task.monitor;
        unlinkWaiting(monitor, task);
        if (monitor.owner == null) {
            monitor.owner = task;
            monitor.count = task.monitorCount;
            task.monitor = null;
            task.state = Scheduler.RUNNING;
            return true;
        }
        block(monitor, task);
        return false;
    }

    private static boolean tryExit(Object object) {
        Task self = Scheduler.current();
        if (self == null) {
            return true;
        }
        Scheduler.lock();
        Monitor monitor = owned(object, self);
        if (monitor != null && --monitor.count == 0) {
            release(monitor);
        }
        Scheduler.unlock();
        return monitor != null;
    }

    private static Monitor owned(Object object, Task self) {
        Monitor monitor = find(Magic.addressOf(object));
        return monitor != null && monitor.owner == self ? monitor : null;
    }

    /** Hands the monitor to the first blocked thread, or frees it. */
    private static void release(Monitor monitor) {
        Task next = monitor.blockedHead;
        if (next == null) {
            monitor.owner = null;
            if (monitor.waitingHead == null) {
                close(monitor);
            }
            return;
        }
        monitor.blockedHead = next.next;
        if (monitor.blockedHead == null) {
            monitor.blockedTail = null;
        }
        monitor.owner = next;
        monitor.count = next.monitorCount;
        next.monitor = null;
        next.state = Scheduler.RUNNING;
        Scheduler.unpark(next);
    }

    private static void block(Monitor monitor, Task task) {
        task.state = Scheduler.BLOCKED;
        monitor.blockedTail = append(monitor.blockedHead, monitor.blockedTail, task);
        if (monitor.blockedHead == null) {
            monitor.blockedHead = task;
        }
    }

    /** Appends to a list linked through Task.next and returns the new tail. */
    private static Task append(Task head, Task tail, Task task) {
        task.next = null;
        if (head != null) {
            tail.next = task;
        }
        return task;
    }

    private static void unlinkWaiting(Monitor monitor, Task task) {
        Task previous = null;
        for (Task t = monitor.waitingHead; t != null; previous = t, t = t.next) {
            if (t == task) {
                if (previous == null) {
                    monitor.waitingHead = t.next;
                } else {
                    previous.next = t.next;
                }
                if (monitor.waitingTail == t) {
                    monitor.waitingTail = previous;
                }
                t.next = null;
                return;
            }
        }
    }

    private static Monitor find(long address) {
        for (int i = 0; i < activeCount; i++) {
            if (active[i].object == address) {
                return active[i];
            }
        }
        return null;
    }

    /**
     * Takes the scheduler lock with a spare monitor and a free table slot ready, so opening one
     * under the lock never allocates. Allocates them first, without the lock, when either is missing.
     */
    private static void lockWithRoom() {
        while (true) {
            Scheduler.lock();
            if (free != null && activeCount < active.length) {
                return;
            }
            int length = active.length;
            boolean full = activeCount == length;
            Scheduler.unlock();
            Monitor spare = new Monitor();
            Monitor[] grown = full ? new Monitor[length * 2] : null;
            Scheduler.lock();
            spare.nextFree = free;
            free = spare;
            if (grown != null && grown.length > active.length) {
                for (int i = 0; i < activeCount; i++) {
                    grown[i] = active[i];
                }
                active = grown;
            }
            Scheduler.unlock();
        }
    }

    /** Under lockWithRoom, so both the spare and the slot are there. */
    private static Monitor open(long address) {
        Monitor monitor = free;
        free = monitor.nextFree;
        monitor.nextFree = null;
        monitor.object = address;
        active[activeCount++] = monitor;
        return monitor;
    }

    private static void close(Monitor monitor) {
        for (int i = 0; i < activeCount; i++) {
            if (active[i] == monitor) {
                active[i] = active[--activeCount];
                active[activeCount] = null;
                break;
            }
        }
        monitor.object = 0;
        monitor.nextFree = free;
        free = monitor;
    }
}
