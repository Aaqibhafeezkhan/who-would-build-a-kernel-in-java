package duke.ktest;

import static duke.ktest.Assert.assertTrue;

import duke.rt.Heap;
import duke.rt.Magic;

/**
 * Running out of stack partway into an allocation, as deep recursion does: the StackOverflowError
 * mustn't leave the heap lock held. A leaked lock goes unnoticed while the thread stays on its
 * CPU, since that CPU counts as holding it, and hangs the kernel the first time it moves.
 */
final class HeapLockTest {

    private static byte[] allocateBelow(long gap) {
        Magic.setStackLimit(Magic.framePointer() - gap);
        return new byte[16];
    }

    private static void collectBelow(long gap) {
        Magic.setStackLimit(Magic.framePointer() - gap);
        Heap.collect();
    }

    static void testStackOverflowPartwayIntoAnAllocation() {
        int overflows = 0;
        int steps = 0;
        Magic.disableInterrupts();
        try {
            // From an overflow in the first prologue down to none at all, every place in between.
            for (long gap = 0; gap <= 4096; gap += 8) {
                steps++;
                try {
                    allocateBelow(gap);
                } catch (StackOverflowError e) {
                    overflows++;
                } finally {
                    Magic.resetStackLimit();
                }
                if (Heap.allocating()) {
                    throw new AssertionError("heap lock still held after a stack overflow " + gap + " bytes down");
                }
            }
        } finally {
            Magic.enableInterrupts();
        }
        assertTrue(overflows > 0 && overflows < steps, overflows + " of " + steps + " allocations overflowed");
    }

    // With the checks off under the lock, a collection started at the limit runs below it.
    static void testCollectingWithTheStackNearlyGone() {
        long before = Heap.collections();
        int overflows = 0;
        Magic.disableInterrupts();
        try {
            for (long gap = 0; gap <= 512; gap += 32) {
                try {
                    collectBelow(gap);
                } catch (StackOverflowError e) {
                    overflows++;
                } finally {
                    Magic.resetStackLimit();
                }
                if (Heap.allocating()) {
                    throw new AssertionError("heap lock still held after a stack overflow " + gap + " bytes down");
                }
            }
        } finally {
            Magic.enableInterrupts();
        }
        assertTrue(Heap.collections() > before, "no collection got under way");
        assertTrue(overflows > 0, "never overflowed");
    }
}
