package duke.kernel;

import duke.rt.Magic;
import duke.rt.Tib;

/**
 * Stops a user program's other threads for a collection, as the kernel stops its other CPUs: each
 * thread is a "CPU" here, by its slot. A thread running Java stops at its next safepoint, which a
 * request in its stack limit sends it to, and records its frame there ({@link #stopIfRequested}).
 *
 * <p>A thread in a system call that may block can't get to a safepoint, so it counts as stopped
 * already: {@link #blockingSyscall} records its frame and marks it native on the way in, and on the
 * way out it waits for any collection to finish before it touches the heap again. Marking itself
 * native and checking for a collection, against the collector raising its flag and checking who's
 * native, are each an xchg then a load, so one side always sees the other.
 */
public final class Smp {

    /** No thread in this slot yet. */
    private static final int NONE = 0;
    private static final int JAVA = 1;
    /** Blocked in a system call, starting, or finished: nothing that touches the heap. */
    private static final int NATIVE = 2;

    private static final int FUTEX = 202;
    private static final long FUTEX_WAIT_PRIVATE = 0 | 128;
    private static final long FUTEX_WAKE_PRIVATE = 1 | 128;
    /** Compiler.STACK_LIMIT, STACK_LIMIT_SAVED and PREEMPT. */
    private static final int BLOCK_STACK_LIMIT = 8;
    private static final int BLOCK_STACK_LIMIT_SAVED = 16;
    private static final long PREEMPT = -1;
    private static final long STOP_TIMEOUT_NANOS = 5_000_000_000L;

    private static final int[] STATES = new int[Scheduler.MAX_THREADS];
    /** A native thread's frame in blockingSyscall, where the collector starts its walk; 0 for none. */
    private static final long[] NATIVE_FRAMES = new long[Scheduler.MAX_THREADS];
    /** A stopped thread's frame in stopIfRequested; 0 while running. */
    private static final long[] STOPPED_FRAMES = new long[Scheduler.MAX_THREADS];
    /** The collecting thread's slot plus one, or 0. It may still make system calls, to panic. */
    private static final int[] STOPPING = new int[1];

    private Smp() {
    }

    /** Slots, for the collector: entries in {@link #stoppedFrame}. */
    public static int cpuCount() {
        return Scheduler.MAX_THREADS;
    }

    /** From the collector, holding the heap lock: returns once every other thread is stopped or native. */
    public static void stopOthers() {
        int self = Magic.cpuIndex();
        Magic.exchangeInt(address(STOPPING), self + 1);
        long deadline = 0;
        int straggler;
        while ((straggler = stillRunning(self)) >= 0) {
            requestStop(straggler);
            long now = Scheduler.nanoTime(Scheduler.current());
            if (deadline == 0) {
                deadline = now + STOP_TIMEOUT_NANOS;
            } else if (now > deadline) {
                Panic.panic("thread ", Integer.toString(straggler), " never stopped for a collection");
            }
            Magic.pause();
        }
    }

    /**
     * Lets the stopped threads go, and waits until each has: one that hasn't cleared its frame yet
     * would look stopped to the next collection.
     */
    public static void resumeOthers() {
        Magic.exchangeInt(address(STOPPING), 0);
        Magic.syscall(FUTEX, address(STOPPING), FUTEX_WAKE_PRIVATE, Integer.MAX_VALUE, 0, 0, 0);
        for (int slot = 0; slot < STOPPED_FRAMES.length; slot++) {
            while (STOPPED_FRAMES[slot] != 0) {
                Magic.pause();
            }
        }
    }

    /** Where to start walking a stopped or native thread's stack; 0 if it's running or has no Java frames. */
    public static long stoppedFrame(int slot) {
        return STATES[slot] == NATIVE ? NATIVE_FRAMES[slot] : STOPPED_FRAMES[slot];
    }

    /** From Runtime.preempt, at a safepoint: waits out a collection running on another thread. */
    public static void stopIfRequested() {
        int self = Magic.cpuIndex();
        if (!othersCollecting(self) || STATES[self] != JAVA) {
            return;
        }
        STOPPED_FRAMES[self] = Magic.framePointer();
        awaitCollection();
        STOPPED_FRAMES[self] = 0;
    }

    /**
     * A system call that may block, made as a native thread. Only longs in this frame, so the
     * collector's walk from it starts at the caller's call site, which has a stack map.
     */
    static long blockingSyscall(long number, long a0, long a1, long a2, long a3) {
        int self = Magic.cpuIndex();
        NATIVE_FRAMES[self] = Magic.framePointer();
        STATES[self] = NATIVE;
        long result = Magic.syscall(number, a0, a1, a2, a3, 0, 0);
        enterJava(self);
        return result;
    }

    /** Native with no Java frames: a thread about to start, or one about to exit. */
    static void outsideJava(int slot) {
        NATIVE_FRAMES[slot] = 0;
        STATES[slot] = NATIVE;
    }

    /** Back from native, once no collection is running. */
    static void enterJava(int self) {
        long state = address(STATES) + 4L * self;
        while (true) {
            Magic.exchangeInt(state, JAVA);
            if (!othersCollecting(self)) {
                return;
            }
            STATES[self] = NATIVE;
            awaitCollection();
        }
    }

    private static boolean othersCollecting(int self) {
        int stopping = STOPPING[0];
        return stopping != 0 && stopping != self + 1;
    }

    private static void awaitCollection() {
        int stopping;
        while ((stopping = STOPPING[0]) != 0) {
            Magic.syscall(FUTEX, address(STOPPING), FUTEX_WAIT_PRIVATE, stopping, 0, 0, 0);
        }
    }

    private static int stillRunning(int self) {
        for (int slot = 0; slot < STATES.length; slot++) {
            if (slot != self && STATES[slot] == JAVA && STOPPED_FRAMES[slot] == 0) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Puts PREEMPT in the thread's stack limit, so its next safepoint stops. A compare-and-exchange,
     * since the thread changes its own limit as it overflows and unwinds: a request that loses to
     * that is made again on the next pass.
     */
    private static void requestStop(int slot) {
        long block = Scheduler.block(slot);
        long limit = Magic.peekLong(block + BLOCK_STACK_LIMIT);
        if (limit != 0 && limit != PREEMPT) {
            Magic.pokeLong(block + BLOCK_STACK_LIMIT_SAVED, limit);
            Magic.compareAndExchangeLong(block + BLOCK_STACK_LIMIT, limit, PREEMPT);
        }
    }

    private static long address(int[] array) {
        return Magic.addressOf(array) + Tib.ARRAY_DATA;
    }
}
