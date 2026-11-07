package duke.kernel.mm;

import duke.kernel.Smp;
import duke.kernel.x86.Interrupts;
import duke.kernel.x86.LocalApic;
import duke.rt.Magic;
import duke.rt.Tib;

/**
 * Which table each CPU has loaded, so a removed lower-half mapping can be flushed from every TLB
 * that might still hold it before its frame is reused. Lower-half entries are never global, so a
 * CR3 write drops them: a CPU on another table holds none of this one's, and one on it reloads
 * CR3 when asked by IPI.
 */
public final class Tlb {

    public static final int FLUSH_VECTOR = 0x31;
    private static final long[] LOADED = new long[Smp.MAX_CPUS];
    // Per CPU, flushes asked for and the last one done: an asker waits for its ticket to be done.
    private static final long[] ASKED = new long[Smp.MAX_CPUS];
    private static final long[] DONE = new long[Smp.MAX_CPUS];

    private Tlb() {
    }

    public static void init() {
        Interrupts.register(FLUSH_VECTOR, frame -> {
            LocalApic.endOfInterrupt();
            int cpu = Magic.cpuIndex();
            // Read first: an ask after it sent another IPI, which this one's EOI lets in next.
            long asked = ASKED[cpu];
            Magic.writeCr3(Magic.readCr3());
            DONE[cpu] = asked;
        });
    }

    /** Switches this CPU to {@code table}. Interrupts must be off, so the CPU can't change in between. */
    public static void load(PageTable table) {
        LOADED[Magic.cpuIndex()] = table.root();
        // Serializing, so the store above is visible before the first walk of the new table.
        Magic.writeCr3(table.root());
    }

    /**
     * Returns once no CPU can still be using a lower-half entry already cleared from {@code table}.
     * Interrupts must be on: a CPU waiting here still answers others' flushes, and stops for a
     * collection at the loop's safepoint.
     */
    public static void flush(PageTable table) {
        long root = table.root();
        // The cleared entries out before reading LOADED: a CPU that loads the table after the read
        // must walk them gone.
        Magic.memoryFence();
        long[] tickets = new long[Smp.cpuCount()];
        for (int cpu = 0; cpu < tickets.length; cpu++) {
            if (Smp.online(cpu) && LOADED[cpu] == root) {
                tickets[cpu] = increment(ASKED, cpu);
                LocalApic.sendIpi(Smp.apicId(cpu), FLUSH_VECTOR);
            }
        }
        for (int cpu = 0; cpu < tickets.length; cpu++) {
            while (DONE[cpu] < tickets[cpu]) {
                Magic.pause();
            }
        }
    }

    private static long increment(long[] counters, int index) {
        long address = Magic.addressOf(counters) + Tib.ARRAY_DATA + 8L * index;
        while (true) {
            long seen = counters[index];
            if (Magic.compareAndExchangeLong(address, seen, seen + 1) == seen) {
                return seen + 1;
            }
        }
    }
}
