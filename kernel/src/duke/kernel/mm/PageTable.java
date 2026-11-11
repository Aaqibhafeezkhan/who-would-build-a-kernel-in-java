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
    /** One of the bits the CPU leaves to software: read-only only until fork's copy is broken. */
    public static final long COPY_ON_WRITE = 1 << 9;
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
            if (entry(slot << 39 | 0xffff_0000_0000_0000L, 3, true, 0) == 0) {
                throw new OutOfMemoryError("out of physical memory");
            }
        }
    }

    /**
     * Drops every lower-half 4 KiB page (PhysicalMemory.drop) and frees the tables under them, then
     * the root. Only for a table from {@link #sharingHigherHalf} that no CPU has loaded: the higher
     * half isn't its to free.
     */
    public void freeLowerHalf() {
        freeTables(root, 4, HIGHER_HALF);
        PhysicalMemory.free(root);
    }

    /**
     * For fork: maps every lower-half 4 KiB page into {@code into} as well, with writable ones
     * turned copy-on-write in both. The caller flushes this table's TLBs, which may still let a
     * thread write to what's now shared. Allocates nothing on the heap (after
     * PhysicalMemory.prepareSharing), so it can run under a spin lock: false if a table for
     * {@code into} couldn't be had, with what's shared so far left for freeing {@code into} to drop.
     */
    public boolean shareLowerHalfInto(PageTable into) {
        return shareTables(root, 4, HIGHER_HALF, 0, into);
    }

    private static boolean shareTables(long table, int level, long entries, long base, PageTable into) {
        for (int i = 0; i < entries; i++) {
            long value = Magic.peekLong(PhysicalMemory.toVirtual(table) + 8 * i);
            if ((value & PRESENT) == 0) {
                continue;
            }
            long virtual = base + ((long) i << (12 + 9 * (level - 1)));
            if (level > 1 && (value & HUGE) != 0) {
                throw new IllegalStateException("huge page in a lower half being copied");
            }
            if (level > 1) {
                if (!shareTables(value & ADDRESS_MASK, level - 1, 512, virtual, into)) {
                    return false;
                }
                continue;
            }
            // Read-only pages are shared as they are, writable ones until either side writes.
            long shared = (value & WRITABLE) != 0 ? value & ~WRITABLE | COPY_ON_WRITE : value;
            if (!into.tryMap(virtual, shared & ADDRESS_MASK, shared & (USER | NO_EXECUTE | COPY_ON_WRITE))) {
                return false;
            }
            PhysicalMemory.share(shared & ADDRESS_MASK);
            Magic.pokeLong(PhysicalMemory.toVirtual(table) + 8 * i, shared);
        }
        return true;
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
                PhysicalMemory.free(value & ADDRESS_MASK);
            } else {
                PhysicalMemory.drop(value & ADDRESS_MASK);
            }
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
        if (!tryMap(virtual, physical, flags)) {
            throw new OutOfMemoryError("out of physical memory");
        }
    }

    /** Like {@link #map}, but false instead of throwing when a table can't be allocated. */
    public boolean tryMap(long virtual, long physical, long flags) {
        checkAligned(virtual, physical, PhysicalMemory.PAGE_SIZE);
        long entry = entry(virtual, 1, true, flags & USER);
        if (entry == 0) {
            return false;
        }
        Magic.pokeLong(entry, physical | flags | PRESENT);
        return true;
    }

    public void mapHuge(long virtual, long physical, long flags) {
        checkAligned(virtual, physical, HUGE_PAGE_SIZE);
        long entry = entry(virtual, 2, true, flags & USER);
        if (entry == 0) {
            throw new OutOfMemoryError("out of physical memory");
        }
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

    /**
     * Clears the 4 KiB mapping at {@code virtual} and returns its frame, or -1 if there wasn't one.
     * Only from this CPU's TLB: the frame isn't safe to reuse until the others' are flushed too.
     */
    public long remove(long virtual) {
        long entry = entry(virtual, 1, false, 0);
        long value = entry == 0 ? 0 : Magic.peekLong(entry);
        if ((value & PRESENT) == 0) {
            return -1;
        }
        Magic.pokeLong(entry, 0);
        Magic.invalidatePage(virtual);
        return value & ADDRESS_MASK;
    }

    /**
     * The lowest page-aligned address in [from, end) with a 4 KiB page mapped, or end. Skips a
     * missing table whole, so a gigabyte that was reserved and never touched is a few reads.
     */
    public long nextMapped(long from, long end) {
        long virtual = from & -PhysicalMemory.PAGE_SIZE;
        while (virtual < end) {
            long table = root;
            int level = 4;
            for (; level > 1; level--) {
                long value = Magic.peekLong(PhysicalMemory.toVirtual(table) + 8 * index(virtual, level));
                if ((value & PRESENT) == 0) {
                    break;
                }
                if ((value & HUGE) != 0) {
                    throw new IllegalStateException("0x" + Long.toHexString(virtual) + " is inside a huge page");
                }
                table = value & ADDRESS_MASK;
            }
            if (level == 1 && (Magic.peekLong(PhysicalMemory.toVirtual(table) + 8 * index(virtual, 1)) & PRESENT) != 0) {
                return virtual;
            }
            long span = 1L << (12 + 9 * (level - 1));
            virtual = (virtual & -span) + span;
        }
        return end;
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
     * missing intermediate tables when {@code create}. 0 where one is missing and can't be (or
     * mustn't be) created.
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
                long frame = PhysicalMemory.tryAllocateZeroed();
                if (frame < 0) {
                    return 0;
                }
                value = frame | PRESENT | WRITABLE | user;
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
