package duke.kernel.user;

import duke.kernel.mm.AddressSpace;
import duke.kernel.mm.KernelAddressSpace;
import duke.kernel.mm.PageTable;
import duke.kernel.mm.PhysicalMemory;
import duke.kernel.time.Timer;
import duke.rt.Magic;
import duke.rt.Tib;

/**
 * Loads static x86-64 ELF executables into a new {@link AddressSpace}, with the initial stack
 * Linux's ABI gives a process: argc at rsp, then argv, an empty environment and the auxiliary
 * vector, so a static libc's startup code finds what it looks for.
 */
public final class Elf {

    /** A loaded executable, ready for {@link UserMode#run}. */
    public static final class Image {
        public final AddressSpace space;
        public final long entry;
        /** Where rsp starts, pointing at argc. */
        public final long stack;

        Image(AddressSpace space, long entry, long stack) {
            this.space = space;
            this.entry = entry;
            this.stack = stack;
        }
    }

    /** One past the stack's highest byte; the page above stays unmapped. */
    public static final long STACK_TOP = AddressSpace.END - PhysicalMemory.PAGE_SIZE;
    /** Linux's default limit. Only what's touched gets memory. */
    public static final long STACK_BYTES = 8L << 20;
    /** Bigger images are refused before anything is allocated: QEMU has 256 MiB in all. */
    public static final long MAX_IMAGE_BYTES = 64L << 20;
    /** Nothing maps below this, so null pointers and small offsets from them still fault (Linux's mmap_min_addr). */
    public static final long LOWEST = 0x1_0000;

    public static final int AT_NULL = 0;
    public static final int AT_PHDR = 3;
    public static final int AT_PHENT = 4;
    public static final int AT_PHNUM = 5;
    public static final int AT_PAGESZ = 6;
    public static final int AT_ENTRY = 9;
    public static final int AT_RANDOM = 25;

    private static final int HEADER_BYTES = 64;
    private static final int PHENT = 56;
    private static final int ET_EXEC = 2;
    private static final int EM_X86_64 = 62;
    private static final int PT_LOAD = 1;
    private static final int PT_INTERP = 3;
    private static final int PF_X = 1;
    private static final int PF_W = 2;
    private static final long PAGE = PhysicalMemory.PAGE_SIZE;

    private static long seed;

    private Elf() {
    }

    /**
     * Loads {@code file} and builds its stack with {@code argv}. Throws IllegalArgumentException,
     * saying why, for anything that isn't a static x86-64 executable that fits in the lower half.
     */
    public static Image load(byte[] file, String[] argv) {
        long phoff = check(file);
        int phnum = u16(file, 56);
        AddressSpace space = new AddressSpace();
        try {
            long phdr = 0;
            long imageEnd = LOWEST;
            for (int i = 0; i < phnum; i++) {
                int ph = (int) (phoff + (long) i * PHENT);
                if (u32(file, ph) == PT_LOAD) {
                    loadSegment(space, file, ph);
                    imageEnd = Math.max(imageEnd, u64(file, ph + 16) + u64(file, ph + 40));
                    long offset = u64(file, ph + 8);
                    if (phoff >= offset && phoff + (long) phnum * PHENT <= offset + u64(file, ph + 32)) {
                        phdr = u64(file, ph + 16) + phoff - offset;
                    }
                }
            }
            long entry = u64(file, 24);
            long stack = buildStack(space, argv, entry, phdr, phnum);
            // A page's gap on either side, so running off the end of one doesn't land in another.
            space.setMapRange(((imageEnd + PAGE - 1) & -PAGE) + PAGE, STACK_TOP - STACK_BYTES - PAGE);
            return new Image(space, entry, stack);
        } catch (RuntimeException | Error e) {
            space.free();
            throw e;
        }
    }

    /** Checks the header and every program header, and returns where the program headers start. */
    private static long check(byte[] file) {
        if (file.length < HEADER_BYTES || u32(file, 0) != 0x464C457F) {
            throw new IllegalArgumentException("not an ELF file");
        }
        if (file[4] != 2 || file[5] != 1 || u16(file, 18) != EM_X86_64) {
            throw new IllegalArgumentException("not a 64-bit little-endian x86-64 executable");
        }
        if (u16(file, 16) != ET_EXEC) {
            throw new IllegalArgumentException("not a static executable (type " + u16(file, 16) + ")");
        }
        long phoff = u64(file, 32);
        int phnum = u16(file, 56);
        if (u16(file, 54) != PHENT || phoff < 0 || phoff > file.length || (long) phnum * PHENT > file.length - phoff) {
            throw new IllegalArgumentException("program headers outside the file");
        }
        long total = 0;
        for (int i = 0; i < phnum; i++) {
            int ph = (int) (phoff + (long) i * PHENT);
            int type = u32(file, ph);
            if (type == PT_INTERP) {
                throw new IllegalArgumentException("dynamically linked");
            }
            if (type != PT_LOAD) {
                continue;
            }
            long offset = u64(file, ph + 8);
            long vaddr = u64(file, ph + 16);
            long filesz = u64(file, ph + 32);
            long memsz = u64(file, ph + 40);
            if (offset < 0 || filesz < 0 || offset > file.length || filesz > file.length - offset) {
                throw new IllegalArgumentException("segment " + i + " is outside the file");
            }
            if (vaddr < LOWEST || memsz < 0 || memsz > STACK_TOP - STACK_BYTES - vaddr) {
                throw new IllegalArgumentException("segment " + i + " is outside user memory");
            }
            if (filesz > memsz) {
                throw new IllegalArgumentException("segment " + i + " has more bytes in the file than in memory");
            }
            total += memsz;
            if (total > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("bigger than " + (MAX_IMAGE_BYTES >> 20) + " MiB");
            }
        }
        return phoff;
    }

    private static void loadSegment(AddressSpace space, byte[] file, int ph) {
        int flags = u32(file, ph + 4);
        long offset = u64(file, ph + 8);
        long vaddr = u64(file, ph + 16);
        long filesz = u64(file, ph + 32);
        long memsz = u64(file, ph + 40);
        long pageFlags = (flags & PF_W) != 0 ? PageTable.WRITABLE : 0;
        if ((flags & PF_X) == 0 && KernelAddressSpace.noExecuteSupported()) {
            pageFlags |= PageTable.NO_EXECUTE;
        }
        PageTable table = space.table();
        long source = Magic.addressOf(file) + Tib.ARRAY_DATA + offset;
        for (long page = vaddr & -PAGE; page < vaddr + memsz; page += PAGE) {
            long frame;
            long old = table.flags(page);
            if (old == 0) {
                frame = space.allocate(page, pageFlags);
            } else {
                // Segments that share a page get the union of their permissions, as Linux gives them.
                frame = table.translate(page);
                long writable = (old | pageFlags) & PageTable.WRITABLE;
                long noExecute = old & pageFlags & PageTable.NO_EXECUTE;
                table.map(page, frame, writable | noExecute | PageTable.USER);
            }
            long from = Math.max(page, vaddr);
            long to = Math.min(page + PAGE, vaddr + filesz);
            if (from < to) {
                Magic.copyMemory(PhysicalMemory.toVirtual(frame) + (from - page), source + (from - vaddr), to - from);
            }
        }
    }

    /**
     * Maps the stack and lays out, from rsp up: argc, argv and its null, the environment's null,
     * the auxiliary vector, then the strings and AT_RANDOM's bytes. All of that goes in the top
     * page, which caps argv at what fits there.
     */
    private static long buildStack(AddressSpace space, String[] argv, long entry, long phdr, int phnum) {
        long nx = KernelAddressSpace.noExecuteSupported() ? PageTable.NO_EXECUTE : 0;
        space.reserve(STACK_TOP - STACK_BYTES, STACK_BYTES, PageTable.WRITABLE | nx);
        long topFrame = space.allocate(STACK_TOP - PAGE, PageTable.WRITABLE | nx);
        long view = PhysicalMemory.toVirtual(topFrame) - (STACK_TOP - PAGE);
        long cursor = STACK_TOP - 16;
        long random = cursor;
        for (int i = 0; i < 16; i += 8) {
            Magic.pokeLong(view + random + i, nextRandom());
        }
        long[] pointers = new long[argv.length];
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            cursor -= arg.length() + 1;
            if (cursor < STACK_TOP - PAGE) {
                throw new IllegalArgumentException("arguments too long");
            }
            for (int c = 0; c < arg.length(); c++) {
                Magic.pokeByte(view + cursor + c, (byte) arg.charAt(c));
            }
            Magic.pokeByte(view + cursor + arg.length(), (byte) 0);
            pointers[i] = cursor;
        }
        long[] auxv = {
            AT_PHDR, phdr, AT_PHENT, PHENT, AT_PHNUM, phnum, AT_PAGESZ, PAGE, AT_ENTRY, entry,
            AT_RANDOM, random, AT_NULL, 0};
        int words = 1 + argv.length + 1 + 1 + auxv.length;
        long rsp = (cursor - 8L * words) & -16;
        if (rsp < STACK_TOP - PAGE) {
            throw new IllegalArgumentException("arguments too long");
        }
        long at = rsp;
        Magic.pokeLong(view + at, argv.length);
        at += 8;
        for (long p : pointers) {
            Magic.pokeLong(view + at, p);
            at += 8;
        }
        Magic.pokeLong(view + at, 0);
        Magic.pokeLong(view + at + 8, 0);
        at += 16;
        for (long v : auxv) {
            Magic.pokeLong(view + at, v);
            at += 8;
        }
        return rsp;
    }

    /** For AT_RANDOM, which libc seeds its stack canary from: varied, not unpredictable. */
    private static long nextRandom() {
        seed += Timer.uptimeMillis() * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L;
        long z = seed;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static int u16(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
    }

    private static int u32(byte[] b, int at) {
        return u16(b, at) | u16(b, at + 2) << 16;
    }

    private static long u64(byte[] b, int at) {
        return (u32(b, at) & 0xFFFF_FFFFL) | (long) u32(b, at + 4) << 32;
    }
}
