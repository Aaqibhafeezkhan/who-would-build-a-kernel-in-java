package duke.kernel.x86;

import duke.kernel.acpi.Madt;
import duke.kernel.mm.KernelAddressSpace;
import duke.rt.Magic;
import duke.rt.SpinLock;

/** This CPU's local APIC, through its memory-mapped registers. */
public final class LocalApic {

    public static final int SPURIOUS_VECTOR = 0xFF;

    private static final int ID = 0x20;
    private static final int EOI = 0xB0;
    private static final int SPURIOUS = 0xF0;
    private static final int LVT_TIMER = 0x320;
    private static final int TIMER_INITIAL = 0x380;
    private static final int TIMER_CURRENT = 0x390;
    private static final int TIMER_DIVIDE = 0x3E0;
    private static final int ICR_LOW = 0x300;
    private static final int ICR_HIGH = 0x310;

    private static final int SOFTWARE_ENABLE = 1 << 8;
    private static final int TIMER_PERIODIC = 1 << 17;
    private static final int MASKED = 1 << 16;
    private static final int DIVIDE_BY_16 = 0x3;
    private static final int LEVEL_ASSERT = 1 << 14;
    private static final int DELIVERY_PENDING = 1 << 12;
    private static final int DELIVER_NMI = 4 << 8;

    private static long base;

    private LocalApic() {
    }

    public static void init() {
        base = KernelAddressSpace.mapDevice(Madt.localApicAddress(), 4096);
        // Spurious interrupts need no EOI; the handler just has to exist.
        Interrupts.register(SPURIOUS_VECTOR, frame -> { });
        write(SPURIOUS, SOFTWARE_ENABLE | SPURIOUS_VECTOR);
    }

    /** Turns on the calling CPU's local APIC; {@link #init} mapped the registers every CPU shares. */
    public static void enableOnThisCpu() {
        write(SPURIOUS, SOFTWARE_ENABLE | SPURIOUS_VECTOR);
    }

    public static int id() {
        return read(ID) >>> 24;
    }

    public static void endOfInterrupt() {
        write(EOI, 0);
    }

    /** Sends {@code vector} to the CPU with local APIC id {@code apicId}. */
    public static void sendIpi(int apicId, int vector) {
        send(apicId, vector);
    }

    /** An NMI, which gets through even with interrupts off. */
    public static void sendNmi(int apicId) {
        send(apicId, DELIVER_NMI);
    }

    private static void send(int apicId, int command) {
        // Interrupts off: a handler's own IPI between the two writes would take our destination.
        long flags = Magic.flags();
        Magic.disableInterrupts();
        while ((read(ICR_LOW) & DELIVERY_PENDING) != 0) {
            Magic.pause();
        }
        write(ICR_HIGH, apicId << 24);
        write(ICR_LOW, LEVEL_ASSERT | command);
        SpinLock.restoreInterrupts(flags);
    }

    /** Starts a masked one-shot countdown from 2^32 - 1, for calibration. */
    static void startCountdown() {
        write(TIMER_DIVIDE, DIVIDE_BY_16);
        write(LVT_TIMER, MASKED);
        write(TIMER_INITIAL, 0xFFFF_FFFF);
    }

    static long elapsedSinceCountdown() {
        return 0xFFFF_FFFFL - (read(TIMER_CURRENT) & 0xFFFF_FFFFL);
    }

    /** Fires {@code vector} every {@code ticks} timer ticks (bus clock / 16). */
    static void startPeriodic(int vector, long ticks) {
        write(TIMER_DIVIDE, DIVIDE_BY_16);
        write(LVT_TIMER, TIMER_PERIODIC | vector);
        write(TIMER_INITIAL, (int) ticks);
    }

    private static int read(int register) {
        return Magic.peekInt(base + register);
    }

    private static void write(int register, int value) {
        Magic.pokeInt(base + register, value);
    }
}
