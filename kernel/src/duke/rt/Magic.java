package duke.rt;

/**
 * Compiler intrinsics. Every method here is replaced by inline machine code; none has a body, and
 * calling one through anything but a direct static call is a compile error.
 */
public final class Magic {

    private Magic() {
    }

    public static native void outb(int port, int value);

    public static native void outw(int port, int value);

    public static native void outl(int port, int value);

    public static native int inb(int port);

    public static native int inw(int port);

    public static native int inl(int port);

    public static native byte peekByte(long address);

    public static native short peekShort(long address);

    public static native int peekInt(long address);

    public static native long peekLong(long address);

    public static native void pokeByte(long address, byte value);

    public static native void pokeShort(long address, short value);

    public static native void pokeInt(long address, int value);

    public static native void pokeLong(long address, long value);

    /**
     * memmove: copies exactly {@code bytes}, including unaligned and overlapping ranges in either
     * direction. The count must be nonnegative; zero does not dereference either address.
     * May use eight-byte accesses, so use this for RAM and linear framebuffers, not device registers.
     */
    public static native void copyMemory(long destination, long source, long bytes);

    /** Zero exactly {@code words * 8} bytes of aligned ordinary RAM; {@code words} must be nonnegative. */
    public static native void zeroMemoryWords(long destination, long words);

    /**
     * memset: exactly {@code bytes} copies of the low byte of {@code value}, even at an unaligned
     * destination. The count must be nonnegative; zero does not dereference the address.
     * This may use eight-byte accesses and is not for device registers.
     */
    public static native void fillMemory(long destination, int value, long bytes);

    public static native long addressOf(Object object);

    public static native Object toObject(long address);

    public static native long heapArenaStart();

    public static native long heapArenaEnd();

    /**
     * Address of 256 code pointers, one entry stub per interrupt vector. Each stub saves every
     * general-purpose register and calls {@code duke.kernel.x86.Interrupts.dispatch(frame)}, where
     * {@code frame} points at the saved registers (layout in duke.kernel.x86.Frame).
     */
    public static native long interruptStubs();

    public static native void loadIdt(long descriptor);

    /** Loads ds, es, ss and fs with {@code data} and reloads cs with {@code code}; gs keeps the per-CPU block. */
    public static native void loadSegments(int code, int data);

    public static native void loadTaskRegister(int selector);

    /**
     * This CPU's block, which GS points at: its own address, then the stack limit, the saved limit,
     * the stack base and the CPU index, 8 bytes each, then slots for starting the CPU and for user
     * mode (Compiler.CPU_BLOCK_SIZE in all).
     */
    public static native long cpuBlock();

    /** This CPU's index: 0 for the boot CPU, then in the order Smp started them. */
    public static native int cpuIndex();

    /** Atomically stores {@code value} at {@code address} and returns what was there. */
    public static native int exchangeInt(long address, int value);

    /** The rsp below which method prologues throw StackOverflowError; 0 disables the checks. */
    public static native long stackLimit();

    public static native void setStackLimit(long limit);

    /** The running thread's lowest usable stack address; the overflow limits are offsets from it. */
    public static native long stackBase();

    public static native void setStackBase(long base);

    /**
     * Makes the next prologue or loop back-edge call Runtime.preempt. For the timer interrupt; does
     * nothing while stack checks are off.
     */
    public static native void requestPreemption();

    /** Withdraws a pending request, so stackLimit() reads the real limit again. */
    public static native void cancelPreemption();

    /**
     * Saves rbp and rsp at {@code saveAt}, then resumes the thread whose rsp was saved by this
     * same intrinsic (or built to look that way). Only duke.kernel.Scheduler should call this.
     */
    public static native void switchStack(long saveAt, long rsp);

    /**
     * Address of the stub Limine starts each application processor in: it moves the CPU onto the
     * CR3 and stack in its block (Limine's mp_info extra argument), points GS at the block and calls
     * duke.kernel.Smp.apMain.
     */
    public static native long apEntry();

    /**
     * Runs user code at {@code rip} in ring 3 on the stack at {@code rsp}, with interrupts on,
     * until something calls {@link #leaveUser} with the kernel stack top this sets; returns the
     * status passed there. Call with interrupts off. Only duke.kernel.user.UserMode should call this.
     */
    public static native long enterUser(long rip, long rsp);

    /** Abandons every frame below {@code kernelStack} and makes the {@link #enterUser} that set it return {@code status}. */
    public static native void leaveUser(long kernelStack, long status);

    /** enterUser, with the other 15 registers loaded from {@code registers} in an interrupt frame's order. */
    public static native long enterUserWith(long rip, long rsp, long registers);

    /** Points this CPU's TSS and block at {@code rsp}, where the next interrupt or system call from ring 3 lands. */
    public static native void setKernelStack(long rsp);

    /** Address of the compiler's syscall entry stub, for IA32_LSTAR. It calls duke.kernel.user.SystemCalls.dispatch. */
    public static native long syscallEntry();

    /** Address of duke.kernel.Scheduler.threadMain, where a new thread's first switch lands. */
    public static native long threadEntry();

    public static native void loadGdt(long descriptor);

    public static native long readCr0();

    public static native long readCr2();

    public static native long readCr3();

    public static native long readCr4();

    public static native void writeCr0(long value);

    public static native void writeCr3(long value);

    public static native void writeCr4(long value);

    public static native void invalidatePage(long address);

    /** Saves the x87 and SSE registers to the 512 bytes at {@code address}, which must be 16-byte aligned. */
    public static native void fxsave(long address);

    /** Loads what {@link #fxsave} saved. */
    public static native void fxrstor(long address);

    public static native long readMsr(int msr);

    public static native void writeMsr(int msr, long value);

    public static native long readTimestamp();

    /** RFLAGS, for saving and restoring the interrupt flag. */
    public static native long flags();

    /** Writes eax, ebx, ecx, edx as four ints at {@code out}. */
    public static native void cpuid(int leaf, int subleaf, long out);

    /** The caller's rbp. Every compiled method keeps a frame pointer, so this starts a stack walk. */
    public static native long framePointer();

    /**
     * Virtual addresses bounding the kernel image: text start/end, rodata start/end, data start,
     * bss end, then the boot stack's guard page (Compiler.emitImageLayout).
     */
    public static native long imageLayout();

    /** Addresses of every static reference field: a count, then the addresses (Compiler.emitRootTable). */
    public static native long gcStaticRoots();

    /** Build-time reference arrays, which the heap can't see but which may point into it. */
    public static native long gcImageRoots();

    /** The compiler's method table; layout documented on Compiler.emitMethodTable. */
    public static native long methodTable();

    /**
     * Abandons the current frames: sets rsp and rbp, pushes the exception and jumps to the handler.
     * Only duke.rt.Exceptions should call this.
     */
    public static native void resumeAt(long handler, long rsp, long rbp, Throwable exception);

    /** Back to the normal stack limit, after unwinding out of a StackOverflowError's reserve. */
    public static native void resetStackLimit();

    /** int3. */
    public static native void breakpoint();

    /** A Linux system call from ring 3: the result, or a negated errno. */
    public static native long syscall(long number, long a0, long a1, long a2);

    public static native void halt();

    public static native void disableInterrupts();

    public static native void enableInterrupts();

    public static native void pause();
}
