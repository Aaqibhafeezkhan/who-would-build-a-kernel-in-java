package duke.compiler.image;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DWARF 4 debug sections so gdb can map code to Java source lines: one compile unit spanning the
 * text, a subprogram per method and a line program built from each method's LineNumberTable. Only
 * for tooling; nothing in the kernel reads them.
 */
public final class Dwarf {

    /** A compiled method: its symbol, source path relative to a source root, and (code offset, line) rows. */
    public record Function(String symbol, String path, List<int[]> rows) {}

    private static final int DW_LANG_JAVA = 0x0b;
    private static final int LINE_BASE = -5;
    private static final int LINE_RANGE = 14;
    private static final int OPCODE_BASE = 13;
    private static final int DW_LNS_COPY = 1, DW_LNS_ADVANCE_PC = 2, DW_LNS_ADVANCE_LINE = 3,
            DW_LNS_SET_FILE = 4, DW_LNS_SET_PROLOGUE_END = 10;
    private static final int DW_LNE_END_SEQUENCE = 1, DW_LNE_SET_ADDRESS = 2;

    private Dwarf() {
    }

    /** Section name to contents, in the order they should appear. */
    public static Map<String, byte[]> sections(Image.Linked linked, List<Function> functions) {
        Map<String, Image.Symbol> symbols = new LinkedHashMap<>();
        for (Image.Symbol s : linked.symbols()) {
            symbols.put(s.name(), s);
        }
        List<Function> sorted = new ArrayList<>();
        for (Function f : functions) {
            if (!f.rows().isEmpty()) {
                sorted.add(f);
            }
        }
        sorted.sort(Comparator.comparingLong(f -> linked.address(f.symbol())));
        Image.Placed text = linked.sections().stream()
                .filter(p -> p.section().kind() == Section.Kind.TEXT).findFirst().orElseThrow();

        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put(".debug_abbrev", abbrev());
        out.put(".debug_info", info(linked, symbols, sorted, text));
        out.put(".debug_line", line(linked, symbols, sorted));
        return out;
    }

    private static byte[] abbrev() {
        Bytes b = new Bytes();
        b.uleb(1).uleb(0x11).u8(1);              // DW_TAG_compile_unit, has children
        b.uleb(0x03).uleb(0x08);                 // DW_AT_name, DW_FORM_string
        b.uleb(0x13).uleb(0x05);                 // DW_AT_language, DW_FORM_data2
        b.uleb(0x10).uleb(0x17);                 // DW_AT_stmt_list, DW_FORM_sec_offset
        b.uleb(0x11).uleb(0x01);                 // DW_AT_low_pc, DW_FORM_addr
        b.uleb(0x12).uleb(0x07);                 // DW_AT_high_pc, DW_FORM_data8 (a length)
        b.uleb(0).uleb(0);
        b.uleb(2).uleb(0x2e).u8(0);              // DW_TAG_subprogram, no children
        b.uleb(0x03).uleb(0x08);                 // DW_AT_name, DW_FORM_string
        b.uleb(0x11).uleb(0x01);                 // DW_AT_low_pc, DW_FORM_addr
        b.uleb(0x12).uleb(0x06);                 // DW_AT_high_pc, DW_FORM_data4 (a length)
        b.uleb(0).uleb(0);
        b.uleb(0);
        return b.bytes();
    }

    private static byte[] info(Image.Linked linked, Map<String, Image.Symbol> symbols, List<Function> functions,
            Image.Placed text) {
        Bytes body = new Bytes();
        body.u16(4).u32(0).u8(8);                // version, abbrev offset, address size
        body.uleb(1).string("duke").u16(DW_LANG_JAVA).u32(0).u64(text.address()).u64(text.section().size());
        for (Function f : functions) {
            body.uleb(2).string(f.symbol()).u64(linked.address(f.symbol())).u32(symbols.get(f.symbol()).size());
        }
        body.u8(0);
        return new Bytes().u32(body.size()).append(body).bytes();
    }

    private static byte[] line(Image.Linked linked, Map<String, Image.Symbol> symbols, List<Function> functions) {
        List<String> directories = new ArrayList<>();
        Map<String, Integer> files = new LinkedHashMap<>();
        Bytes fileTable = new Bytes();
        for (Function f : functions) {
            if (files.containsKey(f.path())) {
                continue;
            }
            int slash = f.path().lastIndexOf('/');
            String dir = slash < 0 ? "" : f.path().substring(0, slash);
            int dirIndex = 0;
            if (!dir.isEmpty()) {
                if (!directories.contains(dir)) {
                    directories.add(dir);
                }
                dirIndex = directories.indexOf(dir) + 1;
            }
            files.put(f.path(), files.size() + 1);
            fileTable.string(f.path().substring(slash + 1)).uleb(dirIndex).uleb(0).uleb(0);
        }

        Bytes header = new Bytes();
        header.u8(1).u8(1).u8(1);                // min instruction length, max ops per instruction, default is_stmt
        header.u8(LINE_BASE).u8(LINE_RANGE).u8(OPCODE_BASE);
        for (int operands : new int[] {0, 1, 1, 1, 1, 0, 0, 0, 1, 0, 0, 1}) {
            header.u8(operands);
        }
        for (String dir : directories) {
            header.string(dir);
        }
        header.u8(0);
        header.append(fileTable).u8(0);

        Bytes program = new Bytes();
        for (Function f : functions) {
            sequence(program, linked.address(f.symbol()), symbols.get(f.symbol()).size(), files.get(f.path()),
                    rows(f.rows()));
        }

        Bytes unit = new Bytes().u16(4).u32(header.size()).append(header).append(program);
        return new Bytes().u32(unit.size()).append(unit).bytes();
    }

    /**
     * One row per code offset, keeping the last line given for it. The prologue gets line 0, no
     * source line: given the first line instead, lldb took that whole line for prologue and put a
     * function breakpoint on the second.
     */
    static List<int[]> rows(List<int[]> lines) {
        List<int[]> rows = new ArrayList<>();
        for (int[] row : lines) {
            if (!rows.isEmpty() && rows.getLast()[0] == row[0]) {
                rows.removeLast();
            }
            rows.add(row);
        }
        if (rows.getFirst()[0] != 0) {
            rows.addFirst(new int[] {0, 0});
        }
        return rows;
    }

    private static void sequence(Bytes b, long address, int size, int file, List<int[]> rows) {
        b.u8(0).uleb(9).u8(DW_LNE_SET_ADDRESS).u64(address);
        if (file != 1) {
            b.u8(DW_LNS_SET_FILE).uleb(file);
        }
        int offset = 0;
        int line = 1;
        for (int i = 0; i < rows.size(); i++) {
            int[] row = rows.get(i);
            if (i == 1 && rows.getFirst()[1] == 0) {
                b.u8(DW_LNS_SET_PROLOGUE_END);
            }
            row(b, row[0] - offset, row[1] - line);
            offset = row[0];
            line = row[1];
        }
        if (size > offset) {
            b.u8(DW_LNS_ADVANCE_PC).uleb(size - offset);
        }
        b.u8(0).uleb(1).u8(DW_LNE_END_SEQUENCE);
    }

    /** Appends a row, with a special opcode when the deltas fit in one. */
    private static void row(Bytes b, int addressDelta, int lineDelta) {
        if (lineDelta < LINE_BASE || lineDelta >= LINE_BASE + LINE_RANGE) {
            b.u8(DW_LNS_ADVANCE_LINE).sleb(lineDelta);
            lineDelta = 0;
        }
        int special = (lineDelta - LINE_BASE) + LINE_RANGE * addressDelta + OPCODE_BASE;
        if (special <= 255) {
            b.u8(special);
            return;
        }
        b.u8(DW_LNS_ADVANCE_PC).uleb(addressDelta);
        if (lineDelta == 0) {
            b.u8(DW_LNS_COPY);
        } else {
            b.u8(lineDelta - LINE_BASE + OPCODE_BASE);
        }
    }

    /** Little-endian bytes with DWARF's LEB128 encodings. */
    private static final class Bytes {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Bytes u8(int v) {
            out.write(v);
            return this;
        }

        Bytes u16(int v) {
            return u8(v).u8(v >> 8);
        }

        Bytes u32(long v) {
            return u16((int) v).u16((int) (v >> 16));
        }

        Bytes u64(long v) {
            return u32(v).u32(v >>> 32);
        }

        Bytes uleb(long v) {
            do {
                int low = (int) (v & 0x7f);
                v >>>= 7;
                u8(v != 0 ? low | 0x80 : low);
            } while (v != 0);
            return this;
        }

        Bytes sleb(long v) {
            while (true) {
                int low = (int) (v & 0x7f);
                v >>= 7;
                boolean done = (v == 0 && (low & 0x40) == 0) || (v == -1 && (low & 0x40) != 0);
                u8(done ? low : low | 0x80);
                if (done) {
                    return this;
                }
            }
        }

        Bytes string(String s) {
            out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
            return u8(0);
        }

        Bytes append(Bytes other) {
            out.writeBytes(other.bytes());
            return this;
        }

        int size() {
            return out.size();
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }
}
