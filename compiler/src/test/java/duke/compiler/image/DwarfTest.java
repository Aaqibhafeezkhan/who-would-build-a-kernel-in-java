package duke.compiler.image;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Runs the DWARF line-number state machine over what Dwarf emits and checks the rows that come out. */
class DwarfTest {

    private static final long BASE = 0xffffffff80000000L;

    @Test
    void lineProgramReproducesTheRows() {
        Image image = new Image();
        image.text.align(16);
        for (int i = 0; i < 500; i++) {
            image.text.emit8(0x90);
        }
        image.define("a/B.f()V", image.text, 0, 450, Image.SymbolType.FUNC);
        image.define("a/c/D.g()V", image.text, 450, 50, Image.SymbolType.FUNC);
        Image.Linked linked = image.link(BASE);
        long f = linked.address("a/B.f()V");
        long g = linked.address("a/c/D.g()V");

        List<int[]> fLines = List.of(
                new int[] {5, 10}, new int[] {5, 11},   // two lines at one offset: the later wins
                new int[] {9, 30},                      // too far for a special opcode's line delta
                new int[] {400, 31},                    // too far for its address delta
                new int[] {401, 2});                    // backwards
        List<int[]> gLines = List.<int[]>of(new int[] {0, 7}, new int[] {3, 8});
        byte[] line = Dwarf.sections(linked, List.of(
                new Dwarf.Function("a/c/D.g()V", "a/c/D.java", gLines),
                new Dwarf.Function("a/B.f()V", "a/B.java", fLines))).get(".debug_line");

        assertEquals(List.of(
                row(f, 0, 1, false), row(f + 5, 11, 1, true), row(f + 9, 30, 1, false), row(f + 400, 31, 1, false),
                row(f + 401, 2, 1, false), end(f + 450),
                row(g, 7, 2, false), row(g + 3, 8, 2, false), end(g + 50)),
                decode(line));
    }

    private static String row(long address, int line, int file, boolean prologueEnd) {
        return Long.toHexString(address) + " line " + line + " file " + file + (prologueEnd ? " prologue_end" : "");
    }

    private static String end(long address) {
        return Long.toHexString(address) + " end";
    }

    /** DWARF 4 section 6.2, only as far as a 32-bit unit with the opcodes Dwarf uses. */
    private static List<String> decode(byte[] section) {
        ByteBuffer b = ByteBuffer.wrap(section).order(ByteOrder.LITTLE_ENDIAN);
        int end = 4 + b.getInt();
        assertEquals(4, b.getShort());
        int headerLength = b.getInt();
        int programStart = b.position() + headerLength;
        b.get();
        b.get();
        b.get();
        int lineBase = b.get();
        int lineRange = b.get() & 0xff;
        int opcodeBase = b.get() & 0xff;
        b.position(programStart);

        List<String> rows = new ArrayList<>();
        long address = 0;
        int line = 1;
        int file = 1;
        boolean prologueEnd = false;
        while (b.position() < end) {
            int op = b.get() & 0xff;
            if (op >= opcodeBase) {
                int adjusted = op - opcodeBase;
                address += adjusted / lineRange;
                line += lineBase + adjusted % lineRange;
                rows.add(row(address, line, file, prologueEnd));
                prologueEnd = false;
                continue;
            }
            switch (op) {
                case 0 -> {
                    long length = uleb(b);
                    int sub = b.get();
                    if (sub == 1) {
                        rows.add(end(address));
                        address = 0;
                        line = 1;
                        file = 1;
                    } else if (sub == 2) {
                        address = b.getLong();
                    } else {
                        throw new AssertionError("extended opcode " + sub + " length " + length);
                    }
                }
                case 1 -> {
                    rows.add(row(address, line, file, prologueEnd));
                    prologueEnd = false;
                }
                case 2 -> address += uleb(b);
                case 3 -> line += (int) sleb(b);
                case 4 -> file = (int) uleb(b);
                case 10 -> prologueEnd = true;
                default -> throw new AssertionError("opcode " + op);
            }
        }
        return rows;
    }

    private static long uleb(ByteBuffer b) {
        long result = 0;
        int shift = 0;
        int x;
        do {
            x = b.get() & 0xff;
            result |= (long) (x & 0x7f) << shift;
            shift += 7;
        } while ((x & 0x80) != 0);
        return result;
    }

    private static long sleb(ByteBuffer b) {
        long result = 0;
        int shift = 0;
        int x;
        do {
            x = b.get() & 0xff;
            result |= (long) (x & 0x7f) << shift;
            shift += 7;
        } while ((x & 0x80) != 0);
        if ((x & 0x40) != 0) {
            result |= -1L << shift;
        }
        return result;
    }
}
