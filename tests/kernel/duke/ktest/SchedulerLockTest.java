package duke.ktest;

import static duke.ktest.Assert.assertTrue;

import duke.kernel.Scheduler;
import duke.rt.Magic;

/**
 * Running out of stack inside the scheduler, as deep recursion that yields or gets preempted does:
 * the StackOverflowError mustn't leave the scheduler lock held. Checks are off under the lock, and
 * a thread switched back in must get its own limit back, wherever it resumes.
 */
final class SchedulerLockTest {

    private static boolean yieldBelow(long gap) {
        long limit = Magic.framePointer() - gap;
        Magic.setStackLimit(limit);
        Thread.yield();
        return Magic.stackLimit() == limit;
    }

    private static boolean sleepBelow(long gap) throws InterruptedException {
        long limit = Magic.framePointer() - gap;
        Magic.setStackLimit(limit);
        Thread.sleep(1);
        return Magic.stackLimit() == limit;
    }

    static void testStackOverflowPartwayIntoAYield() {
        int overflows = 0;
        int steps = 0;
        Magic.disableInterrupts();
        try {
            for (long gap = 0; gap <= 4096; gap += 8) {
                steps++;
                try {
                    if (!yieldBelow(gap)) {
                        throw new AssertionError("wrong stack limit after a yield " + gap + " bytes down");
                    }
                } catch (StackOverflowError e) {
                    overflows++;
                } finally {
                    Magic.resetStackLimit();
                }
                if (Scheduler.lockedHere()) {
                    throw new AssertionError("scheduler lock still held after a stack overflow " + gap + " bytes down");
                }
            }
        } finally {
            Magic.enableInterrupts();
        }
        assertTrue(overflows > 0 && overflows < steps, overflows + " of " + steps + " yields overflowed");
    }

    // Sleeping always switches threads, and the thread may come back on another CPU.
    static void testSleepingWithTheStackNearlyGone() throws InterruptedException {
        int overflows = 0;
        int steps = 0;
        Magic.disableInterrupts();
        try {
            for (long gap = 0; gap <= 4096; gap += 64) {
                steps++;
                try {
                    if (!sleepBelow(gap)) {
                        throw new AssertionError("wrong stack limit after a sleep " + gap + " bytes down");
                    }
                } catch (StackOverflowError e) {
                    overflows++;
                } finally {
                    Magic.resetStackLimit();
                }
                if (Scheduler.lockedHere()) {
                    throw new AssertionError("scheduler lock still held after a stack overflow " + gap + " bytes down");
                }
            }
        } finally {
            Magic.enableInterrupts();
        }
        assertTrue(overflows > 0 && overflows < steps, overflows + " of " + steps + " sleeps overflowed");
    }
}
