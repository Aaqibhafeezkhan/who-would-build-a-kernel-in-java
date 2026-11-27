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
    private static final int IOVECS = 3072;

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
        long relative = t.string("hello.txt");
        int fromConsole = t.call(SystemCalls.OPENAT, 1, relative, 0);
        int fromClosed = t.call(SystemCalls.OPENAT, 99, relative, 0);
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
        assertEquals(-SystemCalls.ENOTDIR, t.result(fromConsole), "openat relative to the console");
        assertEquals(-SystemCalls.EBADF, t.result(fromClosed), "openat relative to fd 99");
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

    static void testDupAndDup2() throws InterruptedException {
        FileTest t = new FileTest();
        long hello = t.string("/hello.txt");
        long buffer = t.p.data + BUFFER;
        int open = t.call(SystemCalls.OPEN, hello, 0, 0);
        int dup = t.call(SystemCalls.DUP, 3, 0, 0);
        int read3 = t.call(SystemCalls.READ, 3, buffer, 6);
        int read4 = t.call(SystemCalls.READ, 4, buffer + 10, 4);
        int pos3 = t.call(SystemCalls.LSEEK, 3, 0, 1);
        int same = t.call(SystemCalls.DUP2, 3, 3, 0);
        int onto1 = t.call(SystemCalls.DUP2, 4, 1, 0);
        int close3 = t.call(SystemCalls.CLOSE, 3, 0, 0);
        int close4 = t.call(SystemCalls.CLOSE, 4, 0, 0);
        int read1 = t.call(SystemCalls.READ, 1, buffer + 20, 7);
        int dupClosed = t.call(SystemCalls.DUP, 3, 0, 0);
        int dupNeg = t.call(SystemCalls.DUP, -1, 0, 0);
        int dupHigh = t.call(SystemCalls.DUP, 99, 0, 0);
        int dup2Closed = t.call(SystemCalls.DUP2, 3, 5, 0);
        int dup2SameClosed = t.call(SystemCalls.DUP2, 3, 3, 0);
        int dup2NegNew = t.call(SystemCalls.DUP2, 1, -1, 0);
        int dup2HighNew = t.call(SystemCalls.DUP2, 1, 64, 0);
        t.run();
        assertEquals(3, t.result(open), "open");
        assertEquals(4, t.result(dup), "dup picks the next free descriptor");
        assertEquals(6, t.result(read3), "read through 3");
        assertEquals("hello ", t.buffer(0, 6), "first bytes");
        assertEquals(4, t.result(read4), "read through 4");
        assertEquals("from", t.buffer(10, 4), "continues where 3 left off");
        assertEquals(10, t.result(pos3), "3's position moved with 4's read");
        assertEquals(3, t.result(same), "dup2 onto itself");
        assertEquals(1, t.result(onto1), "dup2 onto 1");
        assertEquals(0, t.result(close3), "close 3");
        assertEquals(0, t.result(close4), "close 4");
        assertEquals(7, t.result(read1), "still open on 1 after closing the originals");
        assertEquals(" FAT32\n", t.buffer(20, 7), "rest of the file through 1");
        assertEquals(-SystemCalls.EBADF, t.result(dupClosed), "dup closed fd");
        assertEquals(-SystemCalls.EBADF, t.result(dupNeg), "dup negative fd");
        assertEquals(-SystemCalls.EBADF, t.result(dupHigh), "dup out-of-range fd");
        assertEquals(-SystemCalls.EBADF, t.result(dup2Closed), "dup2 closed oldfd");
        assertEquals(-SystemCalls.EBADF, t.result(dup2SameClosed), "dup2 closed oldfd onto itself");
        assertEquals(-SystemCalls.EBADF, t.result(dup2NegNew), "dup2 negative newfd");
        assertEquals(-SystemCalls.EBADF, t.result(dup2HighNew), "dup2 out-of-range newfd");
    }

    static void testDup3() throws InterruptedException {
        FileTest t = new FileTest();
        long hello = t.string("/hello.txt");
        long buffer = t.p.data + BUFFER;
        int open = t.call(SystemCalls.OPEN, hello, 0, 0);
        // dup3 fd 3 onto fd 5, no flags
        int dup3NoFlag = t.call(SystemCalls.DUP3, 3, 5, 0);
        // read through fd 3, then through fd 5: they share the position
        int read3 = t.call(SystemCalls.READ, 3, buffer, 6);
        int read5 = t.call(SystemCalls.READ, 5, buffer + 10, 4);
        // dup3 fd 3 onto fd 6 with O_CLOEXEC
        int dup3Cloexec = t.call(SystemCalls.DUP3, 3, 6, SystemCalls.O_CLOEXEC);
        int read6 = t.call(SystemCalls.READ, 6, buffer + 20, 7);
        // dup3 onto itself: -EINVAL
        int ontoSelf = t.call(SystemCalls.DUP3, 3, 3, 0);
        int ontoSelfCloexec = t.call(SystemCalls.DUP3, 3, 3, SystemCalls.O_CLOEXEC);
        // unknown flag: -EINVAL
        int badFlag = t.call(SystemCalls.DUP3, 3, 7, 0x800);
        // closed oldfd
        int closedOld = t.call(SystemCalls.DUP3, 9, 7, 0);
        // negative newfd
        int negNew = t.call(SystemCalls.DUP3, 3, -1, 0);
        // out-of-range newfd
        int highNew = t.call(SystemCalls.DUP3, 3, 64, 0);
        t.run();
        assertEquals(3, t.result(open), "open");
        assertEquals(5, t.result(dup3NoFlag), "dup3 without flags");
        assertEquals(6, t.result(read3), "read through 3");
        assertEquals("hello ", t.buffer(0, 6), "first bytes");
        assertEquals(4, t.result(read5), "read through 5");
        assertEquals("from", t.buffer(10, 4), "continues where 3 left off");
        assertEquals(6, t.result(dup3Cloexec), "dup3 with O_CLOEXEC");
        assertEquals(7, t.result(read6), "read through 6");
        assertEquals(" FAT32\n", t.buffer(20, 7), "rest of the file through 6");
        assertEquals(-SystemCalls.EINVAL, t.result(ontoSelf), "dup3 onto itself");
        assertEquals(-SystemCalls.EINVAL, t.result(ontoSelfCloexec), "dup3 onto itself with O_CLOEXEC");
        assertEquals(-SystemCalls.EINVAL, t.result(badFlag), "unknown flag");
        assertEquals(-SystemCalls.EBADF, t.result(closedOld), "closed oldfd");
        assertEquals(-SystemCalls.EBADF, t.result(negNew), "negative newfd");
        assertEquals(-SystemCalls.EBADF, t.result(highNew), "out-of-range newfd");
    }

    static void testFcntlAccessModes() throws InterruptedException {
        FileTest t = new FileTest();
        int socket = t.call(41, 2, 1, 0); // socket(AF_INET, SOCK_STREAM, 0)
        int open = t.call(SystemCalls.OPEN, t.string("/hello.txt"), 0, 0);
        int pipe = t.call(SystemCalls.PIPE, t.p.data + STAT, 0, 0);
        int consoleMode = t.call(SystemCalls.FCNTL, 1, SystemCalls.F_GETFL, 0);
        int socketMode = t.call(SystemCalls.FCNTL, 3, SystemCalls.F_GETFL, 0);
        int fileMode = t.call(SystemCalls.FCNTL, 4, SystemCalls.F_GETFL, 0);
        int pipeReadMode = t.call(SystemCalls.FCNTL, 5, SystemCalls.F_GETFL, 0);
        int pipeWriteMode = t.call(SystemCalls.FCNTL, 6, SystemCalls.F_GETFL, 0);
        t.run();

        assertEquals(3, t.result(socket), "socket");
        assertEquals(4, t.result(open), "open");
        assertEquals(0, t.result(pipe), "pipe");
        assertEquals(SystemCalls.O_RDWR, t.result(consoleMode), "console access mode");
        assertEquals(SystemCalls.O_RDWR, t.result(socketMode), "socket access mode");
        assertEquals(SystemCalls.O_RDONLY, t.result(fileMode), "file access mode");
        assertEquals(SystemCalls.O_RDONLY, t.result(pipeReadMode), "pipe read-end access mode");
        assertEquals(SystemCalls.O_WRONLY, t.result(pipeWriteMode), "pipe write-end access mode");
    }

    static void testFcntlDescriptorFlags() throws InterruptedException {
        FileTest t = new FileTest();
        int open = t.call(SystemCalls.OPEN, t.string("/hello.txt"), 0, 0);
        int initialFlags = t.call(SystemCalls.FCNTL, 3, SystemCalls.F_GETFD, 0);
        int setCloseOnExec = t.call(SystemCalls.FCNTL, 3, SystemCalls.F_SETFD, SystemCalls.FD_CLOEXEC);
        int descriptorFlags = t.call(SystemCalls.FCNTL, 3, SystemCalls.F_GETFD, 0);
        t.run();

        assertEquals(3, t.result(open), "open");
        assertEquals(0, t.result(initialFlags), "FD_CLOEXEC starts clear");
        assertEquals(0, t.result(setCloseOnExec), "F_SETFD");
        assertEquals(SystemCalls.FD_CLOEXEC, t.result(descriptorFlags), "F_GETFD after F_SETFD");
    }

    static void testFcntlDuplication() throws InterruptedException {
        FileTest t = new FileTest();
        int open = t.call(SystemCalls.OPEN, t.string("/hello.txt"), 0, 0);
        int pipe = t.call(SystemCalls.PIPE, t.p.data + STAT, 0, 0);
        int duplicate = t.call(SystemCalls.FCNTL, 3, SystemCalls.F_DUPFD, 4);
        int duplicateMode = t.call(SystemCalls.FCNTL, 6, SystemCalls.F_GETFL, 0);
        int duplicateFlags = t.call(SystemCalls.FCNTL, 6, SystemCalls.F_GETFD, 0);
        int duplicateCloexec = t.call(SystemCalls.FCNTL, 3, SystemCalls.F_DUPFD_CLOEXEC, 7);
        int duplicateCloexecMode = t.call(SystemCalls.FCNTL, 7, SystemCalls.F_GETFL, 0);
        int duplicateCloexecFlags = t.call(SystemCalls.FCNTL, 7, SystemCalls.F_GETFD, 0);
        int duplicateWriteEnd = t.call(SystemCalls.FCNTL, 5, SystemCalls.F_DUPFD, 8);
        int duplicateWriteMode = t.call(SystemCalls.FCNTL, 8, SystemCalls.F_GETFL, 0);
        t.run();

        assertEquals(3, t.result(open), "open");
        assertEquals(0, t.result(pipe), "pipe");
        assertEquals(6, t.result(duplicate), "F_DUPFD finds the lowest free descriptor above the minimum");
        assertEquals(SystemCalls.O_RDONLY, t.result(duplicateMode), "F_DUPFD keeps access mode");
        assertEquals(0, t.result(duplicateFlags), "F_DUPFD clears FD_CLOEXEC");
        assertEquals(7, t.result(duplicateCloexec), "F_DUPFD_CLOEXEC");
        assertEquals(SystemCalls.O_RDONLY, t.result(duplicateCloexecMode), "F_DUPFD_CLOEXEC keeps access mode");
        assertEquals(SystemCalls.FD_CLOEXEC, t.result(duplicateCloexecFlags), "F_DUPFD_CLOEXEC sets FD_CLOEXEC");
        assertEquals(8, t.result(duplicateWriteEnd), "F_DUPFD on pipe write end");
        assertEquals(SystemCalls.O_WRONLY, t.result(duplicateWriteMode), "duplicated pipe keeps write access mode");
    }

    static void testFcntlErrors() throws InterruptedException {
        FileTest t = new FileTest();
        int open = t.call(SystemCalls.OPEN, t.string("/hello.txt"), 0, 0);
        int unknownCommand = t.call(SystemCalls.FCNTL, 3, 999, 0);
        int closedDescriptor = t.call(SystemCalls.FCNTL, 99, SystemCalls.F_GETFL, 0);
        int closedUnknownCommand = t.call(SystemCalls.FCNTL, 99, 999, 0);
        int closedDuplicate = t.call(SystemCalls.FCNTL, 99, SystemCalls.F_DUPFD, 0);
        int setFlagsOnClosedDescriptor = t.call(SystemCalls.FCNTL, 99, SystemCalls.F_SETFD, 0);
        int negativeMinimum = t.call(SystemCalls.FCNTL, 3, SystemCalls.F_DUPFD, -1);
        int highMinimum = t.call(SystemCalls.FCNTL, 3, SystemCalls.F_DUPFD, 64);
        t.run();

        assertEquals(3, t.result(open), "open");
        assertEquals(-SystemCalls.EINVAL, t.result(unknownCommand), "unknown fcntl command");
        assertEquals(-SystemCalls.EBADF, t.result(closedDescriptor), "closed descriptor");
        assertEquals(-SystemCalls.EBADF, t.result(closedUnknownCommand), "closed descriptor takes precedence");
        assertEquals(-SystemCalls.EBADF, t.result(closedDuplicate), "F_DUPFD on a closed descriptor");
        assertEquals(-SystemCalls.EBADF, t.result(setFlagsOnClosedDescriptor), "F_SETFD on closed descriptor");
        assertEquals(-SystemCalls.EINVAL, t.result(negativeMinimum), "negative F_DUPFD minimum");
        assertEquals(-SystemCalls.EINVAL, t.result(highMinimum), "out-of-range F_DUPFD minimum");
    }

    /** iovecs at IOVECS as (offset into BUFFER, length) pairs, or an address of its own if the offset's negative. */
    private long iovecs(int at, long... vectors) {
        for (int i = 0; i < vectors.length; i += 2) {
            long base = vectors[i] < 0 ? -vectors[i] : p.data + BUFFER + vectors[i];
            Magic.pokeLong(p.kernelView(IOVECS + at + 8 * i), base);
            Magic.pokeLong(p.kernelView(IOVECS + at + 8 * i + 8), vectors[i + 1]);
        }
        return p.data + IOVECS + at;
    }

    // Three buffers across the end of hello.txt's 17 bytes, with an empty one between.
    static void testReadv() throws InterruptedException {
        FileTest t = new FileTest();
        long hello = t.string("/hello.txt");
        long good = t.iovecs(0, 0, 6, 10, 0, 20, 6, 30, 20);
        long readOnly = t.iovecs(64, 0, 6, -t.p.base, 6);
        int open = t.call(SystemCalls.OPEN, hello, 0, 0);
        int refused = t.call(SystemCalls.READV, 3, readOnly, 2);
        int read = t.call(SystemCalls.READV, 3, good, 4);
        int eof = t.call(SystemCalls.READV, 3, good, 4);
        int negative = t.call(SystemCalls.READV, 3, good, -1);
        int closed = t.call(SystemCalls.READV, 9, good, 4);
        t.run();
        assertEquals(3, t.result(open), "open");
        assertEquals(-SystemCalls.EFAULT, t.result(refused), "a buffer it can't write");
        assertEquals(17, t.result(read), "the whole file, from the start");
        assertEquals("hello ", t.buffer(0, 6), "first buffer");
        assertEquals("from F", t.buffer(20, 6), "second, past the empty one");
        assertEquals("AT32\n", t.buffer(30, 5), "third, cut short by the end");
        assertEquals(0, t.result(eof), "then EOF");
        assertEquals(-SystemCalls.EINVAL, t.result(negative), "a negative count");
        assertEquals(-SystemCalls.EBADF, t.result(closed), "a closed descriptor");
    }
}
