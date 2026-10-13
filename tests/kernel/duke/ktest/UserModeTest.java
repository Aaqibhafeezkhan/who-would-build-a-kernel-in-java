package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.Scheduler;
import duke.kernel.mm.AddressSpace;
import duke.kernel.mm.KernelAddressSpace;
import duke.kernel.mm.PageTable;
import duke.kernel.mm.PhysicalMemory;
import duke.kernel.user.SystemCalls;
import duke.kernel.user.UserMode;
import duke.rt.Heap;
import duke.rt.Magic;
import duke.rt.Tib;

final class UserModeTest {

    private static final long PAGE = PhysicalMemory.PAGE_SIZE;
    private static final int GP = 13;
    private static final int PF = 14;

    private static final long MAP_FLOOR = 0x1000_0000L;
    private static final long MAP_TOP = 0x10_0000_0000L;

    /** Where every program's code goes: each has its own address space. */
    private static final long BASE = 0x40_0000;

    /** A read-only code page at BASE, an unmapped page, then a writable data and stack page. */
    private static final class Program {
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

        void free() {
            space.free();
        }
    }

    /** Just enough of an assembler for these tests. */
    private static final class Code {
        final byte[] bytes = new byte[256];
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
}
