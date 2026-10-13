package duke.kernel.x86;

import duke.rt.Magic;
import duke.rt.Tib;

/**
 * What a thread running user code has in the CPU that the kernel's own code never touches: the FS
 * and GS bases, and the x87 and SSE registers. While the thread is on a CPU they live there, the
 * user's GS base in IA32_KERNEL_GS_BASE since the kernel's is the active one; the scheduler saves
 * them here when it switches the thread out and loads them when it switches it back in.
 */
public final class UserState {

    public static final int IA32_FS_BASE = 0xC0000100;
    /** Ring 3's GS base while the kernel's is in IA32_GS_BASE: swapgs trades them. */
    public static final int IA32_KERNEL_GS_BASE = 0xC0000102;

    private static final long CR0_MP = 1 << 1;
    private static final long CR0_EM = 1 << 2;
    private static final long CR4_OSFXSR = 1 << 9;
    private static final long CR4_OSXMMEXCPT = 1 << 10;
    private static final int FXSAVE_BYTES = 512;

    private long fsBase;
    private long gsBase;
    // fxsave wants 16-byte alignment, which the array's data only promises to 8.
    private final long[] fpu = new long[FXSAVE_BYTES / 8 + 2];
    private final long fpuArea = (Magic.addressOf(fpu) + Tib.ARRAY_DATA + 15) & -16;

    /** A new thread's: both bases 0, and the x87 and SSE control words as the CPU resets them. */
    public UserState() {
        Magic.pokeShort(fpuArea, (short) 0x037F);
        Magic.pokeInt(fpuArea + 24, 0x1F80);
    }

    /**
     * Lets ring 3 use SSE (Limine leaves CR4.OSFXSR clear, so every SSE instruction raises #UD)
     * and reports its exceptions as #XM. Each CPU has its own CR0 and CR4.
     */
    public static void initOnThisCpu() {
        Magic.writeCr0(Magic.readCr0() & ~CR0_EM | CR0_MP);
        Magic.writeCr4(Magic.readCr4() | CR4_OSFXSR | CR4_OSXMMEXCPT);
    }

    /** What the CPU holds for the calling thread's user code now, for a forked child to start from. */
    public static UserState copyOfThisCpu() {
        UserState s = new UserState();
        long flags = Magic.flags();
        Magic.disableInterrupts();
        s.save();
        if ((flags & 0x200) != 0) {
            Magic.enableInterrupts();
        }
        return s;
    }

    /** Copies the CPU's user state here. Interrupts must be off. */
    public void save() {
        fsBase = Magic.readMsr(IA32_FS_BASE);
        gsBase = Magic.readMsr(IA32_KERNEL_GS_BASE);
        Magic.fxsave(fpuArea);
    }

    /** Puts this state in the CPU, for the next return to ring 3. Interrupts must be off. */
    public void load() {
        Magic.writeMsr(IA32_FS_BASE, fsBase);
        Magic.writeMsr(IA32_KERNEL_GS_BASE, gsBase);
        Magic.fxrstor(fpuArea);
    }
}
