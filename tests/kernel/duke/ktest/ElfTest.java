package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.fs.Fat;
import duke.kernel.mm.PageTable;
import duke.kernel.mm.PhysicalMemory;
import duke.kernel.user.Elf;
import duke.kernel.user.UserMode;
import duke.kernel.virtio.VirtioBlock;
import duke.rt.Magic;
import java.util.Arrays;

// The executables come from tools/FatImage.java (helloElf, crashElf), on the disks KernelTests attaches.
final class ElfTest {

    private static final int GP = 13;

    private static byte[] read(String path) {
        for (VirtioBlock disk : VirtioBlock.devices()) {
            Fat fat = Fat.mount(disk);
            if (fat != null && fat.fat32()) {
                return fat.read(path);
            }
        }
        throw new AssertionError("no FAT32 disk");
    }

    private static long run(Elf.Image image) {
        try {
            return UserMode.run(image.space, image.entry, image.stack);
        } finally {
            image.space.free();
        }
    }

    /** A long from the process's memory, through the direct map. */
    private static long peek(Elf.Image image, long virtual) {
        return Magic.peekLong(PhysicalMemory.toVirtual(image.space.table().translate(virtual)));
    }

    private static String string(Elf.Image image, long virtual) {
        StringBuilder sb = new StringBuilder();
        for (long at = virtual; ; at++) {
            int c = Magic.peekByte(PhysicalMemory.toVirtual(image.space.table().translate(at))) & 0xFF;
            if (c == 0) {
                return sb.toString();
            }
            sb.append((char) c);
        }
    }

    private static String rejection(byte[] file) {
        return rejection(file, new String[] {"x"});
    }

    private static String rejection(byte[] file, String[] argv) {
        try {
            Elf.load(file, argv).space.free();
            return "loaded";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private static void putLong(byte[] b, int at, long v) {
        for (int i = 0; i < 8; i++) {
            b[at + i] = (byte) (v >>> 8 * i);
        }
    }

    // hello exits 0 only if both bss words started at zero and the write to the data segment stuck.
    static void testHelloRunsAndItsBssIsZero() {
        assertEquals(0, run(Elf.load(read("bin/hello"), new String[] {"bin/hello"})), "exit status");
    }

    static void testSegmentPermissions() {
        Elf.Image image = Elf.load(read("bin/hello"), new String[] {"hello"});
        try {
            PageTable t = image.space.table();
            long text = t.flags(0x400000);
            long data = t.flags(0x401000);
            assertEquals(PageTable.USER, text & (PageTable.USER | PageTable.WRITABLE | PageTable.NO_EXECUTE),
                    "text is user, read-only, executable");
            assertTrue((data & PageTable.WRITABLE) != 0, "data is writable");
            assertTrue(t.flags(0x402000) != 0, "bss past the file's bytes is mapped");
            assertEquals(0, t.flags(0x403000), "nothing past memsz");
        } finally {
            image.space.free();
        }
    }

    static void testStackGrowsOnDemand() {
        Elf.Image image = Elf.load(read("bin/hello"), new String[] {"hello"});
        try {
            PageTable t = image.space.table();
            long bottom = Elf.STACK_TOP - Elf.STACK_BYTES;
            assertEquals(0, t.flags(bottom), "the bottom of the stack has no memory yet");
            assertTrue(image.space.pageIn(bottom + 8), "a fault there pages it in");
            assertTrue((t.flags(bottom) & PageTable.WRITABLE) != 0, "writable");
            assertTrue(!image.space.pageIn(bottom - 8), "the page below the stack stays unmapped");
            assertTrue(!image.space.pageIn(bottom), "a second fault on the same page isn't a page-in");
        } finally {
            image.space.free();
        }
    }

    static void testInitialStack() {
        Elf.Image image = Elf.load(read("bin/hello"), new String[] {"hello", "world!"});
        try {
            long rsp = image.stack;
            assertEquals(0, rsp % 16, "rsp is 16-byte aligned");
            assertEquals(2, peek(image, rsp), "argc");
            assertEquals("hello", string(image, peek(image, rsp + 8)), "argv[0]");
            assertEquals("world!", string(image, peek(image, rsp + 16)), "argv[1]");
            assertEquals(0, peek(image, rsp + 24), "argv ends");
            assertEquals(0, peek(image, rsp + 32), "no environment");
            long phdr = -1;
            long pageSize = -1;
            long entry = -1;
            long phnum = -1;
            long at = rsp + 40;
            for (; peek(image, at) != Elf.AT_NULL; at += 16) {
                long value = peek(image, at + 8);
                switch ((int) peek(image, at)) {
                    case Elf.AT_PHDR -> phdr = value;
                    case Elf.AT_PAGESZ -> pageSize = value;
                    case Elf.AT_ENTRY -> entry = value;
                    case Elf.AT_PHNUM -> phnum = value;
                    default -> { }
                }
            }
            assertEquals(0x400040, phdr, "AT_PHDR: the headers sit in the text segment");
            assertEquals(3, phnum, "AT_PHNUM");
            assertEquals(4096, pageSize, "AT_PAGESZ");
            assertEquals(image.entry, entry, "AT_ENTRY");
        } finally {
            image.space.free();
        }
    }

    static void testFaultingExecutable() {
        try {
            long status = run(Elf.load(read("bin/crash"), new String[] {"crash"}));
            throw new AssertionError("exited with " + status);
        } catch (UserMode.Fault f) {
            assertEquals(GP, f.vector, "hlt in ring 3");
        }
    }

    static void testRejectsWhatItCantLoad() {
        byte[] hello = read("bin/hello");
        assertEquals("not an ELF file", rejection(read("hello.txt")), "a text file");
        assertEquals("not an ELF file", rejection(new byte[3]), "three bytes");

        byte[] b = Arrays.copyOf(hello, hello.length);
        b[18] = 3;
        assertEquals("not a 64-bit little-endian x86-64 executable", rejection(b), "i386");

        b = Arrays.copyOf(hello, hello.length);
        b[16] = 3;
        assertEquals("not a static executable (type 3)", rejection(b), "position-independent");

        b = Arrays.copyOf(hello, hello.length);
        putLong(b, 32, 1L << 40);
        assertEquals("program headers outside the file", rejection(b), "e_phoff");

        b = Arrays.copyOf(hello, hello.length);
        putLong(b, 64 + 2 * 56, 3);
        assertEquals("dynamically linked", rejection(b), "PT_INTERP");

        b = Arrays.copyOf(hello, hello.length);
        putLong(b, 64 + 8, hello.length);
        assertEquals("segment 0 is outside the file", rejection(b), "p_offset past the end");

        b = Arrays.copyOf(hello, hello.length);
        putLong(b, 64 + 56 + 32, 0x3000);
        assertEquals("segment 1 is outside the file", rejection(b), "p_filesz past the end");

        b = Arrays.copyOf(hello, hello.length);
        putLong(b, 64 + 40, 16);
        assertEquals("segment 0 has more bytes in the file than in memory", rejection(b), "p_filesz over p_memsz");

        b = Arrays.copyOf(hello, hello.length);
        putLong(b, 64 + 16, 0xffff_ffff_8000_0000L);
        assertEquals("segment 0 is outside user memory", rejection(b), "the kernel's image");

        b = Arrays.copyOf(hello, hello.length);
        putLong(b, 64 + 16, 0);
        assertEquals("segment 0 is outside user memory", rejection(b), "page zero");

        b = Arrays.copyOf(hello, hello.length);
        putLong(b, 64 + 16, Elf.STACK_TOP - 0x1000);
        assertEquals("segment 0 is outside user memory", rejection(b), "on the stack");

        b = Arrays.copyOf(hello, hello.length);
        putLong(b, 64 + 56 + 40, -0x1000L);
        assertEquals("segment 1 is outside user memory", rejection(b), "p_memsz wraps");

        b = Arrays.copyOf(hello, hello.length);
        putLong(b, 64 + 56 + 40, Elf.MAX_IMAGE_BYTES);
        assertEquals("bigger than 64 MiB", rejection(b), "too big");
    }

    static void testRejectingFreesEverything() {
        byte[] b = read("bin/hello");
        String[] tooMany = new String[600];
        for (int i = 0; i < tooMany.length; i++) {
            tooMany[i] = "";
        }
        // Once first, so the measured one doesn't grow the heap.
        assertEquals("arguments too long", rejection(b, tooMany), "600 arguments");
        long before = PhysicalMemory.freeFrames();
        assertEquals("arguments too long", rejection(b, tooMany), "600 arguments again");
        assertEquals(before, PhysicalMemory.freeFrames(), "frames after a failed load");
    }
}
