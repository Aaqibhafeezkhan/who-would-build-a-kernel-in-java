package duke.kernel.x86;

import duke.kernel.Console;
import duke.kernel.Panic;
import duke.kernel.user.UserMode;
import duke.rt.Backtrace;
import duke.rt.Magic;

/**
 * Java side of interrupt handling. Handlers run on the interrupted stack with interrupts off and
 * must not allocate: the bump allocator isn't reentrant.
 */
public final class Interrupts {

    public interface Handler {
        void handle(long frame);
    }

    private static final Handler[] HANDLERS = new Handler[256];
    private static final int NMI = 2;
    private static final int MACHINE_CHECK = 18;
    private static final int PAGE_FAULT = 14;

    private static final String[] EXCEPTIONS = {
        "#DE divide error", "#DB debug", "NMI", "#BP breakpoint", "#OF overflow", "#BR bound range",
        "#UD invalid opcode", "#NM device not available", "#DF double fault", "coprocessor overrun",
        "#TS invalid TSS", "#NP segment not present", "#SS stack fault", "#GP general protection",
        "#PF page fault", "reserved", "#MF x87 error", "#AC alignment check", "#MC machine check",
        "#XM SIMD error", "#VE virtualization", "#CP control protection"};

    private Interrupts() {
    }

    public static void register(int vector, Handler handler) {
        HANDLERS[vector] = handler;
    }

    public static String exceptionName(int vector) {
        return vector < EXCEPTIONS.length ? EXCEPTIONS[vector] : vector < 32 ? "reserved exception" : "interrupt";
    }

    /** Called by the compiler's interrupt stubs (Compiler.emitInterruptStubs). */
    static void dispatch(long frame) {
        int vector = Frame.vector(frame);
        boolean fromUser = Frame.cs(frame) != Gdt.KERNEL_CODE;
        // An exception in ring 3 is the user program's; NMIs and machine checks are the machine's.
        if (fromUser && vector < 32 && vector != NMI && vector != MACHINE_CHECK) {
            if (vector != PAGE_FAULT || !UserMode.pageIn(frame)) {
                UserMode.fault(frame);
            }
            Magic.enableInterrupts();
            UserMode.interrupted(frame);
            return;
        }
        Handler handler = HANDLERS[vector];
        if (handler != null) {
            handler.handle(frame);
            if (fromUser) {
                // On, as they were in ring 3, so the call's prologue can switch threads if the handler asked.
                Magic.enableInterrupts();
                UserMode.interrupted(frame);
            }
            return;
        }
        String name = exceptionName(vector);
        String detail = vector == 14 ? " at address 0x" + Long.toHexString(Magic.readCr2())
                : vector == 8 ? ", likely the stack running into its guard page" : "";
        Panic.begin(name, " (vector ", Integer.toString(vector), ", error 0x", Long.toHexString(Frame.errorCode(frame)), ")", detail);
        dump(frame);
        Backtrace.print(Frame.rip(frame), Frame.register(frame, Frame.RBP));
        Panic.haltForever();
    }

    private static void dump(long frame) {
        Console.println("  rip=0x" + Long.toHexString(Frame.rip(frame)) + " rsp=0x" + Long.toHexString(Frame.rsp(frame))
                + " rflags=0x" + Long.toHexString(Frame.rflags(frame)) + " cs=0x" + Long.toHexString(Frame.cs(frame)));
        StringBuilder line = new StringBuilder();
        for (int i = Frame.REGISTERS.length - 1; i >= 0; i--) {
            line.append("  ").append(Frame.REGISTERS[i]).append("=0x").append(Long.toHexString(Frame.register(frame, i)));
            if ((Frame.REGISTERS.length - i) % 4 == 0 || i == 0) {
                Console.println(line.toString());
                line.setLength(0);
            }
        }
    }
}
