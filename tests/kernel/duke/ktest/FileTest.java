package duke.ktest;

import static duke.ktest.Assert.assertEquals;

import duke.kernel.user.SystemCalls;
import duke.ktest.UserModeTest.Code;
import duke.ktest.UserModeTest.Program;
import duke.rt.Magic;

// The FAT disks KernelTests attaches hold FatImage.sample(), with hello.txt ("hello from FAT32\n") and docs/.
final class FileTest {

    private static final int STRINGS = 512;
    private static final int BUFFER = 1024;
    private static final int STAT = 2048;

    private final Program p = new Program();
    private final Code code = new Code();
    private int results;
    private int strings = STRINGS;
    private byte[] page;

    /** A NUL-terminated string in the data page, by its user address. */
    private long string(String s) {
        long at = p.data + strings;
        for (int i = 0; i < s.length(); i++) {
            Magic.pokeByte(p.kernelView(strings + i), (byte) s.charAt(i));
        }
        strings += s.length() + 1;
        return at;
    }

    /** Adds a system call to the program; its result is the {@code n}th long of the data page. */
    private int call(int number, long a0, long a1, long a2) {
        code.call(number, a0, a1, a2, p.data + 8L * results);
        return results++;
    }

    private long peek(int offset, int bytes) {
        long v = 0;
        for (int i = bytes - 1; i >= 0; i--) {
            v = v << 8 | page[offset + i] & 0xFF;
        }
        return v;
    }

    private long result(int n) {
        return peek(8 * n, 8);
    }

    private String buffer(int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append((char) page[BUFFER + offset + i]);
        }
        return sb.toString();
    }

    private void run() throws InterruptedException {
        long[] status = new long[1];
        page = p.startAndCollect(code.park(p.data), null, status);
        assertEquals(0, status[0], "exit");
    }

    static void testOpenReadSeekStatClose() throws InterruptedException {
        FileTest t = new FileTest();
        long hello = t.string("/hello.txt");
        long buffer = t.p.data + BUFFER;
        int open = t.call(SystemCalls.OPEN, hello, 0, 0);
        int read = t.call(SystemCalls.READ, 3, buffer, 64);
        int eof = t.call(SystemCalls.READ, 3, buffer + 100, 64);
        int seek = t.call(SystemCalls.LSEEK, 3, 6, 0);
        int readFrom = t.call(SystemCalls.READ, 3, buffer + 200, 4);
        int end = t.call(SystemCalls.LSEEK, 3, 0, 2);
        int stat = t.call(SystemCalls.FSTAT, 3, t.p.data + STAT, 0);
        int dir = t.call(SystemCalls.OPEN, t.string("/docs"), 0, 0);
        int readDir = t.call(SystemCalls.READ, 4, buffer, 1);
        int close = t.call(SystemCalls.CLOSE, 3, 0, 0);
        int closeAgain = t.call(SystemCalls.CLOSE, 3, 0, 0);
        int reopen = t.call(SystemCalls.OPEN, t.string("hello.txt"), 0, 0);
        int at = t.call(SystemCalls.OPENAT, -100, hello, 0);
        t.run();
        assertEquals(3, t.result(open), "the first free descriptor");
        assertEquals(17, t.result(read), "bytes read");
        assertEquals("hello from FAT32\n", t.buffer(0, 17), "contents");
        assertEquals(0, t.result(eof), "end of file");
        assertEquals(6, t.result(seek), "SEEK_SET");
        assertEquals("from", t.buffer(200, 4), "read after seeking");
        assertEquals(17, t.result(end), "SEEK_END");
        assertEquals(0, t.result(stat), "fstat");
        assertEquals(0100444, (int) t.peek(STAT + 24, 4), "st_mode: a read-only regular file");
        assertEquals(17, t.peek(STAT + 48, 8), "st_size");
        assertEquals(4, t.result(dir), "a directory opens");
        assertEquals(-SystemCalls.EISDIR, t.result(readDir), "but doesn't read");
        assertEquals(0, t.result(close), "close");
        assertEquals(-SystemCalls.EBADF, t.result(closeAgain), "close twice");
        assertEquals(3, t.result(reopen), "a relative path, into the freed descriptor");
        assertEquals(5, t.result(at), "openat(AT_FDCWD)");
    }

    static void testErrors() throws InterruptedException {
        FileTest t = new FileTest();
        long hello = t.string("/hello.txt");
        long buffer = t.p.data + BUFFER;
        int missing = t.call(SystemCalls.OPEN, t.string("/nope"), 0, 0);
        int missingDir = t.call(SystemCalls.OPEN, t.string("/nope/hello.txt"), 0, 0);
        int writeOnly = t.call(SystemCalls.OPEN, hello, 1, 0);
        int create = t.call(SystemCalls.OPEN, hello, SystemCalls.O_CREAT, 0);
        int kernel = t.call(SystemCalls.OPEN, Magic.addressOf(t), 0, 0);
        int notDir = t.call(SystemCalls.OPEN, hello, SystemCalls.O_DIRECTORY, 0);
        int otherDir = t.call(SystemCalls.OPENAT, 3, hello, 0);
        int seekConsole = t.call(SystemCalls.LSEEK, 1, 0, 0);
        int badFd = t.call(SystemCalls.READ, 99, buffer, 1);
        int file = t.call(SystemCalls.OPEN, hello, 0, 0);
        int writeFile = t.call(SystemCalls.WRITE, 3, buffer, 1);
        int readIntoCode = t.call(SystemCalls.READ, 3, t.p.base, 1);
        int badWhence = t.call(SystemCalls.LSEEK, 3, 0, 7);
        int beforeStart = t.call(SystemCalls.LSEEK, 3, -1, 0);
        int statConsole = t.call(SystemCalls.FSTAT, 1, t.p.data + STAT, 0);
        t.run();
        assertEquals(-SystemCalls.ENOENT, t.result(missing), "no such file");
        assertEquals(-SystemCalls.ENOENT, t.result(missingDir), "no such directory");
        assertEquals(-SystemCalls.EROFS, t.result(writeOnly), "O_WRONLY");
        assertEquals(-SystemCalls.EROFS, t.result(create), "O_CREAT");
        assertEquals(-SystemCalls.EFAULT, t.result(kernel), "a path in kernel memory");
        assertEquals(-SystemCalls.ENOTDIR, t.result(notDir), "O_DIRECTORY on a file");
        assertEquals(-SystemCalls.EINVAL, t.result(otherDir), "openat relative to a descriptor");
        assertEquals(-SystemCalls.ESPIPE, t.result(seekConsole), "lseek on the console");
        assertEquals(-SystemCalls.EBADF, t.result(badFd), "fd 99");
        assertEquals(3, t.result(file), "open");
        assertEquals(-SystemCalls.EBADF, t.result(writeFile), "write to a read-only file");
        assertEquals(-SystemCalls.EFAULT, t.result(readIntoCode), "read into read-only code");
        assertEquals(-SystemCalls.EINVAL, t.result(badWhence), "whence 7");
        assertEquals(-SystemCalls.EINVAL, t.result(beforeStart), "before the start");
        assertEquals(0020620, (int) t.peek(STAT + 24, 4), "the console is a character device");
        assertEquals(0, t.result(statConsole), "fstat on the console");
    }

    static void testDescriptorsRunOut() throws InterruptedException {
        FileTest t = new FileTest();
        long hello = t.string("/hello.txt");
        int last = t.results++;
        // mov r12d, 70; again: open; mov [last], rax; dec r12d; jnz again
        t.code.b(0x41, 0xBC).imm32(70);
        int again = t.code.position();
        t.code.call(SystemCalls.OPEN, hello, 0, 0, t.p.data + 8L * last).b(0x41, 0xFF, 0xCC).jcc(0x5, again);
        t.run();
        assertEquals(-SystemCalls.EMFILE, t.result(last), "the 62nd open past 0 to 2");
    }

    // Each process has its own table: a descriptor one opened means nothing in another.
    static void testTablesArePerProcess() throws InterruptedException {
        FileTest a = new FileTest();
        a.call(SystemCalls.OPEN, a.string("/hello.txt"), 0, 0);
        a.run();
        FileTest b = new FileTest();
        int read = b.call(SystemCalls.READ, 3, b.p.data + BUFFER, 1);
        b.run();
        assertEquals(3, a.result(0), "a's open");
        assertEquals(-SystemCalls.EBADF, b.result(read), "b's fd 3");
    }
}
