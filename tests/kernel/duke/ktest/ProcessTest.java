package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.user.Process;
import duke.kernel.user.SystemCalls;
import duke.kernel.user.UserMode;
import duke.ktest.UserModeTest.Code;
import duke.ktest.UserModeTest.Program;
import duke.rt.Magic;

final class ProcessTest {

    private static final int GP = 13;

    /** syscall(number) with no arguments, then exit with its result. */
    private static Code exitWithCall(int number) {
        return new Code().movEax(number).syscall().exitWithResult();
    }

    /** wait4(pid, status, options, NULL). */
    private static Code wait4(Code code, int pid, long status, int options) {
        return code.movEax(SystemCalls.WAIT4).movEdi(pid).movRsi(status).movEdx(options).b(0x45, 0x31, 0xD2).syscall();
    }

    /** mov [address], rax */
    private static Code storeRax(Code code, long address) {
        return code.b(0x48, 0x89, 0x04, 0x25).imm32(address);
    }

    /** Spins until the byte at {@code address} isn't zero: the kernel's go-ahead. */
    private static Code awaitByte(Code code, long address) {
        return code.b(0x80, 0x3C, 0x25).imm32(address).b(0).b(0xF3, 0x90).b(0x74, 0xF4);
    }

    private static long peekLong(byte[] page, int offset) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = v << 8 | page[offset + i] & 0xFF;
        }
        return v;
    }

    static void testKernelCollectsExitStatuses() throws InterruptedException {
        int before = Process.count();
        Process a = new Program().load(new Code().exit(3)).start(null);
        Process b = new Program().load(new Code().exit(4)).start(null);
        assertEquals(4, b.waitFor(), "b");
        assertEquals(3, a.waitFor(), "a");
        assertTrue(a.ended() && b.ended(), "both ended");
        assertEquals(before, Process.count(), "both collected");
    }

    static void testEachProcessSeesItsOwnPid() throws InterruptedException {
        Process a = new Program().load(exitWithCall(SystemCalls.GETPID)).start(null);
        Process b = new Program().load(exitWithCall(SystemCalls.GETPID)).start(null);
        assertEquals(a.pid, a.waitFor(), "a's getpid");
        assertEquals(b.pid, b.waitFor(), "b's getpid");
        assertTrue(a.pid != b.pid, "different pids");
        assertEquals(0, new Program().load(exitWithCall(SystemCalls.GETPPID)).start(null).waitFor(),
                "getppid when the kernel started it");
    }

    static void testFaultComesBackFromWaitFor() throws InterruptedException {
        Process p = new Program().load(new Code().b(0xF4)).start(null);
        try {
            p.waitFor();
            throw new AssertionError("no fault");
        } catch (UserMode.Fault f) {
            assertEquals(GP, f.vector, "hlt");
        }
        assertEquals(Process.SIGSEGV, p.waitStatus(), "wait status: SIGSEGV, as Linux raises for #GP");
    }

    static void testKillEndsASpinningProcess() throws InterruptedException {
        int before = Process.count();
        Process p = new Program().load(new Code().b(0xEB, 0xFE)).start(null);
        Thread.sleep(50);
        assertTrue(!p.ended(), "still spinning before the kill");
        p.kill();
        assertEquals(0, p.waitFor(), "waitFor");
        assertEquals(Process.SIGINT, p.waitStatus(), "wait status: SIGINT");
        assertEquals(before, Process.count(), "collected");
    }

    static void testKillEndsAReadFromTheConsole() throws InterruptedException {
        Program p = new Program();
        Process reader = p.load(new Code().call(SystemCalls.READ, 0, p.data, 64, p.data + 128).exit(0)).start(null);
        Thread.sleep(50);
        assertTrue(!reader.ended(), "still waiting for input before the kill");
        reader.kill();
        reader.waitFor();
        assertEquals(Process.SIGINT, reader.waitStatus(), "wait status: SIGINT, not the exit after read returns");
    }

    // The parent waits in wait4 for a child spinning in ring 3. Killing the parent ends both.
    static void testKillEndsTheWholeGroup() throws InterruptedException {
        int before = Process.count();
        Program p = new Program();
        Code c = new Code().call(SystemCalls.FORK, 0, 0, 0, p.data);
        c.b(0x48, 0x85, 0xC0).b(0x74, 0xFE);                                // test rax, rax; je $
        Process parent = p.load(wait4(c, -1, 0, 0).exitWithResult()).start(null);
        Thread.sleep(50);
        assertEquals(before + 2, Process.count(), "parent and child running");
        parent.kill();
        parent.waitFor();
        assertEquals(Process.SIGINT, parent.waitStatus(), "parent's wait status");
        for (int i = 0; Process.count() != before; i++) {
            assertTrue(i < 100, "the child ended too");
            Thread.sleep(10);
        }
    }

    static void testWait4WithNoChildren() throws InterruptedException {
        Program p = new Program();
        p.load(wait4(new Code(), -1, 0, 0).exitWithResult());
        assertEquals(-SystemCalls.ECHILD, p.start(null).waitFor(), "wait4(-1)");
    }

    static void testWait4ChecksItsArguments() throws InterruptedException {
        Program p = new Program();
        p.load(wait4(new Code(), 0, 0, 0).exitWithResult());
        assertEquals(-SystemCalls.EINVAL, p.start(null).waitFor(), "process group 0");
        p = new Program();
        p.load(wait4(new Code(), -1, 0, 4).exitWithResult());
        assertEquals(-SystemCalls.EINVAL, p.start(null).waitFor(), "WUNTRACED");
        p = new Program();
        p.load(wait4(new Code(), -1, p.base, 0).exitWithResult());
        assertEquals(-SystemCalls.EFAULT, p.start(null).waitFor(), "status in read-only code");
    }

    // The parent waits for the kernel's go-ahead, so its children exist before its wait4.
    private static long[] parentWaiting(Code child) throws InterruptedException {
        Program parent = new Program();
        Code code = awaitByte(new Code(), parent.data + 16);
        wait4(code, -1, parent.data, 0);
        storeRax(code, parent.data + 8);
        wait4(code, -1, 0, 0);
        storeRax(code, parent.data + 24).park(parent.data);
        Process[] c = new Process[1];
        long[] status = new long[1];
        byte[] page = parent.startAndCollect(code, null, status, p -> {
            c[0] = new Program().load(child).start(p);
            Magic.pokeByte(parent.kernelView(16), (byte) 1);
        });
        assertEquals(0, status[0], "the parent's exit");
        assertEquals(-SystemCalls.ECHILD, peekLong(page, 24), "a second wait4: nothing left");
        return new long[] {c[0].pid, peekLong(page, 8), (int) peekLong(page, 0)};
    }

    static void testParentCollectsItsChild() throws InterruptedException {
        int before = Process.count();
        long[] r = parentWaiting(new Code().exit(42));
        assertEquals(r[0], r[1], "wait4 returns the child's pid");
        assertEquals(42 << 8, r[2], "exit code in bits 8 to 15");
        r = parentWaiting(new Code().b(0xF4));
        assertEquals(r[0], r[1], "a child that faulted");
        assertEquals(Process.SIGSEGV, r[2], "killed by SIGSEGV");
        assertEquals(before, Process.count(), "no zombies left");
    }

    // The child spins until told to go, outliving its parent: an orphan, dropped when it ends.
    static void testWnohangAndOrphans() throws InterruptedException {
        int before = Process.count();
        Program parent = new Program();
        Program child = new Program();
        Code code = awaitByte(new Code(), parent.data + 16);
        parent.load(wait4(code, -1, 0, SystemCalls.WNOHANG).exitWithResult());
        child.load(awaitByte(new Code(), child.data).exit(9));
        Process p = parent.start(null);
        Process c = child.start(p);
        Magic.pokeByte(parent.kernelView(16), (byte) 1);
        assertEquals(0, p.waitFor(), "WNOHANG with a child still running");
        assertEquals(0, c.parentPid(), "an orphan once its parent ends");
        Magic.pokeByte(child.kernelView(0), (byte) 1);
        for (int tries = 0; !c.ended(); tries++) {
            assertTrue(tries < 500, "the orphan never ended");
            Thread.sleep(10);
        }
        assertEquals(before, Process.count(), "the orphan's gone from the table as soon as it ends");
        assertEquals(9, c.waitFor(), "the orphan's exit");
    }
}
