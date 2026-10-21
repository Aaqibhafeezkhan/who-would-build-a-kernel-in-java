package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.Scheduler;
import duke.kernel.mm.AddressSpace;
import duke.kernel.mm.KernelAddressSpace;
import duke.kernel.mm.PageTable;
import duke.kernel.mm.PhysicalMemory;
import duke.kernel.user.Process;
import duke.kernel.user.SystemCalls;
import duke.kernel.user.UserMode;
import duke.rt.Heap;
import duke.rt.Magic;
import duke.rt.Tib;
import java.util.function.Consumer;

final class UserModeTest {

    private static final long PAGE = PhysicalMemory.PAGE_SIZE;
    private static final int GP = 13;
    private static final int PF = 14;

    /** Where {@link Code#park} flags that it's waiting, in the data page. */
    static final int PARKED = 4000;
    private static final long MAP_FLOOR = 0x1000_0000L;
    private static final long MAP_TOP = 0x10_0000_0000L;

    /** Where every program's code goes: each has its own address space. */
    private static final long BASE = 0x40_0000;

    /** A read-only code page at BASE, an unmapped page, then a writable data and stack page. */
    static final class Program {
        final long base = BASE;
        final long data = BASE + 2 * PAGE;
        final AddressSpace space = new AddressSpace();
        private final long codeFrame;
        private final long dataFrame;

        Program() {
            codeFrame = space.allocate(base, 0);
            long nx = KernelAddressSpace.noExecuteSupported() ? PageTable.NO_EXECUTE : 0;
            dataFrame = space.allocate(data, PageTable.WRITABLE | nx);
            space.setMapRange(MAP_FLOOR, MAP_TOP);
        }

        Program load(Code code) {
            Magic.copyMemory(PhysicalMemory.toVirtual(codeFrame), Magic.addressOf(code.bytes) + Tib.ARRAY_DATA, code.size);
            return this;
        }

        /** The data page through the direct map. */
        long kernelView(int offset) {
            return PhysicalMemory.toVirtual(dataFrame) + offset;
        }

        long run() {
            return UserMode.run(space, base, data + PAGE);
        }

        /** As a process, which frees the space when it ends: don't {@link #free} it as well. */
        Process start(Process parent) {
            return Process.start("test", parent, space, base, data + PAGE);
        }

        /**
         * Runs code that ends with {@link Code#park}, copies the data page once it gets there, lets
         * it exit, and returns the copy: the process's memory is gone once it ends.
         */
        byte[] startAndCollect(Code code, Process parent, long[] status) throws InterruptedException {
            return startAndCollect(code, parent, status, p -> { });
        }

        /** The same, calling {@code started} with the process as soon as it's started. */
        byte[] startAndCollect(Code code, Process parent, long[] status, Consumer<Process> started)
                throws InterruptedException {
            load(code);
            Process p = start(parent);
            started.accept(p);
            for (int tries = 0; Magic.peekByte(kernelView(PARKED)) == 0; tries++) {
                if (p.ended() || tries >= 1000) {
                    throw new AssertionError("never parked");
                }
                Thread.sleep(5);
            }
            byte[] copy = new byte[(int) PAGE];
            Magic.copyMemory(Magic.addressOf(copy) + Tib.ARRAY_DATA, kernelView(0), PAGE);
            Magic.pokeByte(kernelView(PARKED + 1), (byte) 1);
            status[0] = p.waitFor();
            return copy;
        }

        void free() {
            space.free();
        }
    }

    /** Just enough of an assembler for these tests. */
    static final class Code {
        final byte[] bytes = new byte[1024];
        int size;

        Code b(int... values) {
            for (int v : values) {
                bytes[size++] = (byte) v;
            }
            return this;
        }

        Code imm32(long v) {
            for (int i = 0; i < 4; i++) {
                b((int) (v >>> 8 * i));
            }
            return this;
        }

        Code imm64(long v) {
            return imm32(v).imm32(v >>> 32);
        }

        Code movEax(int v) {
            return b(0xB8).imm32(v);
        }

        Code movEdi(int v) {
            return b(0xBF).imm32(v);
        }

        Code movEdx(int v) {
            return b(0xBA).imm32(v);
        }

        Code movRsi(long v) {
            return b(0x48, 0xBE).imm64(v);
        }

        Code syscall() {
            return b(0x0F, 0x05);
        }

        /** mmap(0, length, prot, flags, fd, 0). */
        Code mmap(long length, int prot, int flags, int fd) {
            return movEax(SystemCalls.MMAP).b(0x31, 0xFF).movRsi(length).movEdx(prot)
                    .b(0x41, 0xBA).imm32(flags).b(0x49, 0xC7, 0xC0).imm32(fd).b(0x45, 0x31, 0xC9).syscall();
        }

        /** Sets the data page's PARKED byte, waits for the one after it, then exits 0. */
        Code park(long data) {
            b(0xC6, 0x04, 0x25).imm32(data + PARKED).b(1);
            int spin = position();
            return b(0xF3, 0x90).b(0x80, 0x3C, 0x25).imm32(data + PARKED + 1).b(0).jcc(0x4, spin).exit(0);
        }

        Code exit(int status) {
            return movEdi(status).movEax(SystemCalls.EXIT).syscall();
        }

        /** exit(eax): the last system call's result becomes the status. */
        Code exitWithResult() {
            return b(0x89, 0xC7).movEax(SystemCalls.EXIT).syscall();
        }

        Code write(int fd, long buffer, int count) {
            return movEax(SystemCalls.WRITE).movEdi(fd).movRsi(buffer).movEdx(count).syscall();
        }

        int position() {
            return size;
        }

        /** j<cc> rel32 to an earlier {@link #position}: 0x4 for je, 0x5 for jne. */
        Code jcc(int cc, int target) {
            int rel = target - (size + 6);
            return b(0x0F, 0x80 | cc).imm32(rel);
        }

        /** syscall(number, a0, a1, a2), then mov [result], rax. */
        Code call(int number, long a0, long a1, long a2, long result) {
            return movEax(number).b(0x48, 0xBF).imm64(a0).movRsi(a1).b(0x48, 0xBA).imm64(a2).syscall()
                    .b(0x48, 0x89, 0x04, 0x25).imm32(result);
        }

        Code archPrctl(int code, long address) {
            return movEax(SystemCalls.ARCH_PRCTL).movEdi(code).movRsi(address).syscall();
        }

        /** movq xmm{n}, rbx */
        Code xmmFromRbx(int n) {
            return b(0x66, n < 8 ? 0x48 : 0x4C, 0x0F, 0x6E, 0xC3 | (n & 7) << 3);
        }

        /** movq rax, xmm{n} */
        Code raxFromXmm(int n) {
            return b(0x66, n < 8 ? 0x48 : 0x4C, 0x0F, 0x7E, 0xC0 | (n & 7) << 3);
        }

        Code at(int offset) {
            size = offset;
            return this;
        }

        Code ascii(String s) {
            for (int i = 0; i < s.length(); i++) {
                b(s.charAt(i));
            }
            return this;
        }
    }

    private static long run(Code code) {
        Program p = new Program().load(code);
        try {
            return p.run();
        } finally {
            p.free();
        }
    }

    private static UserMode.Fault fault(Program p) {
        try {
            long status = p.run();
            throw new AssertionError("exited with " + status + " instead of faulting");
        } catch (UserMode.Fault f) {
            return f;
        } finally {
            p.free();
        }
    }

    static void testExitStatusComesBack() {
        assertEquals(42, run(new Code().exit(42)), "exit(42)");
        assertEquals(-1, run(new Code().exit(-1)), "exit(-1)");
    }

    static void testWriteReachesTheConsole() {
        String message = "ring 3: hello from user mode\n";
        Program p = new Program();
        p.load(new Code().write(1, p.base + 64, message.length()).exitWithResult().at(64).ascii(message));
        try {
            assertEquals(message.length(), p.run(), "bytes written");
        } finally {
            p.free();
        }
    }
    static void testUnameWritesExpectedFields() {
        Program p = new Program();
        int offset = 64;

        p.load(new Code()
                .movEax(SystemCalls.UNAME)
                .movEdi((int) (p.data + offset))
                .syscall()
                .exitWithResult());

        try {
            assertEquals(0, p.run(), "uname");

            // sysname: "Duke\0"
            assertEquals('D', Magic.peekByte(p.kernelView(offset)) & 0xFF, "sysname");
            assertEquals('u', Magic.peekByte(p.kernelView(offset + 1)) & 0xFF, "sysname");
            assertEquals('k', Magic.peekByte(p.kernelView(offset + 2)) & 0xFF, "sysname");
            assertEquals('e', Magic.peekByte(p.kernelView(offset + 3)) & 0xFF, "sysname");
            assertEquals(0, Magic.peekByte(p.kernelView(offset + 4)) & 0xFF,
                    "sysname terminator");

            // machine: "x86_64\0", the fifth 65-byte field.
            int machineOffset = offset + 4 * 65;
            assertEquals('x', Magic.peekByte(p.kernelView(machineOffset)) & 0xFF, "machine");
            assertEquals('8', Magic.peekByte(p.kernelView(machineOffset + 1)) & 0xFF, "machine");
            assertEquals('6', Magic.peekByte(p.kernelView(machineOffset + 2)) & 0xFF, "machine");
            assertEquals('_', Magic.peekByte(p.kernelView(machineOffset + 3)) & 0xFF, "machine");
            assertEquals('6', Magic.peekByte(p.kernelView(machineOffset + 4)) & 0xFF, "machine");
            assertEquals('4', Magic.peekByte(p.kernelView(machineOffset + 5)) & 0xFF, "machine");
            assertEquals(0, Magic.peekByte(p.kernelView(machineOffset + 6)) & 0xFF,
                    "machine terminator");
        } finally {
            p.free();
        }
    }

    static void testClockGettimeWritesMonotonicTime() {
        Program p = new Program();
        int offset = 64;

        p.load(new Code()
            .movEax(SystemCalls.CLOCK_GETTIME)
            .movEdi(SystemCalls.CLOCK_MONOTONIC)
            .movRsi(p.data + offset)
            .syscall()
            .exitWithResult());

        try {
            assertEquals(0, p.run(), "clock_gettime");
            long seconds = Magic.peekLong(p.kernelView(offset));
            long nanos = Magic.peekLong(p.kernelView(offset + 8));

            assertTrue(seconds >= 0, "nonnegative seconds");
            assertTrue(nanos >= 0 && nanos < 1_000_000_000L,
                "nanoseconds in range");
            assertEquals(0, nanos % 1_000_000L, "millisecond precision");
        } finally {
            p.free();
        }
    }

    static void testClockGettimeRejectsUnsupportedClock() {
        assertEquals(-SystemCalls.EINVAL,
            run(new Code()
                .movEax(SystemCalls.CLOCK_GETTIME)
                .movEdi(0)
                .movRsi(BASE + 64)
                .syscall()
                .exitWithResult()),
            "unsupported clock");
    }

    static void testNanosleepRejectsInvalidTimespec() {
        Program p = new Program();
        int offset = 64;

        // Negative seconds.
        Magic.pokeLong(p.kernelView(offset), -1);
        Magic.pokeLong(p.kernelView(offset + 8), 0);

        p.load(new Code()
            .movEax(SystemCalls.NANOSLEEP)
            .movEdi((int)(p.data + offset))
            .b(0x31, 0xF6) // xor esi, esi: remaining = NULL
            .syscall()
            .exitWithResult());

        try {
            assertEquals(-SystemCalls.EINVAL, p.run(), "negative seconds");
        } finally {
            p.free();
        }

        // tv_nsec must be less than one second.
        Program q = new Program();
        Magic.pokeLong(q.kernelView(offset), 0);
        Magic.pokeLong(q.kernelView(offset + 8), 1_000_000_000L);

        q.load(new Code()
            .movEax(SystemCalls.NANOSLEEP)
            .movEdi((int)(q.data + offset))
            .b(0x31, 0xF6)
            .syscall()
            .exitWithResult());

        try {
            assertEquals(-SystemCalls.EINVAL, q.run(), "nanoseconds out of range");
        } finally {
            q.free();
        }
    }

    static void testGetcwdWritesRootAndNul() {
        Program p = new Program();
        int offset = 64;
        p.load(new Code()
                .movEax(SystemCalls.GETCWD)
                .movEdi((int) (p.data + offset))
                .movRsi(2)
                .syscall()
                .exitWithResult());

        try {
            assertEquals(2, p.run(), "getcwd bytes written");
            assertEquals('/', Magic.peekByte(p.kernelView(offset)) & 0xFF, "getcwd root");
            assertEquals(0, Magic.peekByte(p.kernelView(offset + 1)) & 0xFF, "getcwd terminator");
        } finally {
            p.free();
        }

        assertEquals(-SystemCalls.ERANGE,
                run(new Code().movEax(SystemCalls.GETCWD).movEdi((int) (BASE + 64)).movRsi(1)
                        .syscall().exitWithResult()),
                "getcwd with a size of one");
        assertEquals(-SystemCalls.EFAULT,
                run(new Code().movEax(SystemCalls.GETCWD).movEdi((int) (BASE + 64)).movRsi(2)
                        .syscall().exitWithResult()),
                "getcwd with a read-only buffer");
    }

    static void testUnameRejectsReadOnlyBuffer() {
        Code code = new Code()
                .movEax(SystemCalls.UNAME)
                .movEdi((int) (BASE + 64))
                .syscall()
                .exitWithResult();

        assertEquals(
                -SystemCalls.EFAULT,
                run(code),
                "uname with read-only buffer");
    }

    static void testWriteChecksItsArguments() {
        Program p = new Program();
        long kernel = Magic.addressOf(p);
        assertEquals(-SystemCalls.EBADF, run(new Code().write(3, 0, 0).exitWithResult()), "fd 3");
        assertEquals(-SystemCalls.EFAULT, run(new Code().write(1, kernel, 1).exitWithResult()), "kernel buffer");
        assertEquals(-SystemCalls.EFAULT, run(new Code().write(1, p.base + PAGE, 1).exitWithResult()), "unmapped page");
        assertEquals(-SystemCalls.EFAULT, run(new Code().write(1, -1, 1).exitWithResult()), "top of memory");
        p.load(new Code().write(1, p.data + PAGE - 4, 8).exitWithResult());
        assertEquals(-SystemCalls.EFAULT, p.run(), "runs off the end of the data page");
        p.free();
        assertEquals(0, run(new Code().write(1, 0, 0).exitWithResult()), "nothing to write");
    }

    static void testUnknownSystemCalls() {
        assertEquals(-SystemCalls.ENOSYS, run(new Code().movEax(9999).syscall().exitWithResult()), "9999");
        // mov rax, 1 << 32 | WRITE: the whole register is the number.
        Code wide = new Code().b(0x48, 0xB8).imm64(1L << 32 | SystemCalls.WRITE).syscall().exitWithResult();
        assertEquals(-SystemCalls.ENOSYS, run(wide), "a write number in the high half");
    }

    static void testSystemCallsKeepRegisters() {
        // mov ebx, 3; mov r12d, 4; syscall 9999; mov edi, ebx; add edi, r12d; exit
        Code code = new Code().b(0xBB).imm32(3).b(0x41, 0xBC).imm32(4).movEax(9999).syscall()
                .b(0x89, 0xDF).b(0x44, 0x01, 0xE7).movEax(SystemCalls.EXIT).syscall();
        assertEquals(7, run(code), "rbx + r12 after a system call");
    }

    // GS holds the kernel's per-CPU block; ring 3 loading a selector into it mustn't break the next system call.
    static void testUserCanLoadGs() {
        // mov eax, USER_DATA; mov gs, eax; exit(9)
        assertEquals(9, run(new Code().movEax(0x33).b(0x8E, 0xE8).exit(9)), "exit after loading gs");
    }

    static void testPrivilegedInstructionsFault() {
        Program p = new Program();
        p.load(new Code().b(0xF4));
        UserMode.Fault f = fault(p);
        assertEquals(GP, f.vector, "hlt in ring 3");
        assertEquals(p.base, f.rip, "at the hlt");
    }

    static void testKernelMemoryIsOffLimits() {
        long kernel = Magic.addressOf(new int[1]);
        Program p = new Program();
        // mov rax, [kernel]
        p.load(new Code().b(0x48, 0xA1).imm64(kernel));
        UserMode.Fault f = fault(p);
        assertEquals(PF, f.vector, "page fault");
        assertEquals(kernel, f.address, "cr2");
        assertEquals(5, f.errorCode, "present, read, user");
    }

    static void testCodeIsReadOnly() {
        Program p = new Program();
        // mov byte [base], 0
        p.load(new Code().b(0xC6, 0x04, 0x25).imm32(p.base).b(0));
        UserMode.Fault f = fault(p);
        assertEquals(PF, f.vector, "page fault");
        assertEquals(p.base, f.address, "cr2");
        assertEquals(7, f.errorCode, "present, write, user");
    }

    /** Sets data[1], spins until the kernel sets data[0], then exits with {@code status}. */
    private static Program spinner(int status) {
        Program p = new Program();
        // mov byte [data + 1], 1; spin: cmp byte [data], 0; pause; je spin; exit(status)
        return p.load(new Code().b(0xC6, 0x04, 0x25).imm32(p.data + 1).b(1)
                .b(0x80, 0x3C, 0x25).imm32(p.data).b(0).b(0xF3, 0x90).b(0x74, 0xF4).exit(status));
    }

    private static void awaitStart(Program spinner, String name) throws InterruptedException {
        for (int tries = 0; Magic.peekByte(spinner.kernelView(1)) == 0; tries++) {
            assertTrue(tries < 500, name + " never started");
            Thread.sleep(10);
        }
    }

    // Both mapped before either runs: on one shared table, the writer would land in the reader's page.
    static void testAddressSpacesAreSeparate() {
        Program writer = new Program();
        Program reader = new Program();
        try {
            // mov byte [data], 7; exit(0)
            writer.load(new Code().b(0xC6, 0x04, 0x25).imm32(writer.data).b(7).exit(0));
            // movzx edi, byte [data]; exit(edi)
            reader.load(new Code().b(0x0F, 0xB6, 0x3C, 0x25).imm32(reader.data).movEax(SystemCalls.EXIT).syscall());
            assertEquals(0, writer.run(), "writer");
            assertEquals(7, Magic.peekByte(writer.kernelView(0)), "the writer's page");
            assertEquals(0, Magic.peekByte(reader.kernelView(0)), "the reader's page");
            assertEquals(0, reader.run(), "what the reader sees");
        } finally {
            writer.free();
            reader.free();
        }
    }

    static void testFreeReturnsEveryFrame() {
        AddressSpace space = new AddressSpace();
        long before = PhysicalMemory.freeFrames();
        // Far enough apart to need their own PDPT, PD and PT.
        space.allocate(BASE, 0);
        space.allocate(BASE + PAGE, PageTable.WRITABLE);
        space.allocate(0x7FFF_FFFF_F000L, PageTable.WRITABLE);
        assertTrue(PhysicalMemory.freeFrames() < before - 3, "pages and tables allocated");
        space.free();
        assertEquals(before + 1, PhysicalMemory.freeFrames(), "everything back, root included");
    }

    static void testLaterKernelMappingsReachEveryProcess() {
        AddressSpace space = new AddressSpace();
        // A PML4 slot nothing else uses, so no process could have copied its entry yet.
        long virtual = 0xffff_e000_0000_0000L;
        long frame = PhysicalMemory.allocateZeroed();
        KernelAddressSpace.table().map(virtual, frame, PageTable.WRITABLE);
        Magic.pokeLong(PhysicalMemory.toVirtual(frame), 99);
        Magic.disableInterrupts();
        Scheduler.setAddressSpace(space);
        long seen = Magic.peekLong(virtual);
        Scheduler.setAddressSpace(null);
        Magic.enableInterrupts();
        KernelAddressSpace.table().unmap(virtual);
        PhysicalMemory.free(frame);
        space.free();
        assertEquals(99, seen, "read through the process's table");
    }

    static void testFaultingProcessDiesAlone() throws InterruptedException {
        Program survivor = spinner(5);
        long[] status = {-1};
        Thread survivorThread = new Thread(() -> status[0] = survivor.run());
        survivorThread.start();
        awaitStart(survivor, "the survivor");
        int[] vector = {-1};
        Thread crasherThread = new Thread(() -> {
            Program crasher = new Program();
            try {
                vector[0] = fault(crasher.load(new Code().b(0xF4))).vector;
            } catch (AssertionError e) {
                vector[0] = -2;
            }
        });
        crasherThread.start();
        crasherThread.join();
        assertEquals(GP, vector[0], "the crasher's fault");
        assertTrue(survivorThread.isAlive(), "the survivor is still running");
        Magic.pokeByte(survivor.kernelView(0), (byte) 1);
        survivorThread.join();
        survivor.free();
        assertEquals(5, status[0], "the survivor's exit status");
    }

    private static final int ANONYMOUS = SystemCalls.MAP_PRIVATE | SystemCalls.MAP_ANONYMOUS;
    private static final int READ_WRITE = SystemCalls.PROT_READ | SystemCalls.PROT_WRITE;

    // A gigabyte on a machine with 256 MiB: only the two pages touched get memory.
    static void testMmapIsPagedInOnTouch() {
        Code code = new Code().mmap(1 << 30, READ_WRITE, ANONYMOUS | SystemCalls.MAP_NORESERVE, -1)
                .b(0xC6, 0x80).imm32((1 << 30) - 4096).b(7)        // mov byte [rax + 1 GiB - 4096], 7
                .b(0x0F, 0xB6, 0xB8).imm32((1 << 30) - 4096)       // movzx edi, byte [rax + 1 GiB - 4096]
                .b(0x03, 0x38)                                     // add edi, [rax]
                .movEax(SystemCalls.EXIT).syscall();
        assertEquals(7, run(code), "the last page's write plus the first page's zero");
    }

    static void testMmapPlacesMappingsApart() {
        // mov rbx, rax; second mmap; sub rax, rbx; exit(eax): the second one sits right below the first.
        Code code = new Code().mmap(8192, READ_WRITE, ANONYMOUS, -1).b(0x48, 0x89, 0xC3)
                .mmap(4096, READ_WRITE, ANONYMOUS, -1).b(0x48, 0x29, 0xD8).exitWithResult();
        assertEquals(-4096, run(code), "second minus first");
    }

    static void testMmapRejectsWhatItDoesntSupport() {
        assertEquals(-SystemCalls.EINVAL, run(new Code().mmap(4096, READ_WRITE, 1 | SystemCalls.MAP_ANONYMOUS, -1)
                .exitWithResult()), "shared");
        assertEquals(-SystemCalls.EINVAL, run(new Code().mmap(4096, READ_WRITE, SystemCalls.MAP_PRIVATE, 3)
                .exitWithResult()), "a file");
        assertEquals(-SystemCalls.EINVAL, run(new Code().mmap(4096, READ_WRITE, ANONYMOUS | SystemCalls.MAP_FIXED, -1)
                .exitWithResult()), "fixed");
        assertEquals(-SystemCalls.EINVAL, run(new Code().mmap(0, READ_WRITE, ANONYMOUS, -1).exitWithResult()), "empty");
        assertEquals(-SystemCalls.EINVAL, run(new Code().mmap(4096, 0, ANONYMOUS, -1).exitWithResult()), "PROT_NONE");
        assertEquals(-SystemCalls.ENOMEM, run(new Code().mmap(1L << 40, READ_WRITE, ANONYMOUS, -1).exitWithResult()),
                "a terabyte");
    }

    static void testReadOnlyMappingsFaultOnWrite() {
        // mov byte [rax], 1
        UserMode.Fault f = fault(new Program().load(new Code().mmap(4096, SystemCalls.PROT_READ, ANONYMOUS, -1)
                .b(0xC6, 0x00, 0x01)));
        assertEquals(PF, f.vector, "page fault");
        assertEquals(7, f.errorCode, "present, write, user: paged in by the read-only fault, then refused");
    }

    static void testArchPrctl() {
        Program p = new Program();
        Magic.pokeLong(p.kernelView(64 + 8), 77);
        // arch_prctl(SET_FS, data + 64); arch_prctl(GET_FS, data + 128); mov rax, fs:[8]; exit(eax)
        p.load(new Code().archPrctl(SystemCalls.ARCH_SET_FS, p.data + 64).archPrctl(SystemCalls.ARCH_GET_FS, p.data + 128)
                .b(0x64, 0x48, 0x8B, 0x04, 0x25).imm32(8).exitWithResult());
        try {
            assertEquals(77, p.run(), "read through fs");
            assertEquals(p.data + 64, Magic.peekLong(p.kernelView(128)), "ARCH_GET_FS");
        } finally {
            p.free();
        }
        long kernel = Magic.addressOf(p);
        assertEquals(-SystemCalls.EPERM, run(new Code().archPrctl(SystemCalls.ARCH_SET_GS, kernel).exitWithResult()),
                "a kernel address");
        assertEquals(-SystemCalls.EINVAL, run(new Code().archPrctl(0x1005, 0).exitWithResult()), "an unknown code");
        assertEquals(-SystemCalls.EFAULT, run(new Code().archPrctl(SystemCalls.ARCH_GET_GS, BASE).exitWithResult()),
                "into read-only code");
        // The first program on this thread set FS; the next one starts from 0 all the same.
        long data = BASE + 2 * PAGE;
        assertEquals(0, run(new Code().archPrctl(SystemCalls.ARCH_GET_FS, data + 8)
                .b(0x48, 0x8B, 0x04, 0x25).imm32(data + 8).exitWithResult()), "FS base of a fresh program");
    }

    // Limine leaves CR4.OSFXSR clear, which makes every SSE instruction raise #UD.
    static void testSseWorksInRingThree() {
        // mov rbx, 5; movq xmm3, rbx; movq rax, xmm3; exit(eax)
        assertEquals(5, run(new Code().b(0x48, 0xC7, 0xC3).imm32(5).xmmFromRbx(3).raxFromXmm(3).exitWithResult()),
                "through xmm3");
    }

    // Thread i keeps i's value in all sixteen xmm registers and at fs:[8] and gs:[8], with bases
    // that differ per thread, while more threads than CPUs are preempted and moved around. Any
    // state that isn't saved and restored with the thread shows up as another thread's value.
    static void testUserStateFollowsTheThread() throws InterruptedException {
        int count = 6;
        Program[] programs = new Program[count];
        long[] results = new long[count];
        Thread[] threads = new Thread[count];
        for (int i = 0; i < count; i++) {
            Program p = new Program();
            long value = 0x0101_0101_0101L * (i + 1);
            long fs = p.data + 64 * (i + 1);
            long gs = fs + 32;
            Magic.pokeLong(p.kernelView(64 * (i + 1) + 8), value);
            Magic.pokeLong(p.kernelView(64 * (i + 1) + 32 + 8), value);
            Code c = new Code().b(0xE9).imm32(12);
            int bad = c.position();
            c.exit(1);
            c.archPrctl(SystemCalls.ARCH_SET_FS, fs).archPrctl(SystemCalls.ARCH_SET_GS, gs);
            c.b(0x48, 0x8B, 0x1C, 0x25).imm32(fs + 8);                     // mov rbx, [fs + 8]
            for (int n = 0; n < 16; n++) {
                c.xmmFromRbx(n);
            }
            c.b(0xC6, 0x04, 0x25).imm32(p.data + 1).b(1);                  // started
            int loop = c.position();
            for (int n = 0; n < 16; n++) {
                c.raxFromXmm(n).b(0x48, 0x39, 0xD8).jcc(0x5, bad);          // cmp rax, rbx; jne bad
            }
            c.b(0x64, 0x48, 0x8B, 0x04, 0x25).imm32(8).b(0x48, 0x39, 0xD8).jcc(0x5, bad);
            c.b(0x65, 0x48, 0x8B, 0x04, 0x25).imm32(8).b(0x48, 0x39, 0xD8).jcc(0x5, bad);
            c.b(0xF3, 0x90).b(0x80, 0x3C, 0x25).imm32(p.data).b(0).jcc(0x4, loop);
            c.exit(0);
            p.load(c);
            programs[i] = p;
            int index = i;
            threads[i] = new Thread(() -> results[index] = p.run());
            threads[i].start();
        }
        for (int i = 0; i < count; i++) {
            awaitStart(programs[i], "program " + i);
        }
        Thread.sleep(300);
        for (int i = 0; i < count; i++) {
            Magic.pokeByte(programs[i].kernelView(0), (byte) 1);
        }
        for (int i = 0; i < count; i++) {
            threads[i].join();
            programs[i].free();
            assertEquals(0, results[i], "program " + i + " kept its registers and bases");
        }
    }

    // More spinning user programs than CPUs, all at the same addresses: the main thread only gets to flip their flags if
    // timer interrupts from ring 3 preempt them, and the collection in between has to stop every
    // CPU that's running one. Each thread's megabyte is referenced only from its kernel stack,
    // so the collection has to walk from ring 3's interrupt frames back into those.
    static void testUserThreadsArePreemptedAndCollectedAround() throws InterruptedException {
        int count = 6;
        System.gc();
        long liveBefore = Heap.lastLive();
        Program[] programs = new Program[count];
        long[] results = new long[count];
        Thread[] threads = new Thread[count];
        for (int i = 0; i < count; i++) {
            Program p = spinner(i);
            programs[i] = p;
            int index = i;
            threads[i] = new Thread(() -> {
                byte[] keep = new byte[1 << 20];
                keep[keep.length - 1] = (byte) index;
                long status = p.run();
                results[index] = keep[keep.length - 1] == index ? status : -2;
            });
            threads[i].start();
        }
        for (int i = 0; i < count; i++) {
            awaitStart(programs[i], "program " + i);
        }
        System.gc();
        assertTrue(Heap.lastLive() - liveBefore > (long) (count - 1) << 20, "every thread's megabyte is live");
        Thread.sleep(50);
        for (int i = 0; i < count; i++) {
            Magic.pokeByte(programs[i].kernelView(0), (byte) 1);
        }
        for (int i = 0; i < count; i++) {
            threads[i].join();
            programs[i].free();
            assertEquals(i, results[i], "program " + i);
        }
    }
    static void testNanosleepWaitsAtLeastRequestedDuration() {
        Program p = new Program();
        int startOffset = 64;
        int requestOffset = 96;
        int endOffset = 128;

        // nanosleep request: 0 seconds, 50,000,000 nanoseconds.
        Magic.pokeLong(p.kernelView(requestOffset), 0);
        Magic.pokeLong(p.kernelView(requestOffset + 8), 50_000_000L);

        p.load(new Code()
            // clock_gettime(CLOCK_MONOTONIC, &start)
            .movEax(SystemCalls.CLOCK_GETTIME)
            .movEdi(SystemCalls.CLOCK_MONOTONIC)
            .movRsi(p.data + startOffset)
            .syscall()

            // nanosleep(&request, NULL)
            .movEax(SystemCalls.NANOSLEEP)
            .movEdi((int) (p.data + requestOffset))
            .b(0x31, 0xF6) // xor esi, esi: remaining = NULL
            .syscall()

            // clock_gettime(CLOCK_MONOTONIC, &end)
            .movEax(SystemCalls.CLOCK_GETTIME)
            .movEdi(SystemCalls.CLOCK_MONOTONIC)
            .movRsi(p.data + endOffset)
            .syscall()
            .exitWithResult());

        try {
            assertEquals(0, p.run(), "clock and nanosleep syscalls");

            long startSeconds = Magic.peekLong(p.kernelView(startOffset));
            long startNanos = Magic.peekLong(p.kernelView(startOffset + 8));
            long endSeconds = Magic.peekLong(p.kernelView(endOffset));
            long endNanos = Magic.peekLong(p.kernelView(endOffset + 8));

            long start = startSeconds * 1_000_000_000L + startNanos;
            long end = endSeconds * 1_000_000_000L + endNanos;
            long elapsed = end - start;

            assertTrue(elapsed >= 50_000_000L,
                "nanosleep should wait at least 50 ms, elapsed=" + elapsed + " ns");
        } finally {
            p.free();
        }
    }
}
