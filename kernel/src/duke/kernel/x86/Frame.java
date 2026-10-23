package duke.kernel.x86;

import duke.rt.Magic;

/**
 * Accessors for the register frame the compiler's interrupt stubs build on the stack. A frame is
 * passed as its address: it's raw stack memory, not an object.
 */
public final class Frame {

    // Saved in push order rax..r15, so r15 is at the lowest address.
    public static final String[] REGISTERS = {
        "r15", "r14", "r13", "r12", "r11", "r10", "r9", "r8", "rdi", "rsi", "rbp", "rbx", "rdx", "rcx", "rax"};

    public static final int R10 = 5;
    public static final int R9 = 6;
    public static final int R8 = 7;
    public static final int RDI = 8;
    public static final int RSI = 9;
    public static final int RBP = 10;
    public static final int RDX = 12;
    public static final int RCX = 13;
    public static final int RAX = 14;

    private static final int VECTOR = 120;
    private static final int ERROR_CODE = 128;
    private static final int RIP = 136;
    private static final int CS = 144;
    private static final int RFLAGS = 152;
    private static final int RSP = 160;
    /** Compiler.FPU_STATE_BYTES. */
    public static final int FPU_STATE_BYTES = 512;

    private Frame() {
    }

    /** Where the entry stub saved the interrupted code's x87 and SSE state (Compiler.saveFpu). */
    public static long fpuState(long frame) {
        return (frame - FPU_STATE_BYTES - 16) & -16;
    }

    public static long register(long frame, int index) {
        return Magic.peekLong(frame + 8L * index);
    }

    public static void setRegister(long frame, int index, long value) {
        Magic.pokeLong(frame + 8L * index, value);
    }

    public static int vector(long frame) {
        return (int) Magic.peekLong(frame + VECTOR);
    }

    public static long errorCode(long frame) {
        return Magic.peekLong(frame + ERROR_CODE);
    }

    public static long rip(long frame) {
        return Magic.peekLong(frame + RIP);
    }

    public static long cs(long frame) {
        return Magic.peekLong(frame + CS);
    }

    public static long rflags(long frame) {
        return Magic.peekLong(frame + RFLAGS);
    }

    public static long rsp(long frame) {
        return Magic.peekLong(frame + RSP);
    }
}
