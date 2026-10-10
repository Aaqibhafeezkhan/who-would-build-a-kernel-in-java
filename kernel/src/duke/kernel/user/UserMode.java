package duke.kernel.user;

import duke.kernel.Scheduler;
import duke.kernel.Smp;
import duke.kernel.x86.Frame;
import duke.kernel.x86.Gdt;
import duke.kernel.x86.Interrupts;
import duke.rt.Magic;

/**
 * Ring 3. A thread runs user code with {@link #run}, which returns when the code exits and throws
 * {@link Fault} when it faults. The thread's kernel stack stays where run left it: system calls and
 * interrupts from ring 3 land just below, and leaving user mode cuts back to it.
 *
 * <p>Every way back to ring 3 goes through Java first ({@link SystemCalls#dispatch},
 * {@link #interrupted}), which points the CPU it's on at the thread's kernel stack. So a thread
 * can be preempted or block in the kernel and carry on from another CPU.
 */
public final class UserMode {

    public static final class Fault extends RuntimeException {
        public final int vector;
        public final long rip;
        public final long errorCode;
        /** CR2 for a page fault, otherwise 0. */
        public final long address;

        Fault(int vector, long rip, long errorCode, long address) {
            super(Interrupts.exceptionName(vector) + " at 0x" + Long.toHexString(rip)
                    + (vector == PAGE_FAULT ? " touching 0x" + Long.toHexString(address) : "")
                    + " (error 0x" + Long.toHexString(errorCode) + ")");
            this.vector = vector;
            this.rip = rip;
            this.errorCode = errorCode;
            this.address = address;
        }
    }

    /** One past the highest user address: the lower half. */
    public static final long END = 0x0000_8000_0000_0000L;

    private static final int IA32_EFER = 0xC0000080;
    private static final int IA32_STAR = 0xC0000081;
    private static final int IA32_LSTAR = 0xC0000082;
    private static final int IA32_FMASK = 0xC0000084;
    /** Ring 3's GS base while the kernel's is in IA32_GS_BASE, and the other way round: swapgs trades them. */
    private static final int IA32_KERNEL_GS_BASE = 0xC0000102;
    private static final long EFER_SYSCALL = 1;
    // TF, IF, DF, IOPL, NT and AC, as Linux clears them.
    private static final long SYSCALL_CLEARS = 0x47700;
    private static final long INTERRUPT_FLAG = 0x200;
    private static final int PAGE_FAULT = 14;
    /** Saved registers, vector, error code, then the CPU's rip, cs, rflags, rsp and ss (Frame). */
    private static final int INTERRUPT_FRAME_BYTES = 176;
    private static final long FAULTED = Long.MIN_VALUE;

    // Per CPU: the fault handler records it on the CPU where run picks it up, without allocating.
    private static final long[] FAULT_VECTOR = new long[Smp.MAX_CPUS];
    private static final long[] FAULT_RIP = new long[Smp.MAX_CPUS];
    private static final long[] FAULT_ERROR = new long[Smp.MAX_CPUS];
    private static final long[] FAULT_ADDRESS = new long[Smp.MAX_CPUS];

    private UserMode() {
    }

    /** The syscall instruction's MSRs, which each CPU has its own copy of. */
    public static void initOnThisCpu() {
        Magic.writeMsr(IA32_EFER, Magic.readMsr(IA32_EFER) | EFER_SYSCALL);
        Magic.writeMsr(IA32_STAR, (long) Gdt.USER_BASE << 48 | (long) Gdt.KERNEL_CODE << 32);
        Magic.writeMsr(IA32_LSTAR, Magic.syscallEntry());
        Magic.writeMsr(IA32_FMASK, SYSCALL_CLEARS);
    }

    /**
     * Runs the user code at {@code entry} in {@code space} with its stack pointer at {@code stack}
     * until it calls exit, and returns the exit status. Both must be mapped there.
     */
    public static long run(AddressSpace space, long entry, long stack) {
        long flags = Magic.flags();
        Magic.disableInterrupts();
        Scheduler.setPageTable(space.table());
        Magic.writeMsr(IA32_KERNEL_GS_BASE, 0);
        long status = Magic.enterUser(entry, stack);
        // Back on the kernel's table, so space can be freed once this returns.
        Scheduler.setPageTable(null);
        // Read before interrupts are back on and the thread can move, allocate after: with
        // interrupts off, a collection waiting on this CPU would never get it to stop.
        int cpu = Magic.cpuIndex();
        long vector = FAULT_VECTOR[cpu];
        long rip = FAULT_RIP[cpu];
        long error = FAULT_ERROR[cpu];
        long address = FAULT_ADDRESS[cpu];
        if ((flags & INTERRUPT_FLAG) != 0) {
            Magic.enableInterrupts();
        }
        if (status == FAULTED) {
            throw new Fault((int) vector, rip, error, address);
        }
        return status;
    }

    /** An exception in ring 3: back out of user mode for {@link #run} to throw. */
    public static void fault(long frame) {
        int cpu = Magic.cpuIndex();
        int vector = Frame.vector(frame);
        FAULT_VECTOR[cpu] = vector;
        FAULT_RIP[cpu] = Frame.rip(frame);
        FAULT_ERROR[cpu] = Frame.errorCode(frame);
        FAULT_ADDRESS[cpu] = vector == PAGE_FAULT ? Magic.readCr2() : 0;
        Magic.leaveUser(frame + INTERRUPT_FRAME_BYTES, FAULTED);
    }

    /**
     * After the handler for an interrupt from ring 3, before the iretq back, with interrupts on.
     * User code never runs a prologue, so this one is where a preemption request the handler made
     * (the timer's, or a collection stopping this CPU) gets taken. The thread may come back on
     * another CPU.
     */
    public static void interrupted(long frame) {
        Magic.disableInterrupts();
        Magic.setKernelStack(frame + INTERRUPT_FRAME_BYTES);
    }
}
