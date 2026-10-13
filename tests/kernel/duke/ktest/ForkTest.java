package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.user.Process;
import duke.kernel.user.SystemCalls;
import duke.ktest.UserModeTest.Code;
import duke.ktest.UserModeTest.Program;
import duke.rt.Magic;

// /bin/hello is on the disks KernelTests attaches (tools/FatImage.java).
final class ForkTest {

    private static long peekLong(byte[] page, int offset) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = v << 8 | page[offset + i] & 0xFF;
        }
        return v;
    }

    private static void string(Program p, int offset, String s) {
        for (int i = 0; i < s.length(); i++) {
            Magic.pokeByte(p.kernelView(offset + i), (byte) s.charAt(i));
        }
    }

    /**
     * jmp over the child's code to the parent's, which sets rbx and FS, forks, and goes to the
     * child's code when fork returns 0. The parent then waits for the child and parks.
     */
    private static byte[] forkAndWait(Program p, Code child, long[] status) throws InterruptedException {
        Code c = new Code().b(0xE9).imm32(0);
        int childAt = c.position();
        for (int i = 0; i < child.size; i++) {
            c.b(child.bytes[i]);
        }
        int start = c.position();
        c.at(1).imm32(start - 5).at(start);
        c.b(0x48, 0xC7, 0xC3).imm32(7);                                    // mov rbx, 7
        c.archPrctl(SystemCalls.ARCH_SET_FS, p.data + 256);
        c.call(SystemCalls.FORK, 0, 0, 0, p.data);
        c.b(0x48, 0x85, 0xC0).jcc(0x4, childAt);                            // test rax, rax; je child
        c.call(SystemCalls.WAIT4, -1, p.data + 8, 0, p.data + 16);
        c.b(0x48, 0x89, 0x1C, 0x25).imm32(p.data + 24);                     // mov [data + 24], rbx
        c.b(0x64, 0x48, 0x8B, 0x04, 0x25).imm32(0).b(0x48, 0x89, 0x04, 0x25).imm32(p.data + 32);
        return p.startAndCollect(c.park(p.data), null, status);
    }

    // The child sees the parent's registers, FS base and memory, and its own writes stay its own.
    static void testChildCarriesOnFromFork() throws InterruptedException {
        Program p = new Program();
        Magic.pokeLong(p.kernelView(256), 42);
        // mov byte [data + 512], 1; mov rax, fs:[0]; add rax, rbx; exit(rax)
        Code child = new Code().b(0xC6, 0x04, 0x25).imm32(p.data + 512).b(1)
                .b(0x64, 0x48, 0x8B, 0x04, 0x25).imm32(0).b(0x48, 0x01, 0xD8).exitWithResult();
        long[] status = new long[1];
        byte[] page = forkAndWait(p, child, status);
        long pid = peekLong(page, 0);
        assertEquals(0, status[0], "the parent's exit");
        assertTrue(pid > 0, "fork returned the child's pid: " + pid);
        assertEquals(pid, peekLong(page, 16), "wait4 collected it");
        assertEquals((42 + 7) << 8, (int) peekLong(page, 8), "the child saw fs:[0] = 42 and rbx = 7");
        assertEquals(7, peekLong(page, 24), "the parent's rbx after fork");
        assertEquals(42, peekLong(page, 32), "the parent's FS base after fork");
        assertEquals(0, page[512], "the child's write stayed in the child");
    }

    static void testChildExecsHello() throws InterruptedException {
        Program p = new Program();
        string(p, 600, "/bin/hello");
        Magic.pokeLong(p.kernelView(700), p.data + 600);
        // execve("/bin/hello", {"/bin/hello", NULL}, NULL); if it returns, exit(99)
        Code child = new Code().call(SystemCalls.EXECVE, p.data + 600, p.data + 700, 0, p.data + 40).exit(99);
        long[] status = new long[1];
        byte[] page = forkAndWait(p, child, status);
        assertEquals(peekLong(page, 0), peekLong(page, 16), "wait4 collected the child");
        assertEquals(0, (int) peekLong(page, 8), "/bin/hello exited 0, so the exec happened");
    }

    static void testExecveFailuresReturnToTheCaller() throws InterruptedException {
        Program p = new Program();
        string(p, 600, "/nope");
        string(p, 620, "/hello.txt");
        string(p, 640, "/docs");
        string(p, 660, "/bin/hello");
        Magic.pokeLong(p.kernelView(700), Magic.addressOf(p));
        Code c = new Code()
                .call(SystemCalls.EXECVE, p.data + 600, 0, 0, p.data)
                .call(SystemCalls.EXECVE, p.data + 620, 0, 0, p.data + 8)
                .call(SystemCalls.EXECVE, p.data + 640, 0, 0, p.data + 16)
                .call(SystemCalls.EXECVE, Magic.addressOf(p), 0, 0, p.data + 24)
                .call(SystemCalls.EXECVE, p.data + 660, p.data + 700, 0, p.data + 32);
        long[] status = new long[1];
        byte[] page = p.startAndCollect(c.park(p.data), null, status);
        assertEquals(-SystemCalls.ENOENT, peekLong(page, 0), "no such file");
        assertEquals(-SystemCalls.ENOEXEC, peekLong(page, 8), "not an executable");
        assertEquals(-SystemCalls.EACCES, peekLong(page, 16), "a directory");
        assertEquals(-SystemCalls.EFAULT, peekLong(page, 24), "a path in kernel memory");
        assertEquals(-SystemCalls.EFAULT, peekLong(page, 32), "argv pointing at kernel memory");
    }

    // The process and whoever waits for it carry over; only the image changes.
    static void testExecReplacesTheImage() throws InterruptedException {
        Program p = new Program();
        string(p, 600, "/bin/hello");
        Magic.pokeLong(p.kernelView(700), p.data + 600);
        p.load(new Code().call(SystemCalls.EXECVE, p.data + 600, p.data + 700, 0, p.data).exit(99));
        Process proc = p.start(null);
        assertEquals(0, proc.waitFor(), "the status is hello's, not 99");
    }
}
