package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.user.Process;
import duke.kernel.user.SystemCalls;
import duke.kernel.user.UserMode;
import duke.ktest.UserModeTest.Code;
import duke.ktest.UserModeTest.Program;
import duke.rt.Magic;

final class CloneTest {

    private static final int FLAGS = SystemCalls.CLONE_THREAD_FLAGS | SystemCalls.CLONE_SYSVSEM
            | SystemCalls.CLONE_PARENT_SETTID | SystemCalls.CLONE_CHILD_CLEARTID;
    private static final int FUTEX_WAIT = SystemCalls.FUTEX_WAIT | SystemCalls.FUTEX_PRIVATE_FLAG;
    private static final int PF = 14;

    private static long peekLong(byte[] page, int offset) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = v << 8 | page[offset + i] & 0xFF;
        }
        return v;
    }

    /** clone(flags, stack, parentTid, childTid, tls), the result left in rax for both threads. */
    private static Code clone(Code c, int flags, long stack, long parentTid, long childTid, long tls) {
        return c.movEax(SystemCalls.CLONE).b(0x48, 0xBF).imm64(flags).movRsi(stack).b(0x48, 0xBA).imm64(parentTid)
                .b(0x49, 0xBA).imm64(childTid).b(0x49, 0xB8).imm64(tls).syscall();
    }

    /**
     * jmp over the new thread's code to the first thread's, which clones and has the new thread
     * jump back to its code when clone returns 0, then carries on with {@code after}.
     */
    private static Code withThread(Program p, Code thread, int flags, long childTid, Code after) {
        Code c = new Code().b(0xE9).imm32(0);
        int threadAt = c.position();
        for (int i = 0; i < thread.size; i++) {
            c.b(thread.bytes[i]);
        }
        int start = c.position();
        c.at(1).imm32(start - 5).at(start);
        clone(c, flags, p.data + 2048, p.data + 8, childTid, 0);
        c.b(0x48, 0x85, 0xC0).jcc(0x4, threadAt);                           // test rax, rax; je thread
        c.b(0x48, 0x89, 0x04, 0x25).imm32(p.data);                          // mov [data], rax
        for (int i = 0; i < after.size; i++) {
            c.b(after.bytes[i]);
        }
        return c;
    }

    // The new thread shares memory, gets its own tid, and wakes the first when it exits.
    static void testThreadRunsAndIsJoined() throws InterruptedException {
        Program p = new Program();
        Magic.pokeInt(p.kernelView(16), 1);
        // mov byte [data + 24], 42; exit(0): just this thread.
        Code thread = new Code().b(0xC6, 0x04, 0x25).imm32(p.data + 24).b(42).exit(0);
        // Join: futex-wait while [data + 16] is 1, until CLONE_CHILD_CLEARTID zeroes it.
        Code join = new Code();
        int loop = join.position();
        join.b(0x45, 0x31, 0xD2).call(SystemCalls.FUTEX, p.data + 16, FUTEX_WAIT, 1, p.data + 40)
                .b(0x8B, 0x04, 0x25).imm32(p.data + 16).b(0x85, 0xC0).jcc(0x5, loop)
                .call(SystemCalls.GETTID, 0, 0, 0, p.data + 32);
        Code c = withThread(p, thread, FLAGS, p.data + 16, join);
        long[] status = new long[1];
        int[] pid = new int[1];
        byte[] page = p.startAndCollect(c.park(p.data), null, status, started -> pid[0] = started.pid);
        long tid = peekLong(page, 0);
        assertEquals(0, status[0], "the process's exit");
        assertTrue(tid > pid[0], "clone returned a new tid: " + tid + ", pid " + pid[0]);
        assertEquals(tid, (int) peekLong(page, 8), "CLONE_PARENT_SETTID stored it");
        assertEquals(42, page[24], "the thread's write is in the shared memory");
        assertEquals(0, (int) peekLong(page, 16), "CLONE_CHILD_CLEARTID zeroed the word");
        assertEquals(pid[0], (int) peekLong(page, 32), "the first thread's tid is the pid");
    }

    // exit_group from one thread ends a thread spinning in ring 3, with exit_group's status.
    static void testExitGroupEndsEveryThread() throws InterruptedException {
        Program p = new Program();
        Code spin = new Code().b(0xEB, 0xFE);
        Code exitGroup = new Code().movEdi(7).movEax(SystemCalls.EXIT_GROUP).syscall();
        Process process = p.load(withThread(p, spin, FLAGS, p.data + 16, exitGroup)).start(null);
        assertEquals(7, process.waitFor(), "exit_group's status");
    }

    // A thread that exits leaves the others running: the process ends with the last one.
    static void testExitEndsOnlyItsThread() throws InterruptedException {
        Program p = new Program();
        Magic.pokeLong(p.kernelView(80), 0);
        Magic.pokeLong(p.kernelView(88), 50_000_000);
        // The new thread outlives the first: a 50 ms futex timeout, then exit(5).
        Code thread = new Code().b(0x49, 0xBA).imm64(p.data + 80)
                .call(SystemCalls.FUTEX, p.data + 64, FUTEX_WAIT, 0, p.data + 100).exit(5);
        Code first = new Code().exit(3);
        Process process = p.load(withThread(p, thread, FLAGS, p.data + 16, first)).start(null);
        assertEquals(5, process.waitFor(), "the last thread's status");
    }

    // A fault in one thread ends the process, even with another spinning.
    static void testFaultEndsEveryThread() throws InterruptedException {
        Program p = new Program();
        // mov byte [base + PAGE], 1: the unmapped page between code and data.
        Code fault = new Code().b(0xC6, 0x04, 0x25).imm32(p.base + 4096).b(1);
        Code spin = new Code().b(0xEB, 0xFE);
        Process process = p.load(withThread(p, fault, FLAGS, p.data + 16, spin)).start(null);
        try {
            long status = process.waitFor();
            throw new AssertionError("exited with " + status + " instead of faulting");
        } catch (UserMode.Fault f) {
            assertEquals(PF, f.vector, "the thread's page fault");
        }
    }

    // Ctrl-C reaches every thread.
    static void testKillEndsEveryThread() throws InterruptedException {
        Program p = new Program();
        Code spin = new Code().b(0xEB, 0xFE);
        Process process = p.load(withThread(p, spin, FLAGS, p.data + 16, new Code().b(0xEB, 0xFE))).start(null);
        Thread.sleep(50);
        process.kill();
        assertEquals(0, process.waitFor(), "killed");
        assertEquals(Process.SIGINT, process.waitStatus(), "the wait status says SIGINT");
    }

    static void testCloneRejectsAProcessClone() throws InterruptedException {
        Program p = new Program();
        // Without CLONE_THREAD it's fork's job.
        Code c = clone(new Code(), SystemCalls.CLONE_VM, p.data + 2048, 0, 0, 0).exitWithResult();
        assertEquals(-SystemCalls.EINVAL, p.load(c).start(null).waitFor(), "clone without CLONE_THREAD");
    }

    static void testFutexWaitChecksTheWordAndTimesOut() {
        Program p = new Program();
        Magic.pokeInt(p.kernelView(64), 5);
        Magic.pokeLong(p.kernelView(80), 0);
        Magic.pokeLong(p.kernelView(88), 20_000_000);
        // futex(data + 64, WAIT, 4, NULL): the word isn't 4, so it doesn't sleep.
        // futex(data + 64, WAIT, 5, {0, 20 ms}): it is, and nobody wakes it.
        Code c = new Code().b(0x45, 0x31, 0xD2).call(SystemCalls.FUTEX, p.data + 64, FUTEX_WAIT, 4, p.data + 100)
                .b(0x49, 0xBA).imm64(p.data + 80).call(SystemCalls.FUTEX, p.data + 64, FUTEX_WAIT, 5, p.data + 108)
                .call(SystemCalls.FUTEX, p.data + 64, SystemCalls.FUTEX_WAKE, 1, p.data + 116).exit(0);
        try {
            assertEquals(0, p.load(c).run(), "exit");
            assertEquals(-SystemCalls.EAGAIN, Magic.peekLong(p.kernelView(100)), "a word that's changed");
            assertEquals(-SystemCalls.ETIMEDOUT, Magic.peekLong(p.kernelView(108)), "nobody woke it");
            assertEquals(0, Magic.peekLong(p.kernelView(116)), "a wake with nobody waiting");
        } finally {
            p.free();
        }
    }
}
