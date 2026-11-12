package duke.kernel;

import duke.boot.Limine;
import duke.kernel.mm.KernelAddressSpace;
import duke.kernel.mm.KernelStacks;
import duke.kernel.time.HpetClock;
import duke.kernel.time.Timer;
import duke.kernel.user.UserMode;
import duke.kernel.x86.Frame;
import duke.kernel.x86.Gdt;
import duke.kernel.x86.Idt;
import duke.kernel.x86.Interrupts;
import duke.kernel.x86.LocalApic;
import duke.rt.Backtrace;
import duke.rt.Magic;
import duke.rt.SpinLock;

/**
 * Brings up the other CPUs. Limine starts them and parks each on its limine_mp_info, in
 * bootloader-reclaimable memory, on its own page tables. The boot CPU gives each one a block (its
 * GS), a stack, and a GDT and TSS, then sends it to Magic.apEntry, which moves it onto our page
 * tables and stack before any Java runs. {@link #start} must finish before that memory is
 * reclaimed. Each CPU then loads its tables, starts its local APIC and timer, checks in, and
 * becomes the idle thread the scheduler made for it.
 *
 * <p>A collection stops every other CPU first ({@link #stopOthers}): it kicks each with an IPI,
 * whose handler requests preemption, and the CPU's next safepoint with interrupts on records its
 * frame and waits ({@link #stopIfRequested}). The collector walks that CPU's running thread from
 * there, as precisely as a parked one. Nowhere else does a running thread stop: in an interrupt
 * handler the frame below is wherever the interrupt hit, which has no stack map.
 */
public final class Smp {

    public static final int MAX_CPUS = 64;
    private static final int BLOCK_LONGS = 16;
    private static final int STACK_BASE = 24;
    private static final int INDEX = 32;
    private static final int STACK_TOP = 40;
    private static final int CR3 = 48;
    private static final int GDT = 56;
    /** Generous: under emulation on a loaded machine a CPU can take a while to get scheduled at all. */
    private static final long CHECK_IN_NANOS = 5_000_000_000L;
    /** Makes a CPU look at its preemption request: to stop it for a collection, or wake it for a thread. */
    public static final int KICK_VECTOR = 0x30;
    /** A kick can land between threads, while preemption requests are ignored, so kick again until it sticks. */
    private static final long KICK_INTERVAL_NANOS = 1_000_000;
    private static final long STOP_TIMEOUT_NANOS = 5_000_000_000L;

    /** Blocks for CPUs other than the boot CPU, whose block the compiler emits. Image data, so Limine's page tables map it too. */
    private static final long[] BLOCKS = new long[MAX_CPUS * BLOCK_LONGS];
    private static final boolean[] ONLINE = new boolean[MAX_CPUS];
    private static final int[] APIC_IDS = new int[MAX_CPUS];
    private static final int[] STOPPING = new int[1];
    /** Each stopped CPU's frame in stopIfRequested, where the collector starts its walk; 0 while running. */
    private static final long[] STOPPED_FRAMES = new long[MAX_CPUS];
    // locate's question to each CPU, then the answer from its NMI handler: rip and rbp, and a flag
    // set after them.
    private static final int[] LOCATE_ASKED = new int[MAX_CPUS];
    private static final long[] LOCATED = new long[2 * MAX_CPUS];
    private static final int[] LOCATE_ANSWERED = new int[MAX_CPUS];
    private static final long LOCATE_TIMEOUT_NANOS = 100_000_000;
    private static int cpus = 1;

    private Smp() {
    }

    /**
     * Starts every CPU Limine parked and waits for each to check in. Interrupts must be off. False if
     * one never did: it may still be running in bootloader memory, which then mustn't be reclaimed.
     */
    public static boolean start() {
        long response = Limine.mpResponse();
        APIC_IDS[0] = LocalApic.id();
        ONLINE[0] = true;
        if (response == 0) {
            return true;
        }
        Interrupts.register(KICK_VECTOR, frame -> {
            LocalApic.endOfInterrupt();
            Magic.requestPreemption();
        });
        int bspApic = Magic.peekInt(response + 12);
        long count = Magic.peekLong(response + 16);
        long infos = Magic.peekLong(response + 24);
        for (long i = 0; i < count && cpus < MAX_CPUS; i++) {
            long info = Magic.peekLong(infos + 8 * i);
            int apic = Magic.peekInt(info + 4);
            if (apic == bspApic) {
                continue;
            }
            int index = cpus++;
            long stack = KernelStacks.allocate();
            if (stack == -1) {
                Panic.panic("no stack for cpu ", Integer.toString(index));
            }
            long block = Magic.addressOf(BLOCKS) + 16 + 8L * BLOCK_LONGS * index;
            Magic.pokeLong(block, block);
            Magic.pokeLong(block + STACK_BASE, stack);
            Magic.pokeLong(block + INDEX, index);
            Magic.pokeLong(block + STACK_TOP, stack + KernelStacks.STACK_BYTES);
            Magic.pokeLong(block + CR3, KernelAddressSpace.table().root());
            Magic.pokeLong(block + GDT, Gdt.prepare(index));
            APIC_IDS[index] = apic;
            Scheduler.addIdle(index, stack);
            // Limine's spec: the extra argument first, then the goto address, which releases the CPU.
            Magic.pokeLong(info + 24, block);
            Magic.pokeLong(info + 16, Magic.apEntry());
        }
        long deadline = HpetClock.nanos() + CHECK_IN_NANOS;
        while (!allOnline() && HpetClock.nanos() < deadline) {
            Magic.pause();
        }
        return allOnline();
    }

    /** Where Magic.apEntry lands, on the new CPU, with GS at its block and interrupts off. */
    static void apMain() {
        Magic.resetStackLimit();
        Gdt.install(Magic.peekLong(Magic.cpuBlock() + GDT));
        Idt.loadOnThisCpu();
        UserMode.initOnThisCpu();
        LocalApic.enableOnThisCpu();
        Timer.startOnThisCpu();
        ONLINE[Magic.cpuIndex()] = true;
        Scheduler.enterIdle();
    }

    public static int cpuCount() {
        return cpus;
    }

    public static boolean online(int cpu) {
        return ONLINE[cpu];
    }

    public static int apicId(int cpu) {
        return APIC_IDS[cpu];
    }

    public static int onlineCount() {
        int online = 0;
        for (int i = 0; i < cpus; i++) {
            if (ONLINE[i]) {
                online++;
            }
        }
        return online;
    }

    /** Interrupts the CPU so it looks at its run queue and any stop request. */
    public static void kick(int cpu) {
        LocalApic.sendIpi(APIC_IDS[cpu], KICK_VECTOR);
    }

    /** From the collector, holding the heap lock: returns once every other CPU has stopped at a safepoint. */
    public static void stopOthers() {
        if (onlineCount() < 2) {
            return;
        }
        STOPPING[0] = 1;
        long deadline = HpetClock.nanos() + STOP_TIMEOUT_NANOS;
        long nextKick = 0;
        int straggler;
        while ((straggler = stillRunning()) >= 0) {
            long now = HpetClock.nanos();
            if (now >= nextKick) {
                for (int cpu = 0; cpu < cpus; cpu++) {
                    if (cpu != Magic.cpuIndex() && ONLINE[cpu] && STOPPED_FRAMES[cpu] == 0) {
                        kick(cpu);
                    }
                }
                nextKick = now + KICK_INTERVAL_NANOS;
            }
            if (now > deadline) {
                straggled(straggler);
            }
            Magic.pause();
        }
    }

    /**
     * Lets the stopped CPUs go, and waits until each has: one that hasn't cleared its frame yet
     * would look stopped to the next collection.
     */
    public static void resumeOthers() {
        if (STOPPING[0] == 0) {
            return;
        }
        STOPPING[0] = 0;
        for (int cpu = 0; cpu < cpus; cpu++) {
            while (STOPPED_FRAMES[cpu] != 0) {
                Magic.pause();
            }
        }
    }

    /** Where to start walking the stack of the thread running on a stopped CPU; 0 if it isn't stopped. */
    public static long stoppedFrame(int cpu) {
        return STOPPED_FRAMES[cpu];
    }

    /** From Runtime.preempt, at a safepoint: waits out a collection running on another CPU. */
    public static void stopIfRequested() {
        if (STOPPING[0] == 0) {
            return;
        }
        long flags = Magic.flags();
        if (!SpinLock.interruptsOn(flags)) {
            // An interrupt handler, or a short interrupts-off section; the collector kicks again.
            return;
        }
        Magic.disableInterrupts();
        int cpu = Magic.cpuIndex();
        STOPPED_FRAMES[cpu] = Magic.framePointer();
        while (STOPPING[0] != 0) {
            Magic.pause();
        }
        STOPPED_FRAMES[cpu] = 0;
        Magic.enableInterrupts();
    }

    private static void straggled(int cpu) {
        long[] where = locate(cpu);
        Panic.begin("cpu ", Integer.toString(cpu), " never stopped for a collection");
        Backtrace.printCaller();
        if (where == null) {
            // Not even an NMI got through, so it isn't running at all: under emulation, its thread
            // hasn't been scheduled.
            Console.println("cpu " + cpu + " didn't answer an NMI either");
        } else {
            Console.println("cpu " + cpu + " was at:");
            Backtrace.print(where[0], where[1]);
        }
        Panic.haltForever();
    }

    /**
     * Where {@code cpu} is running, as {rip, rbp} for {@link Backtrace#print}, asked by NMI so it
     * answers with interrupts off too; null if it doesn't answer within 100 ms. It carries on
     * afterwards. Reading its stack is only safe while it's stuck.
     */
    public static long[] locate(int cpu) {
        LOCATE_ANSWERED[cpu] = 0;
        LOCATE_ASKED[cpu] = 1;
        LocalApic.sendNmi(APIC_IDS[cpu]);
        long deadline = HpetClock.nanos() + LOCATE_TIMEOUT_NANOS;
        while (LOCATE_ANSWERED[cpu] == 0) {
            if (HpetClock.nanos() > deadline) {
                LOCATE_ASKED[cpu] = 0;
                return null;
            }
            Magic.pause();
        }
        return new long[] {LOCATED[2 * cpu], LOCATED[2 * cpu + 1]};
    }

    /** From the NMI handler, so it allocates nothing: false if the NMI wasn't from {@link #locate}. */
    public static boolean answerLocate(long frame) {
        int cpu = Magic.cpuIndex();
        if (LOCATE_ASKED[cpu] == 0) {
            return false;
        }
        LOCATE_ASKED[cpu] = 0;
        LOCATED[2 * cpu] = Frame.rip(frame);
        // A ring 3 stack is in another address space, so the trace stops at the user rip.
        LOCATED[2 * cpu + 1] = Frame.cs(frame) == Gdt.KERNEL_CODE ? Frame.register(frame, Frame.RBP) : 0;
        LOCATE_ANSWERED[cpu] = 1;
        return true;
    }

    private static int stillRunning() {
        for (int cpu = 0; cpu < cpus; cpu++) {
            if (cpu != Magic.cpuIndex() && ONLINE[cpu] && STOPPED_FRAMES[cpu] == 0) {
                return cpu;
            }
        }
        return -1;
    }

    private static boolean allOnline() {
        return onlineCount() == cpus;
    }
}
