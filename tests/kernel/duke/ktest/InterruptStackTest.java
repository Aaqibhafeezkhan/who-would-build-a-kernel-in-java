package duke.ktest;

import static duke.ktest.Assert.assertEquals;

import duke.kernel.x86.Interrupts;
import duke.rt.Magic;

/**
 * A breakpoint a few hundred bytes above the stack limit: the CPU's frame alone takes the handler
 * below it, so its prologue would throw StackOverflowError inside the interrupt.
 */
final class InterruptStackTest {

    private static final long PREEMPT = -1;
    private static int breakpoints;
    private static boolean handlerRequests;
    private static final long[] SEEN = new long[4];

    private static void nearTheLimit(long limit) {
        if (Magic.framePointer() - limit > 256) {
            nearTheLimit(limit);
            return;
        }
        // Nothing here may make a call: its prologue would take a pending request.
        Magic.breakpoint();
        SEEN[0] = Magic.stackLimit();
        Magic.requestPreemption();
        Magic.breakpoint();
        SEEN[1] = Magic.stackLimit();
        Magic.cancelPreemption();
        handlerRequests = true;
        Magic.breakpoint();
        handlerRequests = false;
        SEEN[2] = Magic.stackLimit();
        Magic.cancelPreemption();
        SEEN[3] = Magic.stackLimit();
    }

    static void testBreakpointJustAboveTheStackLimit() {
        Interrupts.register(3, frame -> {
            breakpoints++;
            if (handlerRequests) {
                Magic.requestPreemption();
            }
        });
        Magic.disableInterrupts();
        try {
            Magic.cancelPreemption();
            long limit = Magic.stackLimit();
            nearTheLimit(limit);
            assertEquals(3, breakpoints, "every breakpoint handled");
            assertEquals(limit, SEEN[0], "the limit put back");
            assertEquals(PREEMPT, SEEN[1], "a request from before still pending");
            assertEquals(PREEMPT, SEEN[2], "the handler's request kept");
            assertEquals(limit, SEEN[3], "behind it, the real limit");
        } finally {
            Magic.enableInterrupts();
            Interrupts.register(3, null);
        }
    }
}
