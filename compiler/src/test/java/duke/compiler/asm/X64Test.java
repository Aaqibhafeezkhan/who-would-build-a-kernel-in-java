package duke.compiler.asm;

import static duke.compiler.asm.Reg.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import duke.compiler.image.Reloc;
import duke.compiler.image.Section;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

/** Expected bytes come from {@code clang -target x86_64-unknown-elf} on the Intel-syntax text shown. */
class X64Test {

    private static Arguments c(String intel, String hex, Consumer<X64> emit) {
        return Arguments.of(intel, hex, emit);
    }

    static Stream<Arguments> encodings() {
        return Stream.of(
                c("push rax", "50", a -> a.push(RAX)),
                c("push r12", "41 54", a -> a.push(R12)),
                c("pop rbx", "5b", a -> a.pop(RBX)),
                c("pop r15", "41 5f", a -> a.pop(R15)),
                c("push qword ptr [rbp - 8]", "ff 75 f8", a -> a.push(Mem.at(RBP, -8))),
                c("push qword ptr [rbp + 24]", "ff 75 18", a -> a.push(Mem.at(RBP, 24))),
                c("push qword ptr [rsp]", "ff 34 24", a -> a.push(Mem.at(RSP))),
                c("push qword ptr [rsp + 8]", "ff 74 24 08", a -> a.push(Mem.at(RSP, 8))),
                c("pop qword ptr [rbp - 16]", "8f 45 f0", a -> a.pop(Mem.at(RBP, -16))),
                c("pop qword ptr [r13]", "41 8f 45 00", a -> a.pop(Mem.at(R13))),
                c("push 5", "6a 05", a -> a.pushImm(5)),
                c("push -1", "6a ff", a -> a.pushImm(-1)),
                c("push 1000", "68 e8 03 00 00", a -> a.pushImm(1000)),
                c("mov rax, rcx", "48 89 c8", a -> a.mov(RAX, RCX)),
                c("mov r9, rsp", "49 89 e1", a -> a.mov(R9, RSP)),
                c("mov eax, ecx", "89 c8", a -> a.mov32(RAX, RCX)),
                c("mov eax, 0x12345678", "b8 78 56 34 12", a -> a.movImm32(RAX, 0x12345678)),
                c("mov r10d, 7", "41 ba 07 00 00 00", a -> a.movImm32(R10, 7)),
                c("movabs rax, 0x123456789a", "48 b8 9a 78 56 34 12 00 00 00", a -> a.movImm64(RAX, 0x123456789aL)),
                c("movzx eax, byte ptr [rax + 16]", "0f b6 40 10", a -> a.load(1, false, RAX, Mem.at(RAX, 16))),
                c("movsx ecx, byte ptr [rax + rcx + 16]", "0f be 4c 08 10", a -> a.load(1, true, RCX, Mem.at(RAX, RCX, 1, 16))),
                c("movzx eax, word ptr [rax + rcx*2 + 16]", "0f b7 44 48 10", a -> a.load(2, false, RAX, Mem.at(RAX, RCX, 2, 16))),
                c("movsx eax, word ptr [r12 + 4]", "41 0f bf 44 24 04", a -> a.load(2, true, RAX, Mem.at(R12, 4))),
                c("mov eax, dword ptr [rax + rcx*4 + 16]", "8b 44 88 10", a -> a.load(4, false, RAX, Mem.at(RAX, RCX, 4, 16))),
                c("movsxd rax, dword ptr [rbp - 8]", "48 63 45 f8", a -> a.load(4, true, RAX, Mem.at(RBP, -8))),
                c("mov rax, qword ptr [rax + rcx*8 + 16]", "48 8b 44 c8 10", a -> a.load(8, false, RAX, Mem.at(RAX, RCX, 8, 16))),
                c("mov rax, qword ptr [r13 + 0x1000]", "49 8b 85 00 10 00 00", a -> a.load(8, false, RAX, Mem.at(R13, 0x1000))),
                c("mov byte ptr [rax + rcx + 16], dl", "88 54 08 10", a -> a.store(1, Mem.at(RAX, RCX, 1, 16), RDX)),
                c("mov byte ptr [rax], sil", "40 88 30", a -> a.store(1, Mem.at(RAX), RSI)),
                c("mov word ptr [rax + rcx*2 + 16], dx", "66 89 54 48 10", a -> a.store(2, Mem.at(RAX, RCX, 2, 16), RDX)),
                c("mov dword ptr [rcx + 8], eax", "89 41 08", a -> a.store(4, Mem.at(RCX, 8), RAX)),
                c("mov qword ptr [rax + rcx*8 + 16], rdx", "48 89 54 c8 10", a -> a.store(8, Mem.at(RAX, RCX, 8, 16), RDX)),
                c("lea rax, [rbp - 24]", "48 8d 45 e8", a -> a.lea(RAX, Mem.at(RBP, -24))),
                c("movsx eax, al", "0f be c0", a -> a.movsx8(RAX, RAX)),
                c("movsx eax, ax", "0f bf c0", a -> a.movsx16(RAX, RAX)),
                c("movzx eax, ax", "0f b7 c0", a -> a.movzx16(RAX, RAX)),
                c("movzx eax, sil", "40 0f b6 c6", a -> a.movzx8(RAX, RSI)),
                c("movsxd rax, eax", "48 63 c0", a -> a.movsxd(RAX, RAX)),
                c("add eax, ecx", "01 c8", a -> a.alu(X64.Alu.ADD, false, RAX, RCX)),
                c("add rax, rcx", "48 01 c8", a -> a.alu(X64.Alu.ADD, true, RAX, RCX)),
                c("sub eax, ecx", "29 c8", a -> a.alu(X64.Alu.SUB, false, RAX, RCX)),
                c("and rax, rcx", "48 21 c8", a -> a.alu(X64.Alu.AND, true, RAX, RCX)),
                c("or eax, ecx", "09 c8", a -> a.alu(X64.Alu.OR, false, RAX, RCX)),
                c("xor rax, rcx", "48 31 c8", a -> a.alu(X64.Alu.XOR, true, RAX, RCX)),
                c("cmp eax, ecx", "39 c8", a -> a.alu(X64.Alu.CMP, false, RAX, RCX)),
                c("cmp r8, r9", "4d 39 c8", a -> a.alu(X64.Alu.CMP, true, R8, R9)),
                c("add rsp, 16", "48 83 c4 10", a -> a.aluImm(X64.Alu.ADD, true, RSP, 16)),
                c("sub rsp, 0x200", "48 81 ec 00 02 00 00", a -> a.aluImm(X64.Alu.SUB, true, RSP, 0x200)),
                c("cmp eax, -1", "83 f8 ff", a -> a.aluImm(X64.Alu.CMP, false, RAX, -1)),
                c("add dword ptr [rbp - 8], 1", "83 45 f8 01", a -> a.aluImm(X64.Alu.ADD, false, Mem.at(RBP, -8), 1)),
                c("add dword ptr [rbp - 8], 1000", "81 45 f8 e8 03 00 00", a -> a.aluImm(X64.Alu.ADD, false, Mem.at(RBP, -8), 1000)),
                c("add qword ptr [rsp], rcx", "48 01 0c 24", a -> a.alu(X64.Alu.ADD, true, Mem.at(RSP), RCX)),
                c("imul eax, ecx", "0f af c1", a -> a.imul(false, RAX, RCX)),
                c("imul rax, rcx", "48 0f af c1", a -> a.imul(true, RAX, RCX)),
                c("idiv ecx", "f7 f9", a -> a.idiv(false, RCX)),
                c("idiv rcx", "48 f7 f9", a -> a.idiv(true, RCX)),
                c("neg eax", "f7 d8", a -> a.neg(false, RAX)),
                c("neg qword ptr [rsp]", "48 f7 1c 24", a -> a.neg(true, Mem.at(RSP))),
                c("not rax", "48 f7 d0", a -> a.not(true, RAX)),
                c("shl eax, cl", "d3 e0", a -> a.shiftCl(X64.Shift.SHL, false, RAX)),
                c("shr rax, cl", "48 d3 e8", a -> a.shiftCl(X64.Shift.SHR, true, RAX)),
                c("sar eax, cl", "d3 f8", a -> a.shiftCl(X64.Shift.SAR, false, RAX)),
                c("shl rax, 3", "48 c1 e0 03", a -> a.shiftImm(X64.Shift.SHL, true, RAX, 3)),
                c("sar r9, 1", "49 c1 f9 01", a -> a.shiftImm(X64.Shift.SAR, true, R9, 1)),
                c("shr eax, 31", "c1 e8 1f", a -> a.shiftImm(X64.Shift.SHR, false, RAX, 31)),
                c("syscall", "0f 05", X64::syscall),
                c("cmp qword ptr gs:[8], rsp", "65 48 39 24 25 08 00 00 00", a -> a.alu(X64.Alu.CMP, true, Mem.gs(8), RSP)),
                c("mov rax, qword ptr gs:[24]", "65 48 8b 04 25 18 00 00 00", a -> a.load(8, false, RAX, Mem.gs(24))),
                c("mov qword ptr gs:[8], rax", "65 48 89 04 25 08 00 00 00", a -> a.store(8, Mem.gs(8), RAX)),
                c("cmp qword ptr gs:[8], -1", "65 48 83 3c 25 08 00 00 00 ff", a -> a.aluImm(X64.Alu.CMP, true, Mem.gs(8), -1)),
                c("cdq", "99", X64::cdq),
                c("cqo", "48 99", X64::cqo),
                c("test eax, eax", "85 c0", a -> a.test(false, RAX, RAX)),
                c("test rax, rax", "48 85 c0", a -> a.test(true, RAX, RAX)),
                c("setg al", "0f 9f c0", a -> a.setcc(Cond.G, RAX)),
                c("setl cl", "0f 9c c1", a -> a.setcc(Cond.L, RCX)),
                c("setne sil", "40 0f 95 c6", a -> a.setcc(Cond.NE, RSI)),
                c("hlt", "f4", X64::hlt),
                c("cli", "fa", X64::cli),
                c("sti", "fb", X64::sti),
                c("pause", "f3 90", X64::pause),
                c("xchg dword ptr [rsi], eax", "87 06", a -> a.xchg(false, Mem.at(RSI), RAX)),
                c("xchg dword ptr [r8], eax", "41 87 00", a -> a.xchg(false, Mem.at(R8), RAX)),
                c("xchg qword ptr [rsi], rax", "48 87 06", a -> a.xchg(true, Mem.at(RSI), RAX)),
                c("lock cmpxchg byte ptr [rsi], cl", "f0 0f b0 0e", a -> a.lockCmpxchgByte(Mem.at(RSI), RCX)),
                c("lock cmpxchg byte ptr [r9 + 8], cl", "f0 41 0f b0 49 08", a -> a.lockCmpxchgByte(Mem.at(R9, 8), RCX)),
                c("lock cmpxchg qword ptr [rsi], rcx", "f0 48 0f b1 0e", a -> a.lockCmpxchg(true, Mem.at(RSI), RCX)),
                c("lock cmpxchg qword ptr [r9 + 8], rcx", "f0 49 0f b1 49 08", a -> a.lockCmpxchg(true, Mem.at(R9, 8), RCX)),
                c("ud2", "0f 0b", X64::ud2),
                c("int3", "cc", X64::int3),
                c("in al, dx", "ec", a -> a.in(1)),
                c("in ax, dx", "66 ed", a -> a.in(2)),
                c("in eax, dx", "ed", a -> a.in(4)),
                c("out dx, al", "ee", a -> a.out(1)),
                c("out dx, ax", "66 ef", a -> a.out(2)),
                c("out dx, eax", "ef", a -> a.out(4)),
                c("call qword ptr [rax + 48]", "ff 50 30", a -> a.call(Mem.at(RAX, 48))),
                c("call qword ptr [rax + 0x1000]", "ff 90 00 10 00 00", a -> a.call(Mem.at(RAX, 0x1000))),
                c("call qword ptr [r11 + 8]", "41 ff 53 08", a -> a.call(Mem.at(R11, 8))),
                c("cmp byte ptr [rax + 8], 0", "80 78 08 00", a -> a.cmpByte(Mem.at(RAX, 8), 0)),
                c("mov ds, ax", "66 8e d8", a -> a.movToSegment(3, RAX)),
                c("mov es, ax", "66 8e c0", a -> a.movToSegment(0, RAX)),
                c("mov ss, ax", "66 8e d0", a -> a.movToSegment(2, RAX)),
                c("mov fs, ax", "66 8e e0", a -> a.movToSegment(4, RAX)),
                c("mov gs, ax", "66 8e e8", a -> a.movToSegment(5, RAX)),
                c("retfq", "48 cb", X64::retfq),
                c("ltr ax", "0f 00 d8", a -> a.ltr(RAX)),
                c("lidt [rax]", "0f 01 18", a -> a.lidt(Mem.at(RAX))),
                c("lgdt [rax]", "0f 01 10", a -> a.lgdt(Mem.at(RAX))),
                c("invlpg byte ptr [rax]", "0f 01 38", a -> a.invlpg(Mem.at(RAX))),
                c("fxsave64 [rax]", "48 0f ae 00", a -> a.fxsave(Mem.at(RAX))),
                c("fxrstor64 [rdi]", "48 0f ae 0f", a -> a.fxrstor(Mem.at(RDI))),
                c("ldmxcsr dword ptr [rsp]", "0f ae 14 24", a -> a.ldmxcsr(Mem.at(RSP))),
                c("fninit", "db e3", X64::fninit),
                c("fld qword ptr [rsp + 24]", "dd 44 24 18", a -> a.fld(true, Mem.at(RSP, 24))),
                c("fld dword ptr [rsp]", "d9 04 24", a -> a.fld(false, Mem.at(RSP))),
                c("fld qword ptr [r12]", "41 dd 04 24", a -> a.fld(true, Mem.at(R12))),
                c("fstp qword ptr [rsp + 8]", "dd 5c 24 08", a -> a.fstp(true, Mem.at(RSP, 8))),
                c("fstp dword ptr [rsp]", "d9 1c 24", a -> a.fstp(false, Mem.at(RSP))),
                c("fstp st(1)", "dd d9", a -> a.fstp(1)),
                c("fprem", "d9 f8", X64::fprem),
                c("fnstsw ax", "df e0", X64::fnstswAx),
                c("movq xmm0, rax", "66 48 0f 6e c0", a -> a.movToXmm(true, 0, RAX)),
                c("movd xmm1, ecx", "66 0f 6e c9", a -> a.movToXmm(false, 1, RCX)),
                c("movq rax, xmm0", "66 48 0f 7e c0", a -> a.movFromXmm(true, RAX, 0)),
                c("movd eax, xmm1", "66 0f 7e c8", a -> a.movFromXmm(false, RAX, 1)),
                c("movq xmm9, r10", "66 4d 0f 6e ca", a -> a.movToXmm(true, 9, R10)),
                c("movq r11, xmm12", "66 4d 0f 7e e3", a -> a.movFromXmm(true, R11, 12)),
                c("addsd xmm0, xmm1", "f2 0f 58 c1", a -> a.sse(X64.Sse.ADD, true, 0, 1)),
                c("subss xmm0, xmm1", "f3 0f 5c c1", a -> a.sse(X64.Sse.SUB, false, 0, 1)),
                c("mulsd xmm8, xmm1", "f2 44 0f 59 c1", a -> a.sse(X64.Sse.MUL, true, 8, 1)),
                c("divss xmm0, xmm15", "f3 41 0f 5e c7", a -> a.sse(X64.Sse.DIV, false, 0, 15)),
                c("ucomisd xmm0, xmm1", "66 0f 2e c1", a -> a.ucomis(true, 0, 1)),
                c("ucomiss xmm0, xmm0", "0f 2e c0", a -> a.ucomis(false, 0, 0)),
                c("cvtsi2sd xmm0, rax", "f2 48 0f 2a c0", a -> a.cvtsi2s(true, true, 0, RAX)),
                c("cvtsi2ss xmm1, ecx", "f3 0f 2a c9", a -> a.cvtsi2s(false, false, 1, RCX)),
                c("cvttsd2si eax, xmm0", "f2 0f 2c c0", a -> a.cvtts2si(true, false, RAX, 0)),
                c("cvttss2si rax, xmm1", "f3 48 0f 2c c1", a -> a.cvtts2si(false, true, RAX, 1)),
                c("cvttsd2si r9, xmm10", "f2 4d 0f 2c ca", a -> a.cvtts2si(true, true, R9, 10)),
                c("cvtss2sd xmm0, xmm0", "f3 0f 5a c0", a -> a.cvts2s(false, 0, 0)),
                c("cvtsd2ss xmm1, xmm0", "f2 0f 5a c8", a -> a.cvts2s(true, 1, 0)),
                c("mov rax, cr0", "0f 20 c0", a -> a.readCr(0, RAX)),
                c("mov rax, cr2", "0f 20 d0", a -> a.readCr(2, RAX)),
                c("mov rax, cr3", "0f 20 d8", a -> a.readCr(3, RAX)),
                c("mov rax, cr4", "0f 20 e0", a -> a.readCr(4, RAX)),
                c("mov cr0, rax", "0f 22 c0", a -> a.writeCr(0, RAX)),
                c("mov cr3, rax", "0f 22 d8", a -> a.writeCr(3, RAX)),
                c("mov cr4, rax", "0f 22 e0", a -> a.writeCr(4, RAX)),
                c("rdmsr", "0f 32", X64::rdmsr),
                c("wrmsr", "0f 30", X64::wrmsr),
                c("iretq", "48 cf", X64::iretq),
                c("sysretq", "48 0f 07", X64::sysretq),
                c("swapgs", "0f 01 f8", X64::swapgs),
                c("cpuid", "0f a2", X64::cpuid),
                c("rdtsc", "0f 31", X64::rdtsc),
                c("pushfq", "9c", X64::pushfq),
                c("popfq", "9d", X64::popfq),
                c("push rdi", "57", a -> a.push(RDI)),
                c("pop r8", "41 58", a -> a.pop(R8)),
                c("jmp rax", "ff e0", a -> a.jmp(RAX)),
                c("jmp r9", "41 ff e1", a -> a.jmp(R9)),
                c("mov rbp, r8", "4c 89 c5", a -> a.mov(RBP, R8)),
                c("mov rsp, r9", "4c 89 cc", a -> a.mov(RSP, R9)),
                c("std", "fd", X64::std),
                c("cld", "fc", X64::cld),
                c("rep movsb", "f3 a4", X64::repMovsb),
                c("rep movsq", "f3 48 a5", X64::repMovsq),
                c("rep stosb", "f3 aa", X64::repStosb),
                c("rep stosq", "f3 48 ab", X64::repStosq),
                c("lea rsi, [rsi + rcx - 1]", "48 8d 74 0e ff", a -> a.lea(RSI, Mem.at(RSI, RCX, 1, -1))),
                c("leave", "c9", X64::leave),
                c("ret", "c3", X64::ret));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("encodings")
    void encodes(String intel, String hex, Consumer<X64> emit) {
        Section s = new Section(".text", Section.Kind.TEXT);
        emit.accept(new X64(s));
        assertEquals(hex.replace(" ", ""), HexFormat.of().formatHex(s.toByteArray()), intel);
    }

    @Test
    void labelsPatchForwardAndBackward() {
        Section s = new Section(".text", Section.Kind.TEXT);
        X64 a = new X64(s);
        X64.Label top = new X64.Label();
        X64.Label end = new X64.Label();
        a.bind(top);
        a.jcc(Cond.E, end);   // 0: 0f 84 rel32 -> end (offset 11)
        a.jmp(top);           // 6: e9 rel32 -> 0
        a.bind(end);
        a.ret();              // 11
        assertEquals("0f8405000000" + "e9f5ffffff" + "c3", HexFormat.of().formatHex(s.toByteArray()));
    }

    @Test
    void leaOfLabelIsRipRelative() {
        Section s = new Section(".text", Section.Kind.TEXT);
        X64 a = new X64(s);
        X64.Label target = new X64.Label();
        a.lea(RAX, target);   // 0: 48 8d 05 rel32, ends at 7
        a.ret();              // 7
        a.bind(target);       // 8
        // clang: lea rax, [rip + 1] -> 48 8d 05 01 00 00 00
        assertEquals("488d0501000000" + "c3", HexFormat.of().formatHex(s.toByteArray()));
    }

    @Test
    void ripRelativeOperandsAccountForTrailingImmediate() {
        Section s = new Section(".text", Section.Kind.TEXT);
        X64 a = new X64(s);
        a.lea(RAX, Mem.rip("sym"));
        a.aluImm(X64.Alu.CMP, false, Mem.rip("sym", 8), 1000);
        a.call("fn");
        a.cmpByte(Mem.rip("flag"), 0);
        a.movByte(Mem.rip("flag"), 1);
        assertEquals(new Reloc(3, Reloc.Kind.PC32, "sym", -4), s.relocs().get(0));
        assertEquals(new Reloc(9, Reloc.Kind.PC32, "sym", 8 - 4 - 4), s.relocs().get(1));
        assertEquals(new Reloc(18, Reloc.Kind.PC32, "fn", -4), s.relocs().get(2));
        // clang: "80 3d <rel32> 00" and "c6 05 <rel32> 01"
        assertEquals(new Reloc(24, Reloc.Kind.PC32, "flag", -4 - 1), s.relocs().get(3));
        assertEquals(new Reloc(31, Reloc.Kind.PC32, "flag", -4 - 1), s.relocs().get(4));
        byte[] bytes = s.toByteArray();
        assertEquals("803d", HexFormat.of().formatHex(bytes, 22, 24));
        assertEquals("00c605", HexFormat.of().formatHex(bytes, 28, 31));
        assertEquals("01", HexFormat.of().formatHex(bytes, 35, 36));
    }
}
