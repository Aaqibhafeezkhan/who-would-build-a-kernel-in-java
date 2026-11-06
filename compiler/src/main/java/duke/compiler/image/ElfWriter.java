package duke.compiler.image;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a linked image as a static ELF64 executable. Section headers and a symbol table are
 * included only for tooling (objdump, gdb); the bootloader just reads the program headers.
 */
public final class ElfWriter {

    private static final int EHDR_SIZE = 64;
    private static final int PHDR_SIZE = 56;
    private static final int SHDR_SIZE = 64;
    private static final int SYM_SIZE = 24;

    private static final int PT_LOAD = 1;
    private static final int PF_X = 1, PF_W = 2, PF_R = 4;
    private static final int SHT_PROGBITS = 1, SHT_SYMTAB = 2, SHT_STRTAB = 3, SHT_NOBITS = 8;
    private static final long SHF_WRITE = 1, SHF_ALLOC = 2, SHF_EXECINSTR = 4;

    private record Segment(int flags, long fileOffset, long vaddr, long fileSize, long memSize) {}

    public static byte[] write(Image.Linked linked, long entry) {
        return write(linked, entry, false, Map.of());
    }

    public static byte[] write(Image.Linked linked, long entry, boolean mapHeaders) {
        return write(linked, entry, mapHeaders, Map.of());
    }

    /**
     * With {@code mapHeaders}, the first page of the file (the ELF and program headers) is loaded as
     * a read-only segment on the page below the text. The Linux kernel doesn't need that, but some
     * loaders (Rosetta's, for one) insist that the first PT_LOAD start at file offset 0.
     * {@code debug} sections (name to contents) go in unloaded, after the symbol table.
     */
    public static byte[] write(Image.Linked linked, long entry, boolean mapHeaders, Map<String, byte[]> debug) {
        Map<Section.Kind, Image.Placed> byKind = new HashMap<>();
        for (Image.Placed p : linked.sections()) {
            byKind.put(p.section().kind(), p);
        }
        Image.Placed text = byKind.get(Section.Kind.TEXT);
        Image.Placed rodata = byKind.get(Section.Kind.RODATA);
        Image.Placed data = byKind.get(Section.Kind.DATA);
        Image.Placed bss = byKind.get(Section.Kind.BSS);

        List<Segment> segments = new ArrayList<>();
        if (mapHeaders) {
            segments.add(new Segment(PF_R, 0, text.address() - Image.PAGE, Image.PAGE, Image.PAGE));
        }
        addSegment(segments, PF_R | PF_X, text, text.bytes().length);
        addSegment(segments, PF_R, rodata, rodata.bytes().length);
        long dataMem = bss.address() + bss.section().size() - data.address();
        addSegment(segments, PF_R | PF_W, data, dataMem);

        // Section header string table and symbol table.
        StringTable shstr = new StringTable();
        StringTable str = new StringTable();
        List<Image.Placed> allocSections = List.of(text, rodata, data, bss);
        Map<Section, Integer> shIndex = new HashMap<>();
        for (int i = 0; i < allocSections.size(); i++) {
            shIndex.put(allocSections.get(i).section(), i + 1);
        }

        ByteBuffer symtab = le(SYM_SIZE * (linked.symbols().size() + 1));
        symtab.put(new byte[SYM_SIZE]);
        for (Image.Symbol sym : linked.symbols()) {
            symtab.putInt(str.add(sym.name()));
            int type = sym.type() == Image.SymbolType.FUNC ? 2 : 1;
            symtab.put((byte) ((1 << 4) | type)); // STB_GLOBAL
            symtab.put((byte) 0);
            symtab.putShort((short) (int) shIndex.get(sym.section()));
            symtab.putLong(linked.address(sym.name()));
            symtab.putLong(sym.size());
        }

        long contentEnd = data.fileOffset() + data.bytes().length;
        long symtabOff = Image.alignUp(contentEnd, 8);
        long strtabOff = symtabOff + symtab.capacity();
        byte[] strBytes = str.bytes();
        long debugOff = strtabOff + strBytes.length;
        long[] debugOffsets = new long[debug.size()];
        long next = debugOff;
        int d = 0;
        for (byte[] contents : debug.values()) {
            debugOffsets[d++] = next;
            next += contents.length;
        }
        long shstrOff = next;

        int shNum = 8 + debug.size();
        int shstrIndex = shNum - 1;
        int[] names = new int[shNum];
        names[1] = shstr.add(".text");
        names[2] = shstr.add(".rodata");
        names[3] = shstr.add(".data");
        names[4] = shstr.add(".bss");
        names[5] = shstr.add(".symtab");
        names[6] = shstr.add(".strtab");
        d = 7;
        for (String name : debug.keySet()) {
            names[d++] = shstr.add(name);
        }
        names[shstrIndex] = shstr.add(".shstrtab");
        byte[] shstrBytes = shstr.bytes();
        long shOff = Image.alignUp(shstrOff + shstrBytes.length, 8);

        ByteBuffer out = le((int) (shOff + (long) shNum * SHDR_SIZE));

        // ELF header
        out.put(new byte[] {0x7F, 'E', 'L', 'F', 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        out.putShort((short) 2);   // ET_EXEC
        out.putShort((short) 62);  // EM_X86_64
        out.putInt(1);
        out.putLong(entry);
        out.putLong(EHDR_SIZE);
        out.putLong(shOff);
        out.putInt(0);
        out.putShort((short) EHDR_SIZE);
        out.putShort((short) PHDR_SIZE);
        out.putShort((short) segments.size());
        out.putShort((short) SHDR_SIZE);
        out.putShort((short) shNum);
        out.putShort((short) shstrIndex);

        for (Segment seg : segments) {
            out.putInt(PT_LOAD);
            out.putInt(seg.flags());
            out.putLong(seg.fileOffset());
            out.putLong(seg.vaddr());
            out.putLong(seg.vaddr());
            out.putLong(seg.fileSize());
            out.putLong(seg.memSize());
            out.putLong(Image.PAGE);
        }

        for (Image.Placed p : List.of(text, rodata, data)) {
            out.put((int) p.fileOffset(), p.bytes());
        }
        out.put((int) symtabOff, symtab.array());
        out.put((int) strtabOff, strBytes);
        d = 0;
        for (byte[] contents : debug.values()) {
            out.put((int) debugOffsets[d++], contents);
        }
        out.put((int) shstrOff, shstrBytes);

        out.position((int) shOff);
        out.put(new byte[SHDR_SIZE]);
        sectionHeader(out, names[1], SHT_PROGBITS, SHF_ALLOC | SHF_EXECINSTR, text, text.bytes().length);
        sectionHeader(out, names[2], SHT_PROGBITS, SHF_ALLOC, rodata, rodata.bytes().length);
        sectionHeader(out, names[3], SHT_PROGBITS, SHF_ALLOC | SHF_WRITE, data, data.bytes().length);
        sectionHeader(out, names[4], SHT_NOBITS, SHF_ALLOC | SHF_WRITE, bss, bss.section().size());
        rawHeader(out, names[5], SHT_SYMTAB, 0, 0, symtabOff, symtab.capacity(), 6, 1, 8, SYM_SIZE);
        rawHeader(out, names[6], SHT_STRTAB, 0, 0, strtabOff, strBytes.length, 0, 0, 1, 0);
        d = 0;
        for (byte[] contents : debug.values()) {
            rawHeader(out, names[7 + d], SHT_PROGBITS, 0, 0, debugOffsets[d++], contents.length, 0, 0, 1, 0);
        }
        rawHeader(out, names[shstrIndex], SHT_STRTAB, 0, 0, shstrOff, shstrBytes.length, 0, 0, 1, 0);
        return out.array();
    }

    private static void addSegment(List<Segment> segments, int flags, Image.Placed p, long memSize) {
        if (memSize == 0) {
            return;
        }
        segments.add(new Segment(flags, p.fileOffset(), p.address(), p.bytes().length, memSize));
    }

    private static void sectionHeader(ByteBuffer out, int name, int type, long flags, Image.Placed p, long size) {
        rawHeader(out, name, type, flags, p.address(), p.fileOffset(), size, 0, 0,
                Math.max(1, p.section().alignment()), 0);
    }

    private static void rawHeader(ByteBuffer out, int name, int type, long flags, long addr, long offset,
            long size, int link, int info, long align, long entsize) {
        out.putInt(name);
        out.putInt(type);
        out.putLong(flags);
        out.putLong(addr);
        out.putLong(offset);
        out.putLong(size);
        out.putInt(link);
        out.putInt(info);
        out.putLong(align);
        out.putLong(entsize);
    }

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static final class StringTable {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        StringTable() {
            bytes.write(0);
        }

        int add(String s) {
            int offset = bytes.size();
            bytes.writeBytes(s.getBytes(StandardCharsets.UTF_8));
            bytes.write(0);
            return offset;
        }

        byte[] bytes() {
            return bytes.toByteArray();
        }
    }
}
