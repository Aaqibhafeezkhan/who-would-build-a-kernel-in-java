package duke.compiler;

import static duke.compiler.asm.Reg.RAX;
import static duke.compiler.asm.Reg.RBP;
import static duke.compiler.asm.Reg.RCX;
import static duke.compiler.asm.Reg.RDX;
import static duke.compiler.asm.Reg.RSI;
import static duke.compiler.asm.Reg.RSP;

import duke.compiler.asm.Cond;
import duke.compiler.asm.Mem;
import duke.compiler.asm.Reg;
import duke.compiler.asm.X64;
import duke.compiler.asm.X64.Alu;
import duke.compiler.asm.X64.Shift;
import duke.compiler.asm.X64.Sse;
import duke.compiler.image.Image;
import duke.compiler.image.Section;
import java.lang.classfile.Attributes;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.ArrayLoadInstruction;
import java.lang.classfile.instruction.ArrayStoreInstruction;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.NopInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Template code generator: each bytecode becomes a fixed x86 sequence operating on the machine
 * stack, which doubles as the JVM operand stack.
 *
 * <p>Every JVM stack or local slot is one 8-byte machine slot, so category-2 values (long) take
 * two: the value in the lower-numbered local / deeper stack slot, and a padding slot above it.
 * Keeping the JVM's slot model 1:1 makes dup2, pop2 and friends trivially correct. Int values in
 * a slot have undefined upper 32 bits, so anything consuming an int as 64 bits must extend it.
 *
 * <p>Calling convention: the caller leaves arguments on its operand stack (first argument
 * deepest) and pops them after the call; results come back in rax. Argument slots double as the
 * callee's first locals, addressed above rbp.
 */
final class MethodCompiler {

    private static final String RUNTIME = "duke/rt/Runtime";
    private static final String MAGIC = "duke/rt/Magic";
    private static final String HEAP = "duke/rt/Heap";
    private static final String TYPES = "duke/rt/Types";
    private static final String EXCEPTIONS = "duke/rt/Exceptions";

    private final Compiler program;
    private final ClassPool pool;
    private final ClassPool.ResolvedMethod method;
    private final X64 a;
    private final Map<Label, X64.Label> labels = new IdentityHashMap<>();
    private int argSlots;
    private int line = -1;
    private final List<ExceptionCatch> catches = new ArrayList<>();
    private int extraLocals;
    /** Slots below rbp: the Java locals that aren't arguments, plus the monitor slot. */
    private int frameSlots;
    private boolean synchronizedMethod;
    /** Code offset from which a synchronized method holds its monitor. */
    private int monitorHeldFrom;
    private static final Mem MONITOR_SLOT = Mem.at(RBP, -8);
    private List<CodeElement> elements;
    private FrameTypes.State[] states;
    /** Slot kinds before the instruction being compiled; the prologue sees the entry state. */
    private FrameTypes.State state;
    /** Operand slots the current template has popped into registers before its next call. */
    private int consumed;
    private int methodStart;
    private final List<Compiler.Safepoint> safepoints = new ArrayList<>();
    /** (code offset, source line) pairs, in code order. */
    private final List<int[]> lines = new ArrayList<>();
    MethodCompiler(Compiler program, ClassPool.ResolvedMethod method) {
        this.program = program;
        this.pool = program.pool();
        this.method = method;
        this.a = new X64(program.image().text);
    }

    void compile() {
        if (method.is(AccessFlag.NATIVE)) {
            throw error("native methods are only allowed in " + MAGIC);
        }
        if (method.is(AccessFlag.ABSTRACT)) {
            throw error("abstract method reached without virtual dispatch support");
        }
        CodeAttribute code = (CodeAttribute) method.method().code().orElseThrow();
        argSlots = argSlots(method.method().methodTypeSymbol()) + (method.is(AccessFlag.STATIC) ? 0 : 1);
        extraLocals = code.maxLocals() - argSlots;
        synchronizedMethod = method.is(AccessFlag.SYNCHRONIZED);
        frameSlots = Math.max(extraLocals, 0) + (synchronizedMethod ? 1 : 0);
        elements = code.elementList();
        states = FrameTypes.analyze(code, elements, method.method().methodTypeSymbol(), method.is(AccessFlag.STATIC));
        for (FrameTypes.State s : states) {
            if (s != null) {
                state = s;
                break;
            }
        }

        Section text = program.image().text;
        text.align(16);
        int start = a.position();
        methodStart = start;
        a.push(RBP);
        a.mov(RBP, RSP);
        if (frameSlots > 0) {
            a.aluImm(Alu.SUB, true, RSP, 8 * frameSlots);
        }
        stackCheck();
        if (synchronizedMethod) {
            enterMethodMonitor();
        }

        for (int index = 0; index < elements.size(); index++) {
            switch (elements.get(index)) {
                case ExceptionCatch c -> catches.add(c);
                case LabelTarget t -> a.bind(label(t.label()));
                case LineNumber n -> {
                    line = n.line();
                    lines.add(new int[] {a.position() - start, line});
                }
                case Instruction i -> {
                    state = states[index];
                    consumed = 0;
                    instruction(i);
                }
                default -> { }
            }
        }
        checkLabelsBound();

        String symbol = Compiler.methodSymbol(method.ownerName(), method.name(), method.descriptor());
        program.image().define(symbol, text, start, a.position() - start, Image.SymbolType.FUNC);
        String sourceFile = method.owner().findAttribute(Attributes.sourceFile())
                .map(f -> f.sourceFile().stringValue()).orElse(null);
        program.recordLines(symbol, sourceFile, lines);
        recordExceptionTable(symbol, start);
        if (synchronizedMethod) {
            program.recordMonitorHeld(symbol, monitorHeldFrom);
        }
        program.recordSafepoints(symbol, safepoints);
    }

    private CompileException error(String message) {
        String where = method.ownerName() + "." + method.name() + method.descriptor();
        if (line >= 0) {
            where += " (line " + line + ")";
        }
        return new CompileException(where + ": " + message);
    }

    private X64.Label label(Label l) {
        return labels.computeIfAbsent(l, k -> new X64.Label());
    }

    private Mem local(int slot) {
        if (slot < argSlots) {
            return Mem.at(RBP, 16 + 8 * (argSlots - 1 - slot));
        }
        return Mem.at(RBP, -8 * (slot - argSlots + 1 + (synchronizedMethod ? 1 : 0)));
    }

    /**
     * A synchronized method keeps its lock object in a hidden slot at [rbp - 8], below which the
     * Java locals start, so the unwinder can find and release it (Exceptions.raise).
     */
    private void enterMethodMonitor() {
        if (method.is(AccessFlag.STATIC)) {
            a.lea(RAX, Mem.rip(program.requireTib(method.ownerName())));
        } else {
            a.load(8, false, RAX, local(0));
        }
        a.store(8, MONITOR_SLOT, RAX);
        a.push(RAX);
        emitCall(program.requireMethod(RUNTIME, "monitorEnter", "(Ljava/lang/Object;)V"));
        a.aluImm(Alu.ADD, true, RSP, 8);
        monitorHeldFrom = a.position() - methodStart;
    }

    private static int argSlots(MethodTypeDesc type) {
        int slots = 0;
        for (ClassDesc p : type.parameterList()) {
            slots += TypeKind.from(p).slotSize();
        }
        return slots;
    }

    private void instruction(Instruction i) {
        switch (i) {
            case LoadInstruction l -> {
                a.push(local(l.slot()));
                if (l.typeKind().slotSize() == 2) {
                    a.push(local(l.slot() + 1));
                }
            }
            case StoreInstruction s -> {
                if (s.typeKind().slotSize() == 2) {
                    a.pop(local(s.slot() + 1));
                }
                a.pop(local(s.slot()));
            }
            case IncrementInstruction inc -> a.aluImm(Alu.ADD, false, local(inc.slot()), inc.constant());
            case ConstantInstruction c -> constant(c);
            case OperatorInstruction op -> operator(op.opcode());
            case ConvertInstruction c -> convert(c.opcode());
            case BranchInstruction b -> {
                X64.Label target = label(b.target());
                if (target.isBound()) {
                    preemptionPoll();
                }
                branch(b.opcode(), target);
            }
            case TableSwitchInstruction t -> lookup(t.cases(), t.defaultTarget());
            case LookupSwitchInstruction l -> lookup(l.cases(), l.defaultTarget());
            case StackInstruction s -> stack(s.opcode());
            case FieldInstruction f -> field(f);
            case InvokeInstruction inv -> invoke(inv);
            case InvokeDynamicInstruction indy -> invokeDynamic(indy);
            case ReturnInstruction r -> {
                if (synchronizedMethod) {
                    a.push(MONITOR_SLOT);
                    emitCall(program.requireMethod(RUNTIME, "monitorExit", "(Ljava/lang/Object;)V"));
                    a.aluImm(Alu.ADD, true, RSP, 8);
                }
                switch (r.typeKind().slotSize()) {
                    case 1 -> a.pop(RAX);
                    case 2 -> popLong(RAX);
                    default -> { }
                }
                a.leave();
                a.ret();
            }
            case ArrayLoadInstruction l -> arrayLoad(l.typeKind());
            case ArrayStoreInstruction s -> arrayStore(s.typeKind());
            case ThrowInstruction t -> {
                a.pop(RAX);
                consumed = 1;
                nullCheck(RAX);
                a.push(RAX);
                ensureInitialized(EXCEPTIONS);
                emitCall(program.requireMethod(EXCEPTIONS, "raise", "(Ljava/lang/Throwable;)V"));
                a.ud2();
            }
            case NewObjectInstruction n -> newObject(n.className().asInternalName());
            case NewPrimitiveArrayInstruction n -> newArray("[" + n.typeKind().upperBound().descriptorString());
            case NewReferenceArrayInstruction n -> newArray("[" + descriptorOf(n.componentType().asInternalName()));
            case NewMultiArrayInstruction n -> newMultiArray(n.arrayType().asInternalName(), n.dimensions());
            case TypeCheckInstruction t -> typeCheck(t.opcode(), program.requireTib(t.type().asInternalName()));
            case MonitorInstruction m -> {
                a.pop(RAX);
                consumed = 1;
                nullCheck(RAX);
                a.push(RAX);
                String name = m.opcode() == Opcode.MONITORENTER ? "monitorEnter" : "monitorExit";
                emitCall(program.requireMethod(RUNTIME, name, "(Ljava/lang/Object;)V"));
                a.aluImm(Alu.ADD, true, RSP, 8);
            }
            case NopInstruction n -> { }
            default -> throw error("unsupported bytecode " + i.opcode().name().toLowerCase());
        }
    }

    private void popLong(Reg r) {
        a.aluImm(Alu.ADD, true, RSP, 8);
        a.pop(r);
    }

    private void pushLong(Reg r) {
        a.push(r);
        a.push(r);
    }

    private void constant(ConstantInstruction c) {
        if (c.opcode() == Opcode.ACONST_NULL) {
            a.pushImm(0);
            return;
        }
        switch (c.constantValue()) {
            case Integer v -> a.pushImm(v);
            case Long v -> {
                a.movImm64(RAX, v);
                pushLong(RAX);
            }
            case Float v -> a.pushImm(Float.floatToRawIntBits(v));
            case Double v -> {
                a.movImm64(RAX, Double.doubleToRawLongBits(v));
                pushLong(RAX);
            }
            case String s -> {
                a.lea(RAX, Mem.rip(program.requireString(s)));
                a.push(RAX);
            }
            case ClassDesc type when !type.isPrimitive() -> {
                // A TIB is its type's Class object.
                String name = type.isArray() ? type.descriptorString() : type.descriptorString()
                        .substring(1, type.descriptorString().length() - 1);
                a.lea(RAX, Mem.rip(program.requireTib(name)));
                a.push(RAX);
            }
            default -> throw error("unsupported constant " + c.constantValue());
        }
    }

    private void operator(Opcode op) {
        switch (op) {
            case IADD -> intBinary(Alu.ADD);
            case ISUB -> intBinary(Alu.SUB);
            case IAND -> intBinary(Alu.AND);
            case IOR -> intBinary(Alu.OR);
            case IXOR -> intBinary(Alu.XOR);
            case LADD -> longBinary(Alu.ADD);
            case LSUB -> longBinary(Alu.SUB);
            case LAND -> longBinary(Alu.AND);
            case LOR -> longBinary(Alu.OR);
            case LXOR -> longBinary(Alu.XOR);
            case IMUL -> {
                a.pop(RCX);
                a.pop(RAX);
                a.imul(false, RAX, RCX);
                a.push(RAX);
            }
            case LMUL -> {
                popLong(RCX);
                popLong(RAX);
                a.imul(true, RAX, RCX);
                pushLong(RAX);
            }
            case IDIV -> divide(false, false);
            case IREM -> divide(false, true);
            case LDIV -> divide(true, false);
            case LREM -> divide(true, true);
            case INEG -> a.neg(false, Mem.at(RSP));
            case LNEG -> a.neg(true, Mem.at(RSP, 8));
            case ISHL -> intShift(Shift.SHL);
            case ISHR -> intShift(Shift.SAR);
            case IUSHR -> intShift(Shift.SHR);
            case LSHL -> longShift(Shift.SHL);
            case LSHR -> longShift(Shift.SAR);
            case LUSHR -> longShift(Shift.SHR);
            case LCMP -> {
                popLong(RCX);
                popLong(RAX);
                a.alu(Alu.CMP, true, RAX, RCX);
                a.setcc(Cond.G, RAX);
                a.setcc(Cond.L, RCX);
                a.movzx8(RAX, RAX);
                a.movzx8(RCX, RCX);
                a.alu(Alu.SUB, false, RAX, RCX);
                a.push(RAX);
            }
            case FADD -> floatBinary(Sse.ADD, false);
            case FSUB -> floatBinary(Sse.SUB, false);
            case FMUL -> floatBinary(Sse.MUL, false);
            case FDIV -> floatBinary(Sse.DIV, false);
            case DADD -> floatBinary(Sse.ADD, true);
            case DSUB -> floatBinary(Sse.SUB, true);
            case DMUL -> floatBinary(Sse.MUL, true);
            case DDIV -> floatBinary(Sse.DIV, true);
            // Flipping the sign bit is negation for NaN and zeros too, where 0 - x isn't.
            case FNEG -> a.aluImm(Alu.XOR, false, Mem.at(RSP), Integer.MIN_VALUE);
            case DNEG -> a.aluImm(Alu.XOR, false, Mem.at(RSP, 12), Integer.MIN_VALUE);
            case FCMPL -> floatCompare(false, -1);
            case FCMPG -> floatCompare(false, 1);
            case DCMPL -> floatCompare(true, -1);
            case DCMPG -> floatCompare(true, 1);
            case FREM -> floatRemainder(false);
            case DREM -> floatRemainder(true);
            case ARRAYLENGTH -> {
                a.pop(RAX);
                consumed = 1;
                nullCheck(RAX);
                a.load(4, false, RAX, Mem.at(RAX, Layouts.ARRAY_LENGTH_OFFSET));
                a.push(RAX);
            }
            default -> throw error("unsupported operator " + op.name().toLowerCase());
        }
    }

    private void intBinary(Alu op) {
        a.pop(RCX);
        a.pop(RAX);
        a.alu(op, false, RAX, RCX);
        a.push(RAX);
    }

    private void longBinary(Alu op) {
        popLong(RCX);
        popLong(RAX);
        a.alu(op, true, RAX, RCX);
        pushLong(RAX);
    }

    /**
     * Floats and doubles live on the Java stack and in locals as their bits, like ints and longs.
     * xmm0 and xmm1 hold them only within one bytecode, so a call or a thread switch never has to
     * save them; an interrupt in between does (Compiler.saveFpu).
     */
    private void popFloat(int xmm, boolean dbl) {
        popValue(RAX, dbl);
        a.movToXmm(dbl, xmm, RAX);
    }

    private void pushFloat(int xmm, boolean dbl) {
        a.movFromXmm(dbl, RAX, xmm);
        pushValue(RAX, dbl);
    }

    private void floatBinary(Sse op, boolean dbl) {
        popFloat(1, dbl);
        popFloat(0, dbl);
        a.sse(op, dbl, 0, 1);
        pushFloat(0, dbl);
    }

    /**
     * frem and drem on the x87, since SSE has no remainder. fprem truncates like Java's % and is
     * exact, so storing back to a float or double doesn't round; it reduces the exponent gap by at
     * most 63 per go, so it repeats while C2 says it's only partway.
     */
    private void floatRemainder(boolean dbl) {
        int slot = dbl ? 16 : 8;
        a.fld(dbl, Mem.at(RSP, slot - 8));
        a.fld(dbl, Mem.at(RSP, 2 * slot - 8));
        X64.Label partial = new X64.Label();
        a.bind(partial);
        a.fprem();
        a.fnstswAx();
        a.aluImm(Alu.AND, false, RAX, 0x400);
        a.jcc(Cond.NE, partial);
        a.fstp(1);
        a.aluImm(Alu.ADD, true, RSP, slot);
        a.fstp(dbl, Mem.at(RSP, slot - 8));
        if (dbl) {
            // Only the copy popLong reads was written; make both match.
            popLong(RAX);
            pushLong(RAX);
        }
    }

    /** fcmpl and the rest: -1, 0 or 1, and {@code ifNaN} when either is NaN. */
    private void floatCompare(boolean dbl, int ifNaN) {
        popFloat(1, dbl);
        popFloat(0, dbl);
        a.alu(Alu.XOR, false, RAX, RAX);
        a.alu(Alu.XOR, false, RCX, RCX);
        a.ucomis(dbl, 0, 1);
        X64.Label unordered = new X64.Label();
        X64.Label done = new X64.Label();
        a.jcc(Cond.P, unordered);
        a.setcc(Cond.A, RAX);
        a.setcc(Cond.B, RCX);
        a.alu(Alu.SUB, false, RAX, RCX);
        a.jmp(done);
        a.bind(unordered);
        a.movImm32(RAX, ifNaN);
        a.bind(done);
        a.push(RAX);
    }

    private void intShift(Shift kind) {
        a.pop(RCX);
        a.pop(RAX);
        a.shiftCl(kind, false, RAX);
        a.push(RAX);
    }

    private void longShift(Shift kind) {
        a.pop(RCX);
        popLong(RAX);
        a.shiftCl(kind, true, RAX);
        pushLong(RAX);
    }

    /** idiv traps on MIN_VALUE / -1 where Java wraps, so -1 divisors take a separate path. */
    private void divide(boolean wide, boolean remainder) {
        if (wide) {
            popLong(RCX);
            popLong(RAX);
        } else {
            a.pop(RCX);
            a.pop(RAX);
        }
        consumed = wide ? 4 : 2;
        X64.Label nonZero = new X64.Label();
        a.test(wide, RCX, RCX);
        a.jcc(Cond.NE, nonZero);
        emitCall(program.requireMethod(RUNTIME, "divideByZero", "()V"));
        a.bind(nonZero);
        X64.Label normal = new X64.Label();
        X64.Label done = new X64.Label();
        a.aluImm(Alu.CMP, wide, RCX, -1);
        a.jcc(Cond.NE, normal);
        if (remainder) {
            a.alu(Alu.XOR, false, RAX, RAX);
        } else {
            a.neg(wide, RAX);
        }
        a.jmp(done);
        a.bind(normal);
        if (wide) {
            a.cqo();
        } else {
            a.cdq();
        }
        a.idiv(wide, RCX);
        if (remainder) {
            a.mov(RAX, RDX);
        }
        a.bind(done);
        if (wide) {
            pushLong(RAX);
        } else {
            a.push(RAX);
        }
    }

    private void convert(Opcode op) {
        switch (op) {
            case I2L -> {
                a.pop(RAX);
                a.movsxd(RAX, RAX);
                pushLong(RAX);
            }
            case L2I -> {
                popLong(RAX);
                a.push(RAX);
            }
            case I2B -> narrow(() -> a.movsx8(RAX, RAX));
            case I2C -> narrow(() -> a.movzx16(RAX, RAX));
            case I2S -> narrow(() -> a.movsx16(RAX, RAX));
            case I2F, I2D, L2F, L2D -> {
                boolean wide = op == Opcode.L2F || op == Opcode.L2D;
                boolean dbl = op == Opcode.I2D || op == Opcode.L2D;
                popValue(RAX, wide);
                a.cvtsi2s(dbl, wide, 0, RAX);
                pushFloat(0, dbl);
            }
            case F2D, D2F -> {
                boolean fromDouble = op == Opcode.D2F;
                popFloat(0, fromDouble);
                a.cvts2s(fromDouble, 0, 0);
                pushFloat(0, !fromDouble);
            }
            case F2I -> floatToInteger(false, false);
            case F2L -> floatToInteger(false, true);
            case D2I -> floatToInteger(true, false);
            case D2L -> floatToInteger(true, true);
            default -> throw error("unsupported conversion " + op.name().toLowerCase());
        }
    }

    /**
     * cvttsd2si and friends give the most negative value for NaN and for anything out of range;
     * Java wants 0 for NaN and the nearest bound otherwise. The bits stay in rcx for the sign.
     */
    private void floatToInteger(boolean dbl, boolean wide) {
        popValue(RCX, dbl);
        a.movToXmm(dbl, 0, RCX);
        a.cvtts2si(dbl, wide, RAX, 0);
        X64.Label done = new X64.Label();
        if (wide) {
            a.movImm64(RDX, Long.MIN_VALUE);
            a.alu(Alu.CMP, true, RAX, RDX);
        } else {
            a.aluImm(Alu.CMP, false, RAX, Integer.MIN_VALUE);
        }
        a.jcc(Cond.NE, done);
        X64.Label nan = new X64.Label();
        a.ucomis(dbl, 0, 0);
        a.jcc(Cond.P, nan);
        a.test(dbl, RCX, RCX);
        a.jcc(Cond.S, done);
        // MIN_VALUE - 1 wraps to MAX_VALUE.
        a.aluImm(Alu.SUB, wide, RAX, 1);
        a.jmp(done);
        a.bind(nan);
        a.alu(Alu.XOR, false, RAX, RAX);
        a.bind(done);
        pushValue(RAX, wide);
    }

    private void narrow(Runnable extend) {
        a.pop(RAX);
        extend.run();
        a.push(RAX);
    }

    private void branch(Opcode op, X64.Label target) {
        switch (op) {
            case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE -> {
                a.pop(RAX);
                a.test(false, RAX, RAX);
                a.jcc(condition(op), target);
            }
            case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE, IF_ACMPEQ, IF_ACMPNE -> {
                a.pop(RCX);
                a.pop(RAX);
                a.alu(Alu.CMP, op == Opcode.IF_ACMPEQ || op == Opcode.IF_ACMPNE, RAX, RCX);
                a.jcc(condition(op), target);
            }
            case IFNULL, IFNONNULL -> {
                a.pop(RAX);
                a.test(true, RAX, RAX);
                a.jcc(op == Opcode.IFNULL ? Cond.E : Cond.NE, target);
            }
            case GOTO, GOTO_W -> a.jmp(target);
            default -> throw error("unsupported branch " + op.name().toLowerCase());
        }
    }

    /**
     * A loop that makes no calls never reaches a prologue, so back-edges check for a preemption
     * request too. Emitted before the branch pops its operands, so the stack map is the
     * instruction's entry state and the stub is free to clobber rax.
     */
    private void preemptionPoll() {
        program.requireStackOverflowStub();
        X64.Label ok = new X64.Label();
        a.aluImm(Alu.CMP, true, Compiler.STACK_LIMIT, Compiler.PREEMPT);
        a.jcc(Cond.NE, ok);
        emitCall(Compiler.STACK_OVERFLOW);
        a.bind(ok);
    }

    private static Cond condition(Opcode op) {
        return switch (op) {
            case IFEQ, IF_ICMPEQ, IF_ACMPEQ -> Cond.E;
            case IFNE, IF_ICMPNE, IF_ACMPNE -> Cond.NE;
            case IFLT, IF_ICMPLT -> Cond.L;
            case IFGE, IF_ICMPGE -> Cond.GE;
            case IFGT, IF_ICMPGT -> Cond.G;
            case IFLE, IF_ICMPLE -> Cond.LE;
            default -> throw new IllegalArgumentException(op.name());
        };
    }

    /** Compare chain for both switch forms; a jump table can come with a real register allocator. */
    private void lookup(List<SwitchCase> cases, Label defaultTarget) {
        a.pop(RAX);
        for (SwitchCase c : cases) {
            a.aluImm(Alu.CMP, false, RAX, c.caseValue());
            a.jcc(Cond.E, label(c.target()));
        }
        a.jmp(label(defaultTarget));
    }

    private void stack(Opcode op) {
        switch (op) {
            case POP -> a.aluImm(Alu.ADD, true, RSP, 8);
            case POP2 -> a.aluImm(Alu.ADD, true, RSP, 16);
            case DUP -> a.push(Mem.at(RSP));
            case DUP2 -> {
                a.push(Mem.at(RSP, 8));
                a.push(Mem.at(RSP, 8));
            }
            case SWAP -> {
                a.pop(RAX);
                a.pop(RCX);
                a.push(RAX);
                a.push(RCX);
            }
            case DUP_X1 -> shuffle(2, RAX, RCX, RAX);
            case DUP_X2 -> shuffle(3, RAX, RDX, RCX, RAX);
            case DUP2_X1 -> shuffle(3, RCX, RAX, RDX, RCX, RAX);
            case DUP2_X2 -> shuffle(4, RCX, RAX, RSI, RDX, RCX, RAX);
            default -> throw error("unsupported stack op " + op.name().toLowerCase());
        }
    }

    /** Pops {@code count} slots into rax, rcx, rdx, rsi (top first), then pushes {@code pushes} in order. */
    private void shuffle(int count, Reg... pushes) {
        Reg[] regs = {RAX, RCX, RDX, RSI};
        for (int i = 0; i < count; i++) {
            a.pop(regs[i]);
        }
        for (Reg r : pushes) {
            a.push(r);
        }
    }

    private void field(FieldInstruction f) {
        String descriptor = f.type().stringValue();
        ClassPool.ResolvedField field = pool.resolveField(f.owner().asInternalName(), f.name().stringValue(), descriptor);
        boolean isStatic = field.field().flags().has(AccessFlag.STATIC);
        boolean wantStatic = f.opcode() == Opcode.GETSTATIC || f.opcode() == Opcode.PUTSTATIC;
        if (isStatic != wantStatic) {
            throw error("field " + field.ownerName() + "." + field.name() + (isStatic ? " is" : " is not") + " static");
        }
        int width = Layouts.width(descriptor);
        boolean signed = Layouts.signed(descriptor);
        boolean wide = descriptor.equals("J") || descriptor.equals("D");
        switch (f.opcode()) {
            case GETSTATIC -> {
                ensureInitialized(field.ownerName());
                a.load(width, signed, RAX, Mem.rip(program.requireStatic(field)));
                pushValue(RAX, wide);
            }
            case PUTSTATIC -> {
                ensureInitialized(field.ownerName());
                popValue(RAX, wide);
                a.store(width, Mem.rip(program.requireStatic(field)), RAX);
            }
            case GETFIELD -> {
                int offset = program.layouts().of(field.ownerName()).offsetOf(field.name());
                a.pop(RAX);
                consumed = 1;
                nullCheck(RAX);
                a.load(width, signed, RAX, Mem.at(RAX, offset));
                pushValue(RAX, wide);
            }
            case PUTFIELD -> {
                int offset = program.layouts().of(field.ownerName()).offsetOf(field.name());
                popValue(RDX, wide);
                a.pop(RAX);
                consumed = 1 + (wide ? 2 : 1);
                nullCheck(RAX);
                a.store(width, Mem.at(RAX, offset), RDX);
            }
            default -> throw new IllegalStateException(f.opcode().name());
        }
    }

    private void pushValue(Reg r, boolean wide) {
        if (wide) {
            pushLong(r);
        } else {
            a.push(r);
        }
    }

    private void popValue(Reg r, boolean wide) {
        if (wide) {
            popLong(r);
        } else {
            a.pop(r);
        }
    }

    private void invoke(InvokeInstruction inv) {
        String owner = inv.owner().asInternalName();
        String name = inv.name().stringValue();
        String descriptor = inv.type().stringValue();
        MethodTypeDesc type = inv.typeSymbol();
        switch (inv.opcode()) {
            case INVOKESTATIC -> {
                if (owner.equals(MAGIC)) {
                    magic(name);
                    return;
                }
                ClassPool.ResolvedMethod m = pool.resolveMethod(owner, name, descriptor);
                if (!m.is(AccessFlag.STATIC)) {
                    throw error(m.ownerName() + "." + name + " is not static");
                }
                ensureInitialized(m.ownerName());
                call(m, type, argSlots(type));
            }
            case INVOKESPECIAL -> {
                ClassPool.ResolvedMethod m = pool.resolveMethod(owner, name, descriptor);
                program.requireClass(m.ownerName());
                call(m, type, argSlots(type) + 1);
            }
            case INVOKEVIRTUAL -> {
                ClassPool.ResolvedMethod m = pool.resolveMethod(owner, name, descriptor);
                if (m.is(AccessFlag.STATIC)) {
                    throw error(m.ownerName() + "." + name + " is static");
                }
                if (m.owner().flags().has(AccessFlag.INTERFACE)) {
                    // Inherited default method: only the itable knows which one this receiver runs.
                    interfaceCall(m.ownerName(), name, descriptor, type);
                    return;
                }
                boolean exact = m.is(AccessFlag.PRIVATE) || m.is(AccessFlag.FINAL)
                        || m.owner().flags().has(AccessFlag.FINAL) || !pool.isOverridden(m);
                int args = argSlots(type);
                a.load(8, false, RAX, Mem.at(RSP, 8 * args));
                nullCheck(RAX);
                program.requireClass(m.ownerName());
                if (exact) {
                    call(m, type, args + 1);
                } else {
                    int slot = program.vtables().slot(owner, name, descriptor);
                    program.requireVirtual(owner, name, descriptor);
                    a.load(8, false, RAX, Mem.at(RAX));
                    emitCall(Mem.at(RAX, Layouts.TIB_VTABLE + 8 * slot));
                    afterCall(type, args + 1);
                }
            }
            case INVOKEINTERFACE -> {
                ClassPool.ResolvedMethod m = pool.resolveMethod(owner, name, descriptor);
                if (m.is(AccessFlag.PRIVATE)) {
                    int args = argSlots(type);
                    a.load(8, false, RAX, Mem.at(RSP, 8 * args));
                    nullCheck(RAX);
                    call(m, type, args + 1);
                } else {
                    interfaceCall(owner, name, descriptor, type);
                }
            }
            default -> throw error("unsupported call " + inv.opcode().name().toLowerCase() + " to " + owner + "." + name
                    + " (lambdas are not supported yet)");
        }
    }

    /**
     * JVMS 5.5 initialization trigger. Skipped when nothing would run, and inside the class itself
     * or a subclass, where the class is already initialized or being initialized.
     */
    private void ensureInitialized(String type) {
        program.requireClass(type);
        if (!program.needsInit(type) || pool.isSubclass(method.ownerName(), type)) {
            return;
        }
        String initializer = program.requireInitializer(type);
        X64.Label done = new X64.Label();
        a.cmpByte(Mem.rip("initialized:" + type), Compiler.INITIALIZED);
        a.jcc(Cond.E, done);
        emitCall(initializer);
        a.bind(done);
    }

    /** Pushes the new object; the {@code dup; invokespecial <init>} that follows is ordinary bytecode. */
    private void newObject(String className) {
        if (pool.get(className).flags().has(AccessFlag.ABSTRACT)) {
            throw error("cannot instantiate abstract class or interface " + className);
        }
        ensureInitialized(className);
        a.lea(RAX, Mem.rip(Compiler.tibSymbol(className)));
        pushLong(RAX);
        a.pushImm(program.layouts().of(className).size());
        ClassPool.ResolvedMethod allocate = pool.resolveMethod(HEAP, "allocateObject", "(JI)Ljava/lang/Object;");
        ensureInitialized(HEAP);
        call(allocate, allocate.method().methodTypeSymbol(), 3);
    }

    /** An exact TIB match is decided inline; anything else asks duke.rt.Types. */
    private void typeCheck(Opcode op, String tib) {
        ensureInitialized(TYPES);
        if (op == Opcode.INSTANCEOF) {
            a.lea(RCX, Mem.rip(tib));
            pushLong(RCX);
            ClassPool.ResolvedMethod m = pool.resolveMethod(TYPES, "instanceOf", "(Ljava/lang/Object;J)Z");
            call(m, m.method().methodTypeSymbol(), 3);
            return;
        }
        X64.Label done = new X64.Label();
        a.load(8, false, RAX, Mem.at(RSP));
        a.test(true, RAX, RAX);
        a.jcc(Cond.E, done);
        a.load(8, false, RAX, Mem.at(RAX));
        a.lea(RCX, Mem.rip(tib));
        a.alu(Alu.CMP, true, RAX, RCX);
        a.jcc(Cond.E, done);
        a.push(Mem.at(RSP));
        pushLong(RCX);
        ClassPool.ResolvedMethod m = pool.resolveMethod(TYPES, "checkCast", "(Ljava/lang/Object;J)V");
        call(m, m.method().methodTypeSymbol(), 3);
        a.bind(done);
    }

    /**
     * Array store check with the stack as {@code array, index, value}. Null values, exact element
     * matches and Object[] targets pass inline. A null array falls through to the NPE check after.
     */
    private void arrayStoreCheck() {
        X64.Label ok = new X64.Label();
        a.load(8, false, RDX, Mem.at(RSP));
        a.test(true, RDX, RDX);
        a.jcc(Cond.E, ok);
        a.load(8, false, RAX, Mem.at(RSP, 16));
        a.test(true, RAX, RAX);
        a.jcc(Cond.E, ok);
        a.load(8, false, RAX, Mem.at(RAX));
        a.load(8, false, RAX, Mem.at(RAX, Layouts.TIB_ELEMENT));
        a.load(8, false, RDX, Mem.at(RDX));
        a.alu(Alu.CMP, true, RAX, RDX);
        a.jcc(Cond.E, ok);
        a.lea(RDX, Mem.rip(program.requireTib(ClassPool.OBJECT)));
        a.alu(Alu.CMP, true, RAX, RDX);
        a.jcc(Cond.E, ok);
        a.push(Mem.at(RSP, 16));
        a.push(Mem.at(RSP, 8));
        ensureInitialized(TYPES);
        ClassPool.ResolvedMethod m = pool.resolveMethod(TYPES, "checkArrayStore", "(Ljava/lang/Object;Ljava/lang/Object;)V");
        call(m, m.method().methodTypeSymbol(), 2);
        a.bind(ok);
    }

    /** Class entries name arrays by descriptor and classes by internal name. */
    private static String descriptorOf(String classEntryName) {
        return classEntryName.startsWith("[") ? classEntryName : "L" + classEntryName + ";";
    }

    private void newArray(String type) {
        a.pop(RCX);
        consumed = 1;
        a.lea(RAX, Mem.rip(program.requireArrayTib(type)));
        pushLong(RAX);
        a.push(RCX);
        a.pushImm(Layouts.width(type.substring(1)));
        ClassPool.ResolvedMethod allocate = pool.resolveMethod(HEAP, "allocateArray", "(JII)Ljava/lang/Object;");
        ensureInitialized(HEAP);
        call(allocate, allocate.method().methodTypeSymbol(), 4);
    }

    /** The dimension counts stay on the operand stack; the runtime reads them in place through rsp. */
    private void newMultiArray(String type, int dimensions) {
        a.mov(RAX, RSP);
        a.lea(RCX, Mem.rip(program.requireMultiArrayDescriptor(type, dimensions)));
        pushLong(RCX);
        pushLong(RAX);
        ClassPool.ResolvedMethod allocate = pool.resolveMethod(HEAP, "allocateMultiArray", "(JJ)Ljava/lang/Object;");
        ensureInitialized(HEAP);
        emitCall(program.requireMethod(allocate));
        a.aluImm(Alu.ADD, true, RSP, 8 * (4 + dimensions));
        a.push(RAX);
    }

    /** Lambdas become a static call to a factory on a class LambdaCompiler generates now. */
    private void invokeDynamic(InvokeDynamicInstruction indy) {
        if (indy.bootstrapMethod().owner().displayName().equals("StringConcatFactory")) {
            throw error("string concatenation compiled to invokedynamic; compile the kernel with javac -XDstringConcat=inline");
        }
        if (!LambdaCompiler.isLambda(indy)) {
            throw error("unsupported invokedynamic bootstrap " + indy.bootstrapMethod().owner().displayName());
        }
        ClassPool.ResolvedMethod factory;
        try {
            factory = program.lambdas().proxyFor(indy, method.ownerName());
        } catch (CompileException e) {
            throw error(e.getMessage());
        }
        ensureInitialized(factory.ownerName());
        call(factory, indy.typeSymbol(), argSlots(indy.typeSymbol()));
    }

    /** Receiver's TIB, then its itable, then the selector's slot. */
    private void interfaceCall(String iface, String name, String descriptor, MethodTypeDesc type) {
        int selector = program.requireInterfaceCall(iface, name, descriptor);
        int args = argSlots(type);
        a.load(8, false, RAX, Mem.at(RSP, 8 * args));
        nullCheck(RAX);
        a.load(8, false, RAX, Mem.at(RAX));
        a.load(8, false, RAX, Mem.at(RAX, Layouts.TIB_ITABLE));
        emitCall(Mem.at(RAX, 8 * selector));
        afterCall(type, args + 1);
    }

    private void call(ClassPool.ResolvedMethod m, MethodTypeDesc type, int slots) {
        if (m.is(AccessFlag.NATIVE)) {
            throw error("call to native method " + m.ownerName() + "." + m.name() + "; only " + MAGIC + " intrinsics may be native");
        }
        emitCall(program.requireMethod(m));
        afterCall(type, slots);
    }

    private void emitCall(String symbol) {
        a.call(symbol);
        safepoint();
    }

    private void emitCall(Mem target) {
        a.call(target);
        safepoint();
    }

    /**
     * Records which frame slots hold references at the return address of the call just emitted:
     * the reference locals, plus the reference operand slots still on the machine stack (the
     * current state's stack minus what the template already popped). Anything the template pushed
     * for the call itself sits above those and is the callee's concern.
     */
    private void safepoint() {
        List<Integer> refs = new ArrayList<>();
        for (int slot = 0; slot < state.locals().length; slot++) {
            if (state.locals()[slot] == FrameTypes.REF) {
                refs.add(local(slot).disp());
            }
        }
        int onStack = state.stack().length - consumed;
        for (int i = 0; i < onStack; i++) {
            if (state.stack()[i] == FrameTypes.REF) {
                refs.add(-8 * frameSlots - 8 * (i + 1));
            }
        }
        safepoints.add(new Compiler.Safepoint(a.position() - methodStart, refs));
    }

    /** Pops the arguments and pushes the result. */
    private void afterCall(MethodTypeDesc type, int slots) {
        if (slots > 0) {
            a.aluImm(Alu.ADD, true, RSP, 8 * slots);
        }
        switch (TypeKind.from(type.returnType()).slotSize()) {
            case 1 -> a.push(RAX);
            case 2 -> pushLong(RAX);
            default -> { }
        }
    }

    private void magic(String name) {
        switch (name) {
            case "outb" -> portOut(1);
            case "outw" -> portOut(2);
            case "outl" -> portOut(4);
            case "inb" -> portIn(1);
            case "inw" -> portIn(2);
            case "inl" -> portIn(4);
            case "peekByte" -> peek(1);
            case "peekShort" -> peek(2);
            case "peekInt" -> peek(4);
            case "peekLong" -> peek(8);
            case "pokeByte" -> poke(1);
            case "pokeShort" -> poke(2);
            case "pokeInt" -> poke(4);
            case "pokeLong" -> poke(8);
            case "copyMemory" -> copyMemory();
            case "zeroMemoryWords" -> {
                popLong(RCX);
                popLong(Reg.RDI);
                a.movImm32(RAX, 0);
                a.repStosq();
            }
            case "fillMemory" -> fillMemory();
            case "addressOf" -> {
                a.pop(RAX);
                pushLong(RAX);
            }
            case "doubleToRawLongBits", "longBitsToDouble", "floatToRawIntBits", "intBitsToFloat" -> { }
            case "toObject" -> {
                popLong(RAX);
                a.push(RAX);
            }
            case "heapArenaStart" -> {
                a.lea(RAX, Mem.rip(Compiler.HEAP_ARENA));
                pushLong(RAX);
            }
            case "heapArenaEnd" -> {
                a.lea(RAX, Mem.rip(Compiler.HEAP_ARENA, Compiler.HEAP_ARENA_SIZE));
                pushLong(RAX);
            }
            case "interruptStubs" -> {
                ensureInitialized(Compiler.INTERRUPTS);
                a.lea(RAX, Mem.rip(program.requireInterruptStubs()));
                pushLong(RAX);
            }
            case "loadSegments" -> {
                // loadSegments(code, data): data selectors directly, cs through a far return. Not gs:
                // loading a selector would zero the GS base, which points at the per-CPU block.
                a.pop(RAX);
                a.pop(RCX);
                for (int sreg : new int[] {3, 0, 2, 4}) {
                    a.movToSegment(sreg, RAX);
                }
                X64.Label reloaded = new X64.Label();
                a.push(RCX);
                a.lea(RAX, reloaded);
                a.push(RAX);
                a.retfq();
                a.bind(reloaded);
            }
            case "loadTaskRegister" -> {
                a.pop(RAX);
                a.ltr(RAX);
            }
            case "cpuBlock" -> {
                a.load(8, false, RAX, Compiler.CPU_SELF);
                pushLong(RAX);
            }
            case "cpuIndex" -> {
                a.load(4, false, RAX, Compiler.CPU_INDEX);
                a.push(RAX);
            }
            case "exchangeInt" -> {
                a.pop(RAX);
                popLong(RSI);
                a.xchg(false, Mem.at(RSI), RAX);
                a.push(RAX);
            }
            case "compareAndExchangeLong" -> {
                popLong(RCX);
                popLong(RAX);
                popLong(RSI);
                a.lockCmpxchg(true, Mem.at(RSI), RCX);
                pushLong(RAX);
            }
            case "stackLimit" -> {
                a.load(8, false, RAX, Compiler.STACK_LIMIT);
                pushLong(RAX);
            }
            case "setStackLimit" -> {
                popLong(RAX);
                a.store(8, Compiler.STACK_LIMIT, RAX);
            }
            case "loadIdt" -> {
                popLong(RAX);
                a.lidt(Mem.at(RAX));
            }
            case "loadGdt" -> {
                popLong(RAX);
                a.lgdt(Mem.at(RAX));
            }
            case "readCr0", "readCr2", "readCr3", "readCr4" -> {
                a.readCr(name.charAt(6) - '0', RAX);
                pushLong(RAX);
            }
            case "writeCr0", "writeCr3", "writeCr4" -> {
                popLong(RAX);
                a.writeCr(name.charAt(7) - '0', RAX);
            }
            case "invalidatePage" -> {
                popLong(RAX);
                a.invlpg(Mem.at(RAX));
            }
            case "fxsave" -> {
                popLong(RAX);
                a.fxsave(Mem.at(RAX));
            }
            case "fxrstor" -> {
                popLong(RAX);
                a.fxrstor(Mem.at(RAX));
            }
            case "readMsr" -> {
                a.pop(RCX);
                a.rdmsr();
                combineEdxEax();
            }
            case "writeMsr" -> {
                popLong(RAX);
                a.pop(RCX);
                a.mov(RDX, RAX);
                a.push(RCX);
                a.movImm32(RCX, 32);
                a.shiftCl(Shift.SHR, true, RDX);
                a.pop(RCX);
                a.wrmsr();
            }
            case "readTimestamp" -> {
                a.rdtsc();
                combineEdxEax();
            }
            case "flags" -> {
                a.pushfq();
                a.push(RAX);
            }
            case "cpuid" -> {
                popLong(RSI);
                a.pop(RCX);
                a.pop(RAX);
                a.cpuid();
                a.store(4, Mem.at(RSI), RAX);
                a.store(4, Mem.at(RSI, 4), Reg.RBX);
                a.store(4, Mem.at(RSI, 8), RCX);
                a.store(4, Mem.at(RSI, 12), RDX);
            }
            case "breakpoint" -> a.int3();
            case "syscall" -> {
                popLong(Reg.R9);
                popLong(Reg.R8);
                popLong(Reg.R10);
                popLong(RDX);
                popLong(RSI);
                popLong(Reg.RDI);
                popLong(RAX);
                a.syscall();
                pushLong(RAX);
            }
            case "resetStackLimit" -> {
                a.load(8, false, RAX, Compiler.STACK_BASE);
                a.lea(RAX, Mem.at(RAX, Compiler.STACK_RESERVE));
                a.store(8, Compiler.STACK_LIMIT, RAX);
            }
            case "stackBase" -> {
                a.load(8, false, RAX, Compiler.STACK_BASE);
                pushLong(RAX);
            }
            case "setStackBase" -> {
                popLong(RAX);
                a.store(8, Compiler.STACK_BASE, RAX);
            }
            case "requestPreemption" -> {
                // Leaves a disabled (0) or already requested limit alone.
                X64.Label done = new X64.Label();
                a.load(8, false, RAX, Compiler.STACK_LIMIT);
                a.test(true, RAX, RAX);
                a.jcc(Cond.E, done);
                a.aluImm(Alu.CMP, true, RAX, Compiler.PREEMPT);
                a.jcc(Cond.E, done);
                a.store(8, Compiler.STACK_LIMIT_SAVED, RAX);
                a.movImm64(RAX, Compiler.PREEMPT);
                a.store(8, Compiler.STACK_LIMIT, RAX);
                a.bind(done);
            }
            case "cancelPreemption" -> {
                X64.Label done = new X64.Label();
                a.aluImm(Alu.CMP, true, Compiler.STACK_LIMIT, Compiler.PREEMPT);
                a.jcc(Cond.NE, done);
                a.load(8, false, RAX, Compiler.STACK_LIMIT_SAVED);
                a.store(8, Compiler.STACK_LIMIT, RAX);
                a.bind(done);
            }
            case "switchStack" -> {
                // switchStack(saveAt, rsp): the other thread suspended in this same intrinsic, so
                // its frame and operand stack have the same shape as ours at this point.
                popLong(RSI);
                popLong(Reg.RDI);
                a.push(RBP);
                a.store(8, Mem.at(Reg.RDI), RSP);
                a.mov(RSP, RSI);
                a.pop(RBP);
            }
            case "enterUser" -> enterUser(false);
            case "enterUserWith" -> enterUser(true);
            case "leaveUser" -> {
                // leaveUser(kernelStack, status): pops the frame record enterUser left at kernelStack
                // and returns into it, where enterUser's result is the status.
                popLong(RAX);
                popLong(RCX);
                a.mov(RSP, RCX);
                a.pop(RBP);
                a.ret();
            }
            case "setKernelStack" -> {
                popLong(RAX);
                setKernelStack(RAX);
            }
            case "syscallEntry" -> {
                a.lea(RAX, Mem.rip(program.requireSyscallEntry()));
                pushLong(RAX);
            }
            case "apEntry" -> {
                a.lea(RAX, Mem.rip(program.requireApEntry()));
                pushLong(RAX);
            }
            case "cloneThread" -> {
                // cloneThread(flags, stack, parentTid, childTid): the child comes back from the
                // syscall on its new stack, with no frame here, so it leaves for the thread entry.
                X64.Label parent = new X64.Label();
                popLong(Reg.R10);
                popLong(RDX);
                popLong(RSI);
                popLong(Reg.RDI);
                a.alu(Alu.XOR, false, Reg.R8, Reg.R8);
                a.movImm32(RAX, CLONE);
                a.syscall();
                a.test(true, RAX, RAX);
                a.jcc(Cond.NE, parent);
                a.jmp(program.requireUserThreadEntry());
                a.bind(parent);
                pushLong(RAX);
            }
            case "threadEntry" -> {
                a.lea(RAX, Mem.rip(program.requireThreadEntry()));
                pushLong(RAX);
            }
            case "resumeAt" -> {
                // resumeAt(handler, rsp, rbp, exception): the JVM's handler entry state is an
                // operand stack holding just the exception, on top of the frame's locals.
                a.pop(RDX);
                popLong(Reg.R8);
                popLong(Reg.R9);
                popLong(RAX);
                a.mov(RBP, Reg.R8);
                a.mov(RSP, Reg.R9);
                a.push(RDX);
                a.jmp(RAX);
            }
            case "framePointer" -> {
                a.push(RBP);
                a.push(RBP);
            }
            case "gcStaticRoots" -> {
                a.lea(RAX, Mem.rip(Compiler.GC_STATIC_ROOTS));
                pushLong(RAX);
            }
            case "gcImageRoots" -> {
                a.lea(RAX, Mem.rip(Compiler.GC_IMAGE_ROOTS));
                pushLong(RAX);
            }
            case "imageLayout" -> {
                a.lea(RAX, Mem.rip(Compiler.IMAGE_LAYOUT));
                pushLong(RAX);
            }
            case "methodTable" -> {
                a.lea(RAX, Mem.rip(Compiler.METHOD_TABLE));
                pushLong(RAX);
            }
            case "halt" -> a.hlt();
            case "disableInterrupts" -> a.cli();
            case "enableInterrupts" -> a.sti();
            case "pause" -> a.pause();
            default -> throw error("unknown intrinsic " + MAGIC + "." + name);
        }
    }

    /** The order of the saved registers in an interrupt or syscall frame (duke.kernel.x86.Frame), lowest first. */
    private static final Reg[] FRAME_REGISTERS = {
        Reg.R15, Reg.R14, Reg.R13, Reg.R12, Reg.R11, Reg.R10, Reg.R9, Reg.R8,
        Reg.RDI, RSI, RBP, Reg.RBX, RDX, RCX, RAX};

    /**
     * enterUser(rip, rsp). Leaves a frame record at a 16-byte-aligned kernel stack top, a saved rbp
     * under a return address into this method, the way a call would, and points the TSS and the
     * CPU block there: interrupts and system calls from ring 3 arrive just below it, and
     * leaveUser returns through it. Then swapgs and iretq to ring 3 with interrupts on and every
     * register cleared, or for enterUserWith(rip, rsp, registers) loaded from 15 longs in a frame's
     * order, as a forked child resumes. The return address is a safepoint, so a stack walk from a
     * handler below carries on into this frame.
     */
    private void enterUser(boolean withRegisters) {
        if (withRegisters) {
            popLong(Reg.R8);
        }
        popLong(RSI);
        popLong(Reg.RDI);
        consumed = withRegisters ? 6 : 4;
        X64.Label resume = new X64.Label();
        a.mov(RDX, RSP);
        a.aluImm(Alu.AND, true, RSP, -16);
        a.push(RDX);
        a.push(RDX);
        a.lea(RAX, resume);
        a.push(RAX);
        a.push(RBP);
        setKernelStack(RSP);
        a.pushImm(Compiler.USER_DATA);
        a.push(RSI);
        a.pushImm(INTERRUPT_FLAG);
        a.pushImm(Compiler.USER_CODE);
        a.push(Reg.RDI);
        a.swapgs();
        if (withRegisters) {
            a.mov(RAX, Reg.R8);
            for (int i = 0; i < FRAME_REGISTERS.length; i++) {
                if (FRAME_REGISTERS[i] != RAX) {
                    a.load(8, false, FRAME_REGISTERS[i], Mem.at(RAX, 8 * i));
                }
            }
            a.load(8, false, RAX, Mem.at(RAX, 8 * (FRAME_REGISTERS.length - 1)));
        } else {
            for (Reg r : Reg.values()) {
                if (r != RSP) {
                    a.alu(Alu.XOR, false, r, r);
                }
            }
        }
        a.iretq();
        a.bind(resume);
        safepoint();
        // leaveUser popped the record; the original rsp is above the padding.
        a.load(8, false, RSP, Mem.at(RSP, 8));
        pushLong(RAX);
    }

    private static final int INTERRUPT_FLAG = 0x200;
    private static final int CLONE = 56;

    /** Where this CPU switches to on an interrupt or system call from ring 3: the TSS's rsp0 and the CPU block. */
    private void setKernelStack(Reg top) {
        a.store(8, Compiler.CPU_KERNEL_STACK, top);
        a.load(8, false, RCX, Compiler.CPU_TSS);
        a.store(8, Mem.at(RCX, Compiler.TSS_RSP0), top);
    }

    /** memmove in words, then bytes; neither end may be rounded out into an unmapped page. */
    private void copyMemory() {
        popLong(RCX);
        popLong(RSI);
        popLong(Reg.RDI);
        X64.Label forward = new X64.Label();
        X64.Label bytes = new X64.Label();
        X64.Label done = new X64.Label();
        a.alu(Alu.CMP, true, Reg.RDI, RSI);
        a.jcc(Cond.E, done);
        a.jcc(Cond.BE, forward);
        // A higher destination only needs the backward path when the ranges actually overlap.
        // Subtraction avoids wrapping source + length when comparing unsigned addresses.
        a.mov(RDX, Reg.RDI);
        a.alu(Alu.SUB, true, RDX, RSI);
        a.alu(Alu.CMP, true, RDX, RCX);
        a.jcc(Cond.AE, forward);

        a.lea(RSI, Mem.at(RSI, RCX, 1, -1));
        a.lea(Reg.RDI, Mem.at(Reg.RDI, RCX, 1, -1));
        a.mov(RDX, RCX);
        a.aluImm(Alu.AND, true, RCX, 7);
        a.std();
        // Peel the high tail first; a backward MOVSQ starts at the low byte of each word.
        a.repMovsb();
        a.aluImm(Alu.SUB, true, RSI, 7);
        a.aluImm(Alu.SUB, true, Reg.RDI, 7);
        a.mov(RCX, RDX);
        a.shiftImm(Shift.SHR, true, RCX, 3);
        a.repMovsq();
        a.cld();
        a.jmp(done);
        a.bind(forward);
        a.aluImm(Alu.CMP, true, RCX, 8);
        a.jcc(Cond.B, bytes);
        a.mov(RDX, RCX);
        a.shiftImm(Shift.SHR, true, RCX, 3);
        a.repMovsq();
        a.mov(RCX, RDX);
        a.aluImm(Alu.AND, true, RCX, 7);
        a.bind(bytes);
        a.repMovsb();
        a.bind(done);
    }

    /** Repeat the low byte through rax, fill whole words, then the exact remaining bytes. */
    private void fillMemory() {
        popLong(RCX);
        a.pop(RAX);
        popLong(Reg.RDI);
        X64.Label tail = new X64.Label();
        a.aluImm(Alu.CMP, true, RCX, 8);
        a.jcc(Cond.B, tail);
        a.mov(RDX, RCX);
        a.movzx8(RAX, RAX);
        a.movImm64(Reg.R8, 0x0101_0101_0101_0101L);
        a.imul(true, RAX, Reg.R8);
        a.shiftImm(Shift.SHR, true, RCX, 3);
        a.repStosq();
        a.mov(RCX, RDX);
        a.aluImm(Alu.AND, true, RCX, 7);
        a.bind(tail);
        a.repStosb();
    }

    /** Pushes edx:eax as one long. */
    private void combineEdxEax() {
        a.mov32(RAX, RAX);
        a.movImm32(RCX, 32);
        a.shiftCl(Shift.SHL, true, RDX);
        a.alu(Alu.OR, true, RAX, RDX);
        pushLong(RAX);
    }

    private void portOut(int width) {
        a.pop(RAX);
        a.pop(RDX);
        a.out(width);
    }

    private void portIn(int width) {
        a.pop(RDX);
        a.in(width);
        if (width == 1) {
            a.movzx8(RAX, RAX);
        } else if (width == 2) {
            a.movzx16(RAX, RAX);
        }
        a.push(RAX);
    }

    private void peek(int width) {
        popLong(RAX);
        a.load(width, true, RAX, Mem.at(RAX));
        pushValue(RAX, width == 8);
    }

    private void poke(int width) {
        popValue(RDX, width == 8);
        popLong(RAX);
        a.store(width, Mem.at(RAX), RDX);
    }

    private static int arrayWidth(TypeKind kind) {
        return switch (kind) {
            case BYTE, BOOLEAN -> 1;
            case CHAR, SHORT -> 2;
            case INT, FLOAT -> 4;
            case LONG, DOUBLE, REFERENCE -> 8;
            default -> 0;
        };
    }

    /**
     * Leaves the array in rax and the bounds-checked, zero-extended index in rcx. {@code popped}
     * is how many slots the caller already popped (the value, for stores).
     */
    private void checkedArrayAccess(int popped) {
        a.pop(RCX);
        a.pop(RAX);
        consumed = popped + 2;
        nullCheck(RAX);
        X64.Label inBounds = new X64.Label();
        a.alu(Alu.CMP, false, Mem.at(RAX, Layouts.ARRAY_LENGTH_OFFSET), RCX);
        a.jcc(Cond.A, inBounds);
        a.push(RCX);
        a.push(Mem.at(RAX, Layouts.ARRAY_LENGTH_OFFSET));
        emitCall(program.requireMethod(RUNTIME, "arrayIndexOutOfBounds", "(II)V"));
        a.bind(inBounds);
        a.mov32(RCX, RCX);
    }

    private void arrayLoad(TypeKind kind) {
        int width = arrayWidth(kind);
        if (width == 0) {
            throw error("unsupported array element type " + kind);
        }
        checkedArrayAccess(0);
        a.load(width, kind == TypeKind.BYTE || kind == TypeKind.SHORT, RAX,
                Mem.at(RAX, RCX, width, Layouts.ARRAY_DATA_OFFSET));
        pushValue(RAX, kind.slotSize() == 2);
    }

    private void arrayStore(TypeKind kind) {
        int width = arrayWidth(kind);
        if (width == 0) {
            throw error("unsupported array element type " + kind);
        }
        if (kind == TypeKind.REFERENCE) {
            arrayStoreCheck();
        }
        popValue(RDX, kind.slotSize() == 2);
        checkedArrayAccess(kind.slotSize() == 2 ? 2 : 1);
        a.store(width, Mem.at(RAX, RCX, width, Layouts.ARRAY_DATA_OFFSET), RDX);
    }

    /**
     * Every frame checks the stack limit once it has its locals. Crossing it calls the compiler's
     * overflow stub, which lowers the limit into the reserve and throws StackOverflowError.
     */
    private void stackCheck() {
        program.requireStackOverflowStub();
        X64.Label ok = new X64.Label();
        a.alu(Alu.CMP, true, Compiler.STACK_LIMIT, RSP);
        a.jcc(Cond.BE, ok);
        emitCall(Compiler.STACK_OVERFLOW);
        a.bind(ok);
    }

    /**
     * Runtime checks call their throwing handler inline rather than from an out-of-line stub, so
     * the return address sits inside the try range and the source line of the check.
     */
    private void nullCheck(Reg r) {
        X64.Label ok = new X64.Label();
        a.test(true, r, r);
        a.jcc(Cond.NE, ok);
        emitCall(program.requireMethod(RUNTIME, "nullPointer", "()V"));
        a.bind(ok);
    }

    /**
     * Rows of (start, end, handler, catch type TIB) over this method's code, in the order the class
     * file lists them, which is the order the JVM tries them. Offsets are from the method start.
     */
    private void recordExceptionTable(String symbol, int start) {
        List<Compiler.Handler> handlers = new ArrayList<>();
        for (ExceptionCatch c : catches) {
            String catchType = c.catchType().map(t -> program.requireTib(t.asInternalName())).orElse(null);
            handlers.add(new Compiler.Handler(label(c.tryStart()).position() - start, label(c.tryEnd()).position() - start,
                    label(c.handler()).position() - start, catchType));
        }
        if (!handlers.isEmpty()) {
            program.recordExceptionTable(symbol, 8 * frameSlots, handlers);
        }
    }

    private void checkLabelsBound() {
        for (X64.Label l : labels.values()) {
            if (!l.isBound()) {
                throw error("internal: branch target never bound");
            }
        }
    }
}
