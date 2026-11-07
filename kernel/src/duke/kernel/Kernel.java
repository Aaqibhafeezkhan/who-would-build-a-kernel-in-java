package duke.kernel;

import duke.boot.Limine;
import duke.kernel.acpi.Acpi;
import duke.kernel.acpi.Hpet;
import duke.kernel.acpi.Madt;
import duke.kernel.fs.Fat;
import duke.kernel.mm.KernelAddressSpace;
import duke.kernel.mm.KernelHeap;
import duke.kernel.mm.PhysicalMemory;
import duke.kernel.mm.Tlb;
import duke.kernel.pci.Pci;
import duke.kernel.time.Timer;
import duke.kernel.user.UserMode;
import duke.kernel.virtio.VirtioBlock;
import duke.kernel.x86.Gdt;
import duke.kernel.x86.IoApic;
import duke.kernel.x86.LocalApic;
import duke.kernel.x86.Pic;
import duke.kernel.x86.Idt;
import duke.kernel.x86.Interrupts;
import duke.rt.Heap;
import duke.rt.Magic;

public final class Kernel {

    private Kernel() {
    }

    private static int breakpoints;

    /** Brings up the core subsystems. Shared with the ktest runner, so tests see a real kernel. */
    public static void init() {
        Serial.init();
        if (!Limine.baseRevisionSupported()) {
            Panic.panic("Limine doesn't support base revision 6");
        }
        Limine.snapshot();
        Gdt.ensureLoaded();
        Idt.load();
        UserMode.initOnThisCpu();
        PhysicalMemory.init();
        KernelAddressSpace.activate();
        KernelHeap.init();
        FramebufferConsole.init();
        Acpi.init();
        Madt.init();
        Hpet.init();
        Pci.init();
        Pic.disable();
        LocalApic.init();
        Tlb.init();
        Timer.init();
        Scheduler.init();
        // Disk requests time out against the HPET clock, and the disk lock is a monitor.
        VirtioBlock.init();
        Fat.init();
        // Not before: the boot CPU's old GDT and page tables, and the other CPUs while Limine had
        // them parked, all lived in this memory. A CPU that never checked in might still be there.
        if (Smp.start()) {
            reclaimed = PhysicalMemory.reclaimBootloaderMemory();
        }
        IoApic.init();
        Ps2Keyboard.init();
        Serial.enableInput();
        Magic.enableInterrupts();
    }

    private static long reclaimed;

    /** The entry point: the compiler's _start stub calls this. */
    public static void main() {
        init();
        Console.println("Duke: hello from Java on bare metal");
        Console.println("bytecode arithmetic check: 6 * 7 = " + 6L * multiplier());
        Interrupts.register(3, frame -> breakpoints++);
        Magic.breakpoint();
        Magic.breakpoint();
        Console.println("interrupts: IDT loaded, " + breakpoints + " breakpoints handled and resumed");
        try {
            Integer.parseInt("forty-two");
        } catch (NumberFormatException e) {
            Console.println("exceptions: caught " + e);
        }
        printMemoryMap();
        Console.println("frames: " + PhysicalMemory.freeFrames() + " free ("
                + (PhysicalMemory.freeFrames() * PhysicalMemory.PAGE_SIZE >> 20) + " MiB), including "
                + (reclaimed >> 20) + " MiB reclaimed from the bootloader");
        Console.println("paging: running on our own page tables, PML4 at physical 0x"
                + Long.toHexString(KernelAddressSpace.table().root()) + ", " + KernelAddressSpace.table().tableFrames()
                + " table frames, NX " + (KernelAddressSpace.noExecuteSupported() ? "on" : "unavailable"));
        Console.println("heap: growing on demand at 0x" + Long.toHexString(KernelHeap.BASE) + ", "
                + (Heap.used() >> 10) + " KiB used, " + (Heap.committed() >> 10) + " KiB committed");
        for (int i = 0; i < 512; i++) {
            byte[] garbage = new byte[1 << 20];
        }
        Console.println("gc: allocated 512 MiB on a 256 MiB machine; " + Heap.collections() + " collections, heap "
                + (Heap.committed() >> 20) + " MiB committed");
        printPlatform();
        StringBuilder apics = new StringBuilder();
        for (int i = 0; i < Smp.cpuCount(); i++) {
            apics.append(i == 0 ? "" : ", ").append(Smp.apicId(i)).append(Smp.online(i) ? "" : " (no response)");
        }
        Console.println("smp: " + Smp.onlineCount() + " of " + Smp.cpuCount() + " CPUs online (apic " + apics + ")");
        Console.println("framebuffer: " + Limine.framebufferWidth() + "x" + Limine.framebufferHeight() + ", "
                + FramebufferConsole.columns() + "x" + FramebufferConsole.rows() + " text");
        long before = Timer.uptimeMillis();
        Timer.sleep(100);
        Console.println("timer: local APIC timer at " + Timer.apicFrequency() / 1000 + " kHz (calibrated against the HPET), "
                + Timer.HZ + " Hz tick; slept 100 ms, uptime advanced " + (Timer.uptimeMillis() - before) + " ms");
        Console.println("DUKE-BOOT-OK");
        Startup.runAll();
        Shell.run();
    }

    private static void printPlatform() {
        Console.println("acpi: revision " + Acpi.revision() + ", tables " + Acpi.signatures());
        StringBuilder cpus = new StringBuilder();
        for (Madt.LocalApic cpu : Madt.cpus()) {
            cpus.append(cpus.length() == 0 ? "" : ", ").append("apic ").append(cpu.apicId)
                    .append(cpu.enabled ? "" : " (disabled)");
        }
        Console.println("cpus: " + Madt.cpus().size() + " (" + cpus + "), local APIC at 0x"
                + Long.toHexString(Madt.localApicAddress()) + (Madt.legacyPics() ? ", legacy PICs present" : ""));
        for (Madt.IoApic io : Madt.ioApics()) {
            Console.println("ioapic " + io.id + " at 0x" + Long.toHexString(io.address) + ", GSIs from " + io.gsiBase);
        }
        StringBuilder overrides = new StringBuilder();
        for (Madt.Override o : Madt.overrides()) {
            overrides.append(overrides.length() == 0 ? "" : ", ").append("irq ").append(o.irq).append(" -> gsi ")
                    .append(o.gsi).append(o.levelTriggered() ? " level" : "").append(o.activeLow() ? " low" : "");
        }
        Console.println("irq overrides: " + overrides + "; hpet at 0x" + Long.toHexString(Hpet.address()));
        Console.println("pci: " + Pci.functions().size() + " functions, ECAM at 0x" + Long.toHexString(Pci.ecamBase()));
        for (VirtioBlock disk : VirtioBlock.devices()) {
            Console.println("virtio-blk: " + disk.capacity() + " sectors at " + disk.function().address()
                    + (disk.readOnly() ? ", read-only" : ""));
        }
        if (Fat.mounted() != null) {
            Console.println("fat: " + (Fat.mounted().fat32() ? "FAT32" : "FAT16") + " on " + Fat.mounted().disk().function().address());
        }
    }

    private static void printMemoryMap() {
        Console.println("boot: Limine base revision " + Limine.loadedBaseRevision() + ", hhdm at 0x"
                + Long.toHexString(Limine.hhdmOffset()) + ", kernel at physical 0x"
                + Long.toHexString(Limine.kernelPhysicalBase()));
        long usable = 0;
        for (int i = 0; i < Limine.memoryMapSize(); i++) {
            long base = Limine.memoryMapBase(i);
            long length = Limine.memoryMapLength(i);
            int type = Limine.memoryMapType(i);
            if (type == Limine.MEMMAP_USABLE) {
                usable += length;
            }
            Console.println("  0x" + Long.toHexString(base) + "-0x" + Long.toHexString(base + length) + " "
                    + Limine.memoryMapTypeName(type));
        }
        Console.println("memory: " + Limine.memoryMapSize() + " regions, " + (usable >> 20) + " MiB usable");
    }

    private static int multiplier() {
        return 7;
    }
}
