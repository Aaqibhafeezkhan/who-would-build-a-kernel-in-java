package duke.kernel.x86;

import duke.rt.Magic;
import duke.rt.Tib;

/**
 * What a thread running user code has in the CPU besides its general-purpose registers: the FS and
 * GS bases, and the x87 and SSE registers. The bases live in the CPU while the thread is on one,
 * the user's GS base in IA32_KERNEL_GS_BASE since the kernel's is the active one; the scheduler
 * saves them here when it switches the thread out and loads them when it switches it back in. The
 * FPU's state is here only until the thread first enters ring 3: after that, each way back into the
 * kernel saves it below its frame and puts it back on the way out (Frame.fpuState).
 */
public final class UserState {

    public static final int IA32_FS_BASE = 0xC0000100;
    /** Ring 3's GS base while the kernel's is in IA32_GS_BASE: swapgs trades them. */
    public static final int IA32_KERNEL_GS_BASE = 0xC0000102;

    private long fsBase;
    private long gsBase;
    // fxsave wants 16-byte alignment, which the array's data only promises to 8.
    private final long[] fpu = new long[Frame.FPU_STATE_BYTES / 8 + 2];
    private final long fpuArea = (Magic.addressOf(fpu) + Tib.ARRAY_DATA + 15) & -16;

    /** A new thread's: both bases 0, and the x87 and SSE control words as the CPU resets them. */
    public UserState() {
        Magic.pokeShort(fpuArea, (short) 0x037F);
        Magic.pokeInt(fpuArea + 24, 0x1F80);
    }

    /**
     * What the calling thread's user code had when it entered the kernel through {@code frame}, for
     * a forked child or a new thread to start from.
     */
    public static UserState copyOf(long frame) {
        UserState s = new UserState();
        long flags = Magic.flags();
        Magic.disableInterrupts();
        s.save();
        if ((flags & 0x200) != 0) {
            Magic.enableInterrupts();
        }
        Magic.copyMemory(s.fpuArea, Frame.fpuState(frame), Frame.FPU_STATE_BYTES);
        return s;
    }

    /** For clone's CLONE_SETTLS: the new thread's FS base. */
    public void setFsBase(long base) {
        fsBase = base;
    }

    /** Copies the CPU's user bases here. Interrupts must be off. */
    public void save() {
        fsBase = Magic.readMsr(IA32_FS_BASE);
        gsBase = Magic.readMsr(IA32_KERNEL_GS_BASE);
    }

    /** Puts the bases in the CPU, for the next return to ring 3. Interrupts must be off. */
    public void load() {
        Magic.writeMsr(IA32_FS_BASE, fsBase);
        Magic.writeMsr(IA32_KERNEL_GS_BASE, gsBase);
    }

    /**
     * Puts the FPU state in the CPU, right before the thread first enters ring 3: Java code after
     * this would compute with it. Interrupts must be off.
     */
    public void loadFpu() {
        Magic.fxrstor(fpuArea);
    }
}
