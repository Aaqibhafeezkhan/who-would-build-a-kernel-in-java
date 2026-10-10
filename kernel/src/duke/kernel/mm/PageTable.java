package duke.kernel.mm;

import duke.rt.Magic;

/**
 * An x86-64 4-level page table (PML4 -> PDPT -> PD -> PT). Tables are physical frames, read and
 * written through the direct map. Intermediate entries are present and writable with no NX, and
 * user-accessible once anything under them is, so the leaf entry alone decides a page's permissions.
 */
public final class PageTable {

    public static final long PRESENT = 1;
    public static final long WRITABLE = 1 << 1;
    public static final long USER = 1 << 2;
    public static final long WRITE_THROUGH = 1 << 3;
    public static final long CACHE_DISABLE = 1 << 4;
    public static final long HUGE = 1 << 7;
    public static final long GLOBAL = 1 << 8;
    public static final long NO_EXECUTE = 1L << 63;

    public static final long HUGE_PAGE_SIZE = 2 * 1024 * 1024;
    private static final long ADDRESS_MASK = 0x000F_FFFF_FFFF_F000L;
    /** PML4 entries per half. */
    private static final long HIGHER_HALF = 256;

    private final long root;
    private int tables = 1;

    public PageTable() {
        root = PhysicalMemory.allocateZeroed();
    }

    /**
     * A table whose higher half is {@code kernel}'s: the same PDPTs, so mappings the kernel adds
     * later show up here too, as long as {@code kernel} already has every one of them
     * ({@link #fillHigherHalf}).
     */
    public static PageTable sharingHigherHalf(PageTable kernel) {
        PageTable t = new PageTable();
        long half = 8 * HIGHER_HALF;
        Magic.copyMemory(PhysicalMemory.toVirtual(t.root) + half, PhysicalMemory.toVirtual(kernel.root) + half, half);
        return t;
    }

    /** Gives every higher-half PML4 entry a PDPT, so the slot set never changes again. */
    public void fillHigherHalf() {
        for (long slot = HIGHER_HALF; slot < 2 * HIGHER_HALF; slot++) {
            entry(slot << 39 | 0xffff_0000_0000_0000L, 3, true, 0);
        }
    }

    /**
     * Frees every lower-half 4 KiB page and the tables under them, then the root. Only for a table
     * from {@link #sharingHigherHalf} that no CPU has loaded: the higher half isn't its to free.
     */
    public void freeLowerHalf() {
        freeTables(root, 4, HIGHER_HALF);
        PhysicalMemory.free(root);
    }

    private static void freeTables(long table, int level, long entries) {
        for (int i = 0; i < entries; i++) {
            long value = Magic.peekLong(PhysicalMemory.toVirtual(table) + 8 * i);
            if ((value & PRESENT) == 0) {
                continue;
            }
            if (level > 1 && (value & HUGE) != 0) {
                throw new IllegalStateException("huge page in a lower half being freed");
            }
            if (level > 1) {
                freeTables(value & ADDRESS_MASK, level - 1, 512);
            }
            PhysicalMemory.free(value & ADDRESS_MASK);
        }
    }

    /** Physical address of the PML4, for CR3. */
    public long root() {
        return root;
    }

    /** Page-table frames this table owns, including the root. */
    public int tableFrames() {
        return tables;
    }

    public void map(long virtual, long physical, long flags) {
        checkAligned(virtual, physical, PhysicalMemory.PAGE_SIZE);
        long entry = entry(virtual, 1, true, flags & USER);
        Magic.pokeLong(entry, physical | flags | PRESENT);
    }

    public void mapHuge(long virtual, long physical, long flags) {
        checkAligned(virtual, physical, HUGE_PAGE_SIZE);
        long entry = entry(virtual, 2, true, flags & USER);
        Magic.pokeLong(entry, physical | flags | PRESENT | HUGE);
    }

    /** Maps [virtual, virtual + length) to the same-sized physical range, with 2 MiB pages where both line up. */
    public void mapRange(long virtual, long physical, long length, long flags) {
        long offset = 0;
        while (offset < length) {
            long v = virtual + offset;
            long p = physical + offset;
            if (v % HUGE_PAGE_SIZE == 0 && p % HUGE_PAGE_SIZE == 0 && length - offset >= HUGE_PAGE_SIZE) {
                mapHuge(v, p, flags);
                offset += HUGE_PAGE_SIZE;
            } else {
                map(v, p, flags);
                offset += PhysicalMemory.PAGE_SIZE;
            }
        }
    }

    /** Removes a 4 KiB mapping and flushes it from this CPU's TLB. */
    public void unmap(long virtual) {
        long entry = entry(virtual, 1, false, 0);
        if (entry != 0) {
            Magic.pokeLong(entry, 0);
            Magic.invalidatePage(virtual);
        }
    }

    /** The physical address {@code virtual} maps to, or -1 if it isn't mapped. */
    public long translate(long virtual) {
        long table = root;
        for (int level = 4; level >= 1; level--) {
            long value = Magic.peekLong(PhysicalMemory.toVirtual(table) + 8 * index(virtual, level));
            if ((value & PRESENT) == 0) {
                return -1;
            }
            if (level == 1 || (level == 2 && (value & HUGE) != 0)) {
                long pageSize = level == 1 ? PhysicalMemory.PAGE_SIZE : HUGE_PAGE_SIZE;
                return (value & ADDRESS_MASK & -pageSize) + (virtual & (pageSize - 1));
            }
            table = value & ADDRESS_MASK;
        }
        return -1;
    }

    /** The leaf entry's flags for {@code virtual}, or 0 if it isn't mapped. */
    public long flags(long virtual) {
        long table = root;
        for (int level = 4; level >= 1; level--) {
            long value = Magic.peekLong(PhysicalMemory.toVirtual(table) + 8 * index(virtual, level));
            if ((value & PRESENT) == 0) {
                return 0;
            }
            if (level == 1 || (level == 2 && (value & HUGE) != 0)) {
                return value & ~ADDRESS_MASK;
            }
            table = value & ADDRESS_MASK;
        }
        return 0;
    }

    /**
     * Virtual address of the entry for {@code virtual} at {@code level} (1 = PT, 2 = PD), creating
     * missing intermediate tables when {@code create}, else returning 0 where one is missing.
     * {@code user} is {@link #USER} or 0, added to every intermediate entry on the way.
     */
    private long entry(long virtual, int level, boolean create, long user) {
        long table = root;
        for (int l = 4; l > level; l--) {
            long slot = PhysicalMemory.toVirtual(table) + 8 * index(virtual, l);
            long value = Magic.peekLong(slot);
            if ((value & PRESENT) == 0) {
                if (!create) {
                    return 0;
                }
                value = PhysicalMemory.allocateZeroed() | PRESENT | WRITABLE | user;
                Magic.pokeLong(slot, value);
                tables++;
            } else if ((value & user) != user) {
                value |= user;
                Magic.pokeLong(slot, value);
            } else if ((value & HUGE) != 0) {
                throw new IllegalStateException("0x" + Long.toHexString(virtual) + " is inside a huge page");
            }
            table = value & ADDRESS_MASK;
        }
        return PhysicalMemory.toVirtual(table) + 8 * index(virtual, level);
    }

    private static long index(long virtual, int level) {
        return (virtual >>> (12 + 9 * (level - 1))) & 511;
    }

    private static void checkAligned(long virtual, long physical, long size) {
        if (virtual % size != 0 || physical % size != 0) {
            throw new IllegalArgumentException("unaligned mapping 0x" + Long.toHexString(virtual) + " -> 0x"
                    + Long.toHexString(physical));
        }
    }
}
