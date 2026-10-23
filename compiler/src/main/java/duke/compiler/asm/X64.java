package duke.compiler.asm;

import duke.compiler.image.Reloc;
import duke.compiler.image.Section;
import java.util.ArrayList;
import java.util.List;

/**
 * A small x86-64 encoder that writes straight into a section. Only the forms the code generator
 * needs exist; every one of them is pinned to GNU/LLVM output in X64Test.
 */
public final class X64 {

    public static final class Label {
        private int position = -1;
        private final List<Integer> pending = new ArrayList<>();

        public boolean isBound() {
            return position >= 0;
        }

        public int position() {
            if (position < 0) {
                throw new IllegalStateException("label not bound");
            }
            return position;
        }
    }

    public enum Alu {
        ADD(0x01, 0), OR(0x09, 1), AND(0x21, 4), SUB(0x29, 5), XOR(0x31, 6), CMP(0x39, 7);

        final int rmReg;
        final int ext;

        Alu(int rmReg, int ext) {
            this.rmReg = rmReg;
            this.ext = ext;
        }
    }

    public enum Shift {
        SHL(4), SHR(5), SAR(7);

        final int ext;

        Shift(int ext) {
            this.ext = ext;
        }
    }

    /** Scalar SSE arithmetic: {@code addsd}, {@code addss} and the rest. */
    public enum Sse {
        ADD(0x58), MUL(0x59), SUB(0x5C), DIV(0x5E);

        final int opcode;

        Sse(int opcode) {
            this.opcode = opcode;
        }
    }

    private final Section out;

    public X64(Section out) {
        this.out = out;
    }

    public int position() {
        return out.size();
    }

    public void bind(Label label) {
        if (label.isBound()) {
            throw new IllegalStateException("label bound twice");
        }
        label.position = out.size();
        for (int site : label.pending) {
            out.put32(site, label.position - (site + 4));
        }
        label.pending.clear();
    }

    private void rel32(Label label) {
        int site = out.size();
        if (label.isBound()) {
            out.emit32(label.position - (site + 4));
        } else {
            label.pending.add(site);
            out.emit32(0);
        }
    }

    public void jmp(Label target) {
        out.emit8(0xE9);
        rel32(target);
    }

    public void jcc(Cond cond, Label target) {
        out.emit8(0x0F);
        out.emit8(0x80 | cond.code());
        rel32(target);
    }

    public void call(String symbol) {
        out.emit8(0xE8);
        out.emitReloc(Reloc.Kind.PC32, symbol, -4);
    }

    public void call(Mem target) {
        memOp(false, false, 2, target, 0, 0xFF);
    }

    public void jmp(String symbol) {
        out.emit8(0xE9);
        out.emitReloc(Reloc.Kind.PC32, symbol, -4);
    }

    public void jmp(Reg target) {
        regOp(false, false, 4, target.code(), 0xFF);
    }

    public void ret() {
        out.emit8(0xC3);
    }

    public void leave() {
        out.emit8(0xC9);
    }

    public void push(Reg r) {
        rex(false, 0, 0, r.code(), false);
        out.emit8(0x50 | r.low3());
    }

    public void pop(Reg r) {
        rex(false, 0, 0, r.code(), false);
        out.emit8(0x58 | r.low3());
    }

    public void push(Mem m) {
        memOp(false, false, 6, m, 0, 0xFF);
    }

    public void pop(Mem m) {
        memOp(false, false, 0, m, 0, 0x8F);
    }

    /** Pushes a sign-extended 32-bit immediate. */
    public void pushImm(int imm) {
        if (imm == (byte) imm) {
            out.emit8(0x6A);
            out.emit8(imm);
        } else {
            out.emit8(0x68);
            out.emit32(imm);
        }
    }

    public void mov(Reg dst, Reg src) {
        regOp(true, false, src.code(), dst.code(), 0x89);
    }

    public void mov32(Reg dst, Reg src) {
        regOp(false, false, src.code(), dst.code(), 0x89);
    }

    /** {@code mov r32, imm32}, which zero-extends into the full register. */
    public void movImm32(Reg dst, int imm) {
        rex(false, 0, 0, dst.code(), false);
        out.emit8(0xB8 | dst.low3());
        out.emit32(imm);
    }

    public void movImm64(Reg dst, long imm) {
        if (imm == (imm & 0xFFFF_FFFFL)) {
            movImm32(dst, (int) imm);
            return;
        }
        rex(true, 0, 0, dst.code(), false);
        out.emit8(0xB8 | dst.low3());
        out.emit64(imm);
    }

    /** Loads {@code width} bytes into {@code dst}, sign- or zero-extending narrow values to 32 bits (64 for signed ints). */
    public void load(int width, boolean signed, Reg dst, Mem src) {
        switch (width) {
            case 1 -> memOp(false, false, dst.code(), src, 0, 0x0F, signed ? 0xBE : 0xB6);
            case 2 -> memOp(false, false, dst.code(), src, 0, 0x0F, signed ? 0xBF : 0xB7);
            case 4 -> {
                if (signed) {
                    memOp(true, false, dst.code(), src, 0, 0x63);
                } else {
                    memOp(false, false, dst.code(), src, 0, 0x8B);
                }
            }
            case 8 -> memOp(true, false, dst.code(), src, 0, 0x8B);
            default -> throw new IllegalArgumentException("width " + width);
        }
    }

    public void store(int width, Mem dst, Reg src) {
        switch (width) {
            case 1 -> memOp(false, src.code() >= 4 && src.code() < 8, src.code(), dst, 0, 0x88);
            case 2 -> {
                out.emit8(0x66);
                memOp(false, false, src.code(), dst, 0, 0x89);
            }
            case 4 -> memOp(false, false, src.code(), dst, 0, 0x89);
            case 8 -> memOp(true, false, src.code(), dst, 0, 0x89);
            default -> throw new IllegalArgumentException("width " + width);
        }
    }

    public void lea(Reg dst, Mem src) {
        memOp(true, false, dst.code(), src, 0, 0x8D);
    }

    public void movsx8(Reg dst, Reg src) {
        regOp(false, src.code() >= 4 && src.code() < 8, dst.code(), src.code(), 0x0F, 0xBE);
    }

    public void movsx16(Reg dst, Reg src) {
        regOp(false, false, dst.code(), src.code(), 0x0F, 0xBF);
    }

    public void movzx8(Reg dst, Reg src) {
        regOp(false, src.code() >= 4 && src.code() < 8, dst.code(), src.code(), 0x0F, 0xB6);
    }

    public void movzx16(Reg dst, Reg src) {
        regOp(false, false, dst.code(), src.code(), 0x0F, 0xB7);
    }

    public void movsxd(Reg dst, Reg src) {
        regOp(true, false, dst.code(), src.code(), 0x63);
    }

    public void alu(Alu op, boolean wide, Reg dst, Reg src) {
        regOp(wide, false, src.code(), dst.code(), op.rmReg);
    }

    public void alu(Alu op, boolean wide, Mem dst, Reg src) {
        memOp(wide, false, src.code(), dst, 0, op.rmReg);
    }

    public void aluImm(Alu op, boolean wide, Reg dst, int imm) {
        boolean small = imm == (byte) imm;
        regOp(wide, false, op.ext, dst.code(), small ? 0x83 : 0x81);
        emitImm(imm, small);
    }

    public void aluImm(Alu op, boolean wide, Mem dst, int imm) {
        boolean small = imm == (byte) imm;
        memOp(wide, false, op.ext, dst, small ? 1 : 4, small ? 0x83 : 0x81);
        emitImm(imm, small);
    }

    public void cmpByte(Mem m, int imm8) {
        memOp(false, false, 7, m, 1, 0x80);
        out.emit8(imm8);
    }

    public void movByte(Mem m, int imm8) {
        memOp(false, false, 0, m, 1, 0xC6);
        out.emit8(imm8);
    }

    public void imul(boolean wide, Reg dst, Reg src) {
        regOp(wide, false, dst.code(), src.code(), 0x0F, 0xAF);
    }

    public void idiv(boolean wide, Reg divisor) {
        regOp(wide, false, 7, divisor.code(), 0xF7);
    }

    public void neg(boolean wide, Reg r) {
        regOp(wide, false, 3, r.code(), 0xF7);
    }

    public void neg(boolean wide, Mem m) {
        memOp(wide, false, 3, m, 0, 0xF7);
    }

    public void not(boolean wide, Reg r) {
        regOp(wide, false, 2, r.code(), 0xF7);
    }

    /** Shifts {@code r} by {@code cl}; the CPU masks the count exactly as the JVM requires. */
    public void shiftCl(Shift kind, boolean wide, Reg r) {
        regOp(wide, false, kind.ext, r.code(), 0xD3);
    }

    /** Shifts {@code r} by a constant; the count is masked by the CPU like {@link #shiftCl}. */
    public void shiftImm(Shift kind, boolean wide, Reg r, int count) {
        regOp(wide, false, kind.ext, r.code(), 0xC1);
        out.emit8(count);
    }

    public void syscall() {
        out.emit8(0x0F);
        out.emit8(0x05);
    }

    public void cdq() {
        out.emit8(0x99);
    }

    public void cqo() {
        out.emit8(0x48);
        out.emit8(0x99);
    }

    public void test(boolean wide, Reg a, Reg b) {
        regOp(wide, false, b.code(), a.code(), 0x85);
    }

    public void setcc(Cond cond, Reg dst) {
        regOp(false, dst.code() >= 4 && dst.code() < 8, 0, dst.code(), 0x0F, 0x90 | cond.code());
    }

    public void hlt() {
        out.emit8(0xF4);
    }

    public void cli() {
        out.emit8(0xFA);
    }

    public void sti() {
        out.emit8(0xFB);
    }

    public void pause() {
        out.emit8(0xF3);
        out.emit8(0x90);
    }

    /** {@code mov sreg, src} with clang's 0x66 prefix; sreg is 0 es, 2 ss, 3 ds, 4 fs, 5 gs. */
    public void movToSegment(int sreg, Reg src) {
        out.emit8(0x66);
        regOp(false, false, sreg, src.code(), 0x8E);
    }

    /** Far return: pops rip, then cs. The only way to reload cs in long mode without a far call. */
    public void retfq() {
        out.emit8(0x48);
        out.emit8(0xCB);
    }

    public void ltr(Reg selector) {
        regOp(false, false, 3, selector.code(), 0x0F, 0x00);
    }

    /** {@code lea dst, [rip + label]}. */
    public void lea(Reg dst, Label target) {
        rex(true, dst.code(), 0, 0, false);
        out.emit8(0x8D);
        out.emit8((dst.code() & 7) << 3 | 0b101);
        rel32(target);
    }

    public void lidt(Mem descriptor) {
        memOp(false, false, 3, descriptor, 0, 0x0F, 0x01);
    }

    public void lgdt(Mem descriptor) {
        memOp(false, false, 2, descriptor, 0, 0x0F, 0x01);
    }

    /** {@code lock cmpxchg byte [m], src}: if [m] equals al, stores src and sets ZF; else loads [m] into al. */
    public void lockCmpxchgByte(Mem m, Reg src) {
        out.emit8(0xF0);
        memOp(false, src.code() >= 4 && src.code() < 8, src.code(), m, 0, 0x0F, 0xB0);
    }

    /** {@code lock cmpxchg [m], src}: if [m] equals rax, stores src and sets ZF; else loads [m] into rax. */
    public void lockCmpxchg(boolean wide, Mem m, Reg src) {
        out.emit8(0xF0);
        memOp(wide, false, src.code(), m, 0, 0x0F, 0xB1);
    }

    /** {@code xchg [m], src}: atomic without a lock prefix, which xchg with memory implies. */
    public void xchg(boolean wide, Mem m, Reg src) {
        memOp(wide, false, src.code(), m, 0, 0x87);
    }

    public void invlpg(Mem address) {
        memOp(false, false, 7, address, 0, 0x0F, 0x01);
    }

    /** {@code mov dst, crN}; always 64-bit in long mode, no REX.W needed. */
    public void readCr(int cr, Reg dst) {
        regOp(false, false, cr, dst.code(), 0x0F, 0x20);
    }

    /** {@code mov crN, src}. */
    public void writeCr(int cr, Reg src) {
        regOp(false, false, cr, src.code(), 0x0F, 0x22);
    }

    /** {@code fxsave64 [m]}: the x87 and SSE state, 512 bytes at a 16-byte-aligned address. */
    public void fxsave(Mem address) {
        memOp(true, false, 0, address, 0, 0x0F, 0xAE);
    }

    /** {@code fxrstor64 [m]}. */
    public void fxrstor(Mem address) {
        memOp(true, false, 1, address, 0, 0x0F, 0xAE);
    }

    /** {@code movq xmm, r}, or {@code movd} for the low 32 bits unless {@code wide}. */
    public void movToXmm(boolean wide, int xmm, Reg src) {
        out.emit8(0x66);
        regOp(wide, false, xmm, src.code(), 0x0F, 0x6E);
    }

    /** {@code movq r, xmm}, or {@code movd} unless {@code wide}. */
    public void movFromXmm(boolean wide, Reg dst, int xmm) {
        out.emit8(0x66);
        regOp(wide, false, xmm, dst.code(), 0x0F, 0x7E);
    }

    /** {@code addsd dst, src} and the rest on doubles, or {@code addss} and the rest on floats. */
    public void sse(Sse op, boolean dbl, int dst, int src) {
        out.emit8(dbl ? 0xF2 : 0xF3);
        regOp(false, false, dst, src, 0x0F, op.opcode);
    }

    /** {@code ucomisd a, b} or {@code ucomiss}: ZF, PF and CF all set if either is NaN. */
    public void ucomis(boolean dbl, int a, int b) {
        if (dbl) {
            out.emit8(0x66);
        }
        regOp(false, false, a, b, 0x0F, 0x2E);
    }

    /** {@code cvtsi2sd xmm, r} or {@code cvtsi2ss}, from all 64 bits if {@code wide}. */
    public void cvtsi2s(boolean dbl, boolean wide, int xmm, Reg src) {
        out.emit8(dbl ? 0xF2 : 0xF3);
        regOp(wide, false, xmm, src.code(), 0x0F, 0x2A);
    }

    /**
     * {@code cvttsd2si r, xmm} or {@code cvttss2si}, truncating: NaN and anything out of range
     * give the most negative value.
     */
    public void cvtts2si(boolean dbl, boolean wide, Reg dst, int xmm) {
        out.emit8(dbl ? 0xF2 : 0xF3);
        regOp(wide, false, dst.code(), xmm, 0x0F, 0x2C);
    }

    /** {@code cvtsd2ss dst, src} from a double, or {@code cvtss2sd} from a float. */
    public void cvts2s(boolean fromDouble, int dst, int src) {
        out.emit8(fromDouble ? 0xF2 : 0xF3);
        regOp(false, false, dst, src, 0x0F, 0x5A);
    }

    /** {@code ldmxcsr [m]}. */
    public void ldmxcsr(Mem address) {
        memOp(false, false, 2, address, 0, 0x0F, 0xAE);
    }

    /** {@code fninit}: the x87 control word as the CPU resets it, and an empty register stack. */
    public void fninit() {
        out.emit8(0xDB);
        out.emit8(0xE3);
    }

    public void rdmsr() {
        out.emit8(0x0F);
        out.emit8(0x32);
    }

    public void wrmsr() {
        out.emit8(0x0F);
        out.emit8(0x30);
    }

    public void iretq() {
        out.emit8(0x48);
        out.emit8(0xCF);
    }

    public void swapgs() {
        out.emit8(0x0F);
        out.emit8(0x01);
        out.emit8(0xF8);
    }

    public void sysretq() {
        out.emit8(0x48);
        out.emit8(0x0F);
        out.emit8(0x07);
    }

    public void cpuid() {
        out.emit8(0x0F);
        out.emit8(0xA2);
    }

    public void rdtsc() {
        out.emit8(0x0F);
        out.emit8(0x31);
    }

    public void pushfq() {
        out.emit8(0x9C);
    }

    public void popfq() {
        out.emit8(0x9D);
    }

    public void std() {
        out.emit8(0xFD);
    }

    public void cld() {
        out.emit8(0xFC);
    }

    /** Copies rcx bytes from [rsi] to [rdi], in the direction the DF flag says. */
    public void repMovsb() {
        out.emit8(0xF3);
        out.emit8(0xA4);
    }

    /** Copies rcx eight-byte words from [rsi] to [rdi], in the direction the DF flag says. */
    public void repMovsq() {
        out.emit8(0xF3);
        out.emit8(0x48);
        out.emit8(0xA5);
    }

    /** Stores rax into rcx eight-byte words at [rdi]. */
    public void repStosq() {
        out.emit8(0xF3);
        out.emit8(0x48);
        out.emit8(0xAB);
    }

    /** Stores al into rcx bytes at [rdi]. */
    public void repStosb() {
        out.emit8(0xF3);
        out.emit8(0xAA);
    }

    public void ud2() {
        out.emit8(0x0F);
        out.emit8(0x0B);
    }

    public void int3() {
        out.emit8(0xCC);
    }

    /** {@code in al/ax/eax, dx}. */
    public void in(int width) {
        portOp(width, 0xEC);
    }

    /** {@code out dx, al/ax/eax}. */
    public void out(int width) {
        portOp(width, 0xEE);
    }

    private void portOp(int width, int base) {
        switch (width) {
            case 1 -> out.emit8(base);
            case 2 -> {
                out.emit8(0x66);
                out.emit8(base + 1);
            }
            case 4 -> out.emit8(base + 1);
            default -> throw new IllegalArgumentException("width " + width);
        }
    }

    private void emitImm(int imm, boolean small) {
        if (small) {
            out.emit8(imm);
        } else {
            out.emit32(imm);
        }
    }

    private void rex(boolean w, int reg, int index, int base, boolean force) {
        int rex = 0x40 | (w ? 8 : 0) | ((reg >> 3) & 1) << 2 | ((index >> 3) & 1) << 1 | ((base >> 3) & 1);
        if (rex != 0x40 || force) {
            out.emit8(rex);
        }
    }

    private void regOp(boolean w, boolean forceRex, int reg, int rm, int... opcode) {
        rex(w, reg, 0, rm, forceRex);
        for (int b : opcode) {
            out.emit8(b);
        }
        out.emit8(0xC0 | (reg & 7) << 3 | (rm & 7));
    }

    /**
     * Emits opcode + ModRM (+ SIB, displacement) for a memory operand. {@code trailingImm} is the
     * size of any immediate the caller emits afterwards, needed to bias RIP-relative displacements.
     */
    private void memOp(boolean w, boolean forceRex, int reg, Mem m, int trailingImm, int... opcode) {
        if (m.gs()) {
            // Segment override, then ModRM rm=100 with a SIB of no base and no index: [disp32].
            out.emit8(0x65);
            rex(w, reg, 0, 0, forceRex);
            for (int b : opcode) {
                out.emit8(b);
            }
            out.emit8((reg & 7) << 3 | 0b100);
            out.emit8(0x25);
            out.emit32(m.disp());
            return;
        }
        if (m.isRipRelative()) {
            rex(w, reg, 0, 0, forceRex);
            for (int b : opcode) {
                out.emit8(b);
            }
            out.emit8((reg & 7) << 3 | 0b101);
            out.emitReloc(Reloc.Kind.PC32, m.symbol(), m.disp() - 4 - trailingImm);
            return;
        }
        Reg base = m.base();
        Reg index = m.index();
        rex(w, reg, index == null ? 0 : index.code(), base.code(), forceRex);
        for (int b : opcode) {
            out.emit8(b);
        }
        int disp = m.disp();
        int mod;
        if (disp == 0 && base.low3() != 5) {
            mod = 0;
        } else if (disp == (byte) disp) {
            mod = 1;
        } else {
            mod = 2;
        }
        if (index != null || base.low3() == 4) {
            out.emit8(mod << 6 | (reg & 7) << 3 | 0b100);
            int idx = index == null ? 0b100 : index.low3();
            out.emit8(Integer.numberOfTrailingZeros(m.scale()) << 6 | idx << 3 | base.low3());
        } else {
            out.emit8(mod << 6 | (reg & 7) << 3 | base.low3());
        }
        if (mod == 1) {
            out.emit8(disp);
        } else if (mod == 2) {
            out.emit32(disp);
        }
    }
}
