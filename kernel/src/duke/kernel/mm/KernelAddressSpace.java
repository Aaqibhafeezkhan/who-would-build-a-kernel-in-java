package duke.kernel.mm;

import duke.boot.Limine;
import duke.rt.Magic;

/**
 * Replaces Limine's page tables with ours: the kernel image with per-section permissions (minus
 * the boot stack's guard page), and the higher-half direct map over every region Limine mapped. Same virtual layout, so execution
 * carries on across the CR3 switch.
 */
public final class KernelAddressSpace {

    private static PageTable table;
    private static boolean noExecute;

    private KernelAddressSpace() {
    }

    /** Device registers (APICs, HPET) get uncached mappings here, in their own PML4 slot. */
    private static final long DEVICE_BASE = 0xffff_d000_0000_0000L;
    private static long nextDevice = DEVICE_BASE;

    /**
     * Maps a device's physical register range uncached and returns its virtual address. Device
     * memory sits in reserved regions the direct map doesn't cover.
     */
    public static long mapDevice(long physical, long length) {
        long base = physical & -PhysicalMemory.PAGE_SIZE;
        long end = (physical + length + PhysicalMemory.PAGE_SIZE - 1) & -PhysicalMemory.PAGE_SIZE;
        long virtual = nextDevice;
        long nx = noExecute ? PageTable.NO_EXECUTE : 0;
        for (long p = base; p < end; p += PhysicalMemory.PAGE_SIZE) {
            table.map(virtual + (p - base), p, PageTable.WRITABLE | PageTable.CACHE_DISABLE | PageTable.WRITE_THROUGH | nx);
        }
        nextDevice += end - base;
        return virtual + (physical - base);
    }

    public static PageTable table() {
        return table;
    }

    public static boolean noExecuteSupported() {
        return noExecute;
    }

    public static void activate() {
        noExecute = cpuSupportsNoExecute();
        long nx = noExecute ? PageTable.NO_EXECUTE : 0;
        PageTable t = new PageTable();

        long layout = Magic.imageLayout();
        long physicalOffset = Limine.kernelPhysicalBase() - Limine.kernelVirtualBase();
        mapImage(t, Magic.peekLong(layout), Magic.peekLong(layout + 8), physicalOffset, 0);
        mapImage(t, Magic.peekLong(layout + 16), Magic.peekLong(layout + 24), physicalOffset, nx);
        mapImage(t, Magic.peekLong(layout + 32), Magic.peekLong(layout + 40), physicalOffset, PageTable.WRITABLE | nx);
        t.unmap(Magic.peekLong(layout + 48));

        long hhdm = Limine.hhdmOffset();
        for (int i = 0; i < Limine.memoryMapSize(); i++) {
            int type = Limine.memoryMapType(i);
            if (!directMapped(type)) {
                continue;
            }
            long base = Limine.memoryMapBase(i) & -PhysicalMemory.PAGE_SIZE;
            long end = (Limine.memoryMapBase(i) + Limine.memoryMapLength(i) + PhysicalMemory.PAGE_SIZE - 1)
                    & -PhysicalMemory.PAGE_SIZE;
            // Uncached for now: it's device memory to the CPU. Write-combining needs the PAT (#18).
            long caching = type == Limine.MEMMAP_FRAMEBUFFER ? PageTable.CACHE_DISABLE | PageTable.WRITE_THROUGH : 0;
            t.mapRange(hhdm + base, base, end - base, PageTable.WRITABLE | nx | caching);
        }
        // Process tables copy the higher half's PML4 entries once (PageTable.sharingHigherHalf).
        t.fillHigherHalf();
        Magic.writeCr3(t.root());
        table = t;
    }

    /** The region types base revision 4 and later put in the direct map (PROTOCOL.md, "Memory Layout at Entry"). */
    private static boolean directMapped(int type) {
        return type == Limine.MEMMAP_USABLE || type == Limine.MEMMAP_BOOTLOADER_RECLAIMABLE
                || type == Limine.MEMMAP_EXECUTABLE_AND_MODULES || type == Limine.MEMMAP_FRAMEBUFFER
                || type == Limine.MEMMAP_RESERVED_MAPPED || type == Limine.MEMMAP_ACPI_RECLAIMABLE
                || type == Limine.MEMMAP_ACPI_NVS;
    }

    private static void mapImage(PageTable t, long start, long end, long physicalOffset, long flags) {
        long first = start & -PhysicalMemory.PAGE_SIZE;
        long last = (end + PhysicalMemory.PAGE_SIZE - 1) & -PhysicalMemory.PAGE_SIZE;
        for (long v = first; v < last; v += PhysicalMemory.PAGE_SIZE) {
            t.map(v, v + physicalOffset, flags);
        }
    }

    /** CPUID 0x80000001, EDX bit 20. Limine enables EFER.NXE whenever this is set. */
    private static boolean cpuSupportsNoExecute() {
        int[] regs = new int[4];
        long out = Magic.addressOf(regs) + 16;
        Magic.cpuid(0x80000000, 0, out);
        if (Integer.compareUnsigned(regs[0], 0x80000001) < 0) {
            return false;
        }
        Magic.cpuid(0x80000001, 0, out);
        return (regs[3] & (1 << 20)) != 0;
    }
}
