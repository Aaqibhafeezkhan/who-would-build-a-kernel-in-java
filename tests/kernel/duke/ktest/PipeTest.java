package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.user.Process;
import duke.kernel.user.SystemCalls;
import duke.ktest.UserModeTest.Code;
import duke.ktest.UserModeTest.Program;
import duke.rt.Magic;

final class PipeTest {

    // In the data page: three pairs of descriptors, then results, a message and read buffers.
    private static final int FDS = 0;
    private static final int RESULTS = 64;
    private static final int MESSAGE = 256;
    private static final int BUFFER = 512;
    private static final int BIG = 1024;
    private static final int BIG_BYTES = 2048;

    private static long peekLong(byte[] page, int offset) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = v << 8 | page[offset + i] & 0xFF;
        }
        return v;
    }

    private static String text(byte[] page, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append((char) (page[offset + i] & 0xFF));
        }
        return sb.toString();
    }

    private static long result(byte[] page, int n) {
        return peekLong(page, RESULTS + 8 * n);
    }

    /** syscall(number, the int at fdAddress, a1, a2), then mov [result], rax. */
    private static Code fdCall(Code c, int number, long fdAddress, long a1, long a2, long result) {
        return c.movEax(number).b(0x8B, 0x3C, 0x25).imm32(fdAddress).movRsi(a1).b(0x48, 0xBA).imm64(a2).syscall()
                .b(0x48, 0x89, 0x04, 0x25).imm32(result);
    }

    private static void message(Program p, String s) {
        for (int i = 0; i < s.length(); i++) {
            Magic.pokeByte(p.kernelView(MESSAGE + i), (byte) s.charAt(i));
        }
    }

    static void testReadWriteEofAndEpipe() throws InterruptedException {
        Program p = new Program();
        long d = p.data;
        long r = d + RESULTS;
        message(p, "hello");
        Code c = new Code().call(SystemCalls.PIPE, d + FDS, 0, 0, r);
        fdCall(c, SystemCalls.WRITE, d + FDS + 4, d + MESSAGE, 5, r + 8);
        fdCall(c, SystemCalls.READ, d + FDS, d + BUFFER, 64, r + 16);
        fdCall(c, SystemCalls.CLOSE, d + FDS + 4, 0, 0, r + 24);
        fdCall(c, SystemCalls.READ, d + FDS, d + BUFFER + 64, 64, r + 32);
        // A second pipe, its read end closed.
        c.call(SystemCalls.PIPE2, d + FDS + 8, SystemCalls.O_CLOEXEC, 0, r + 40);
        fdCall(c, SystemCalls.CLOSE, d + FDS + 8, 0, 0, r + 48);
        fdCall(c, SystemCalls.WRITE, d + FDS + 12, d + MESSAGE, 5, r + 56);
        fdCall(c, SystemCalls.FSTAT, d + FDS, d + BIG, 0, r + 64);
        c.call(SystemCalls.PIPE2, d + FDS + 16, 0x800, 0, r + 72);
        long[] status = new long[1];
        byte[] page = p.startAndCollect(c.park(d), null, status);

        assertEquals(0, result(page, 0), "pipe");
        assertEquals(3, page[FDS], "the read end on the lowest free descriptor");
        assertEquals(4, page[FDS + 4], "then the write end");
        assertEquals(5, result(page, 1), "written");
        assertEquals(5, result(page, 2), "read back");
        assertEquals("hello", text(page, BUFFER, 5), "what was written");
        assertEquals(0, result(page, 3), "write end closed");
        assertEquals(0, result(page, 4), "EOF once no write end is left");
        assertEquals(0, result(page, 5), "pipe2 with O_CLOEXEC");
        assertEquals(0, result(page, 6), "read end closed");
        assertEquals(-SystemCalls.EPIPE, result(page, 7), "EPIPE once no read end is left");
        assertEquals(0, result(page, 8), "fstat");
        assertEquals(0010600, page[BIG + 24] & 0xFF | (page[BIG + 25] & 0xFF) << 8, "a FIFO");
        assertEquals(-SystemCalls.EINVAL, result(page, 9), "no O_NONBLOCK yet");
        assertEquals(0, status[0], "exit status");
    }

    // The write end stays open while any descriptor still refers to it.
    static void testDupKeepsTheEndOpen() throws InterruptedException {
        Program p = new Program();
        long d = p.data;
        long r = d + RESULTS;
        message(p, "dup'd");
        Code c = new Code().call(SystemCalls.PIPE, d + FDS, 0, 0, r);
        fdCall(c, SystemCalls.DUP, d + FDS + 4, 0, 0, r + 8);
        fdCall(c, SystemCalls.CLOSE, d + FDS + 4, 0, 0, r + 16);
        fdCall(c, SystemCalls.WRITE, r + 8, d + MESSAGE, 5, r + 24);
        fdCall(c, SystemCalls.READ, d + FDS, d + BUFFER, 64, r + 32);
        fdCall(c, SystemCalls.CLOSE, r + 8, 0, 0, r + 40);
        fdCall(c, SystemCalls.READ, d + FDS, d + BUFFER, 64, r + 48);
        long[] status = new long[1];
        byte[] page = p.startAndCollect(c.park(d), null, status);

        assertEquals(5, result(page, 1), "dup");
        assertEquals(5, result(page, 3), "written through the copy after the original closed");
        assertEquals(5, result(page, 4), "and read");
        assertEquals("dup'd", text(page, BUFFER, 5), "what was written");
        assertEquals(0, result(page, 5), "the copy closed");
        assertEquals(0, result(page, 6), "EOF now");
    }

    /**
     * A forked child writes 80 chunks of 2 KiB, more than the pipe holds, and exits without closing
     * anything. The parent reads until EOF, which only comes once the child's exit closed its copy.
     */
    static void testForkedWriterThroughAFullPipe() throws InterruptedException {
        Program p = new Program();
        long d = p.data;
        long r = d + RESULTS;
        Code c = new Code().b(0xE9).imm32(0);
        int child = c.position();
        c.b(0x41, 0xBC).imm32(80);                                          // mov r12d, 80
        int write = c.position();
        fdCall(c, SystemCalls.WRITE, d + FDS + 4, d + BIG, BIG_BYTES, r + 32);
        c.b(0x41, 0xFF, 0xCC).jcc(0x5, write);                              // dec r12d; jnz write
        c.exit(0);
        int start = c.position();
        c.at(1).imm32(start - 5).at(start);
        c.call(SystemCalls.PIPE, d + FDS, 0, 0, r);
        c.call(SystemCalls.FORK, 0, 0, 0, r + 8);
        c.b(0x48, 0x85, 0xC0).jcc(0x4, child);                              // test rax, rax; je child
        fdCall(c, SystemCalls.CLOSE, d + FDS + 4, 0, 0, r + 16);
        c.b(0x4D, 0x31, 0xED);                                              // xor r13, r13
        int read = c.position();
        fdCall(c, SystemCalls.READ, d + FDS, d + BIG, BIG_BYTES, r + 24);
        c.b(0x48, 0x85, 0xC0, 0x0F, 0x8E).imm32(0);                         // test rax, rax; jle done
        int jle = c.position();
        c.b(0x49, 0x01, 0xC5, 0xE9).imm32(read - (c.position() + 4));       // add r13, rax; jmp read
        int done = c.position();
        c.at(jle - 4).imm32(done - jle).at(done);
        c.b(0x4C, 0x89, 0x2C, 0x25).imm32(r + 40);                          // mov [r + 40], r13
        c.call(SystemCalls.WAIT4, -1, 0, 0, r + 48);
        long[] status = new long[1];
        byte[] page = p.startAndCollect(c.park(d), null, status);

        assertTrue(result(page, 1) > 0, "forked: " + result(page, 1));
        assertEquals(0, result(page, 2), "the parent's write end closed");
        assertEquals(0, result(page, 3), "the last read is EOF");
        assertEquals(80L * BIG_BYTES, result(page, 5), "every byte through");
        assertEquals(result(page, 1), result(page, 6), "the child reaped");
    }

    // The process's own write end is open, so only the kill ends the read.
    static void testKillWakesABlockedRead() throws InterruptedException {
        Program p = new Program();
        long d = p.data;
        Code c = new Code().call(SystemCalls.PIPE, d + FDS, 0, 0, d + RESULTS);
        fdCall(c, SystemCalls.READ, d + FDS, d + BUFFER, 64, d + RESULTS + 8).exit(1);
        p.load(c);
        Process process = p.start(null);
        Thread.sleep(50);
        assertTrue(!process.ended(), "still reading");
        process.kill();
        assertEquals(0, process.waitFor(), "killed");
        assertEquals(Process.SIGINT, process.waitStatus(), "by Ctrl-C");
    }
}
