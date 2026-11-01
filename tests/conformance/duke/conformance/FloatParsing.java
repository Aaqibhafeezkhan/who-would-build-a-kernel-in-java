package duke.conformance;

/** Double.parseDouble and Float.parseFloat: the JDK's grammar, its error messages, and correct rounding. */
final class FloatParsing {

    static String simple() { return parse("0", "-0", "1", "-1.5", "0.1", ".5", "5.", "+.5e-3", "1e10", "123.456e-2"); }
    static String whitespaceAndSuffixes() { return parse(" 1.5 ", "\t7\n", "1.5f", "1.5D", "2d", "3F"); }
    static String specials() { return parse("NaN", "-NaN", "+Infinity", "-Infinity", "Infinity"); }
    static String hex() { return parse("0x1p3", "0x1.8P-1", "0x.8p1", "0X1P1D", "-0x1.fffffffffffffp1023", "0x0.0000000000001p-1022"); }
    static String overflowAndUnderflow() {
        return parse("1e400", "-1e400", "1e-400", "-1e-400", "1.7976931348623157e308", "1.7976931348623158e308",
                "1.7976931348623159e308", "1e999999999999", "1e-999999999999");
    }
    // Either side of half of Double.MIN_VALUE, which is where rounding to zero stops.
    static String halfOfMinValue() { return parse("2.4703282292062327e-324", "2.4703282292062328e-324", "4.9e-324"); }
    // The exact midpoint between 1.0 and the next double up rounds to even (1.0); a hair above rounds up.
    static String midpoints() {
        return parse("1.00000000000000011102230246251565404236316680908203125",
                "1.00000000000000011102230246251565404236316680908203126",
                "1.00000000000000033306690738754696212708950042724609375");
    }
    static String manyDigits() {
        StringBuilder digits = new StringBuilder("0.");
        for (int i = 0; i < 1000; i++) {
            digits.append((char) ('0' + i * 7 % 10));
        }
        return parse(digits.toString(), "000000000000000000000000000000.00000000000000000001e21",
                "123456789012345678901234567890e-30");
    }
    static String errors() {
        return parse("", "  ", "abc", "1e", "1e+", ".", "1.2.3", "..", "0x1..2p1", "0x1", "0xp1", "1.5ff", "infinity",
                "NaNf", "1_000", " -", "0x 1p1");
    }
    static long roundTrip() {
        long h = 0;
        long bits = 0x0123456789abcdefL;
        for (int i = 0; i < 100; i++) {
            bits = bits * 6364136223846793005L + 1442695040888963407L;
            double d = Double.longBitsToDouble(bits);
            h = h * 31 + Double.doubleToLongBits(Double.parseDouble(Double.toString(d)));
            float f = Float.intBitsToFloat((int) (bits >>> 21));
            h = h * 31 + Float.floatToIntBits(Float.parseFloat(Float.toString(f)));
        }
        return h;
    }

    // Through a double this rounds twice and lands one float higher.
    static String floatRoundsOnce() { return parseFloat("1.00000017881393432617187499", "3.4028235677973366e38", "7.006492321624085e-46"); }
    static String floats() { return parseFloat("0.1", "-0", "1e39", "1e-46", "1.4e-45", "0x1.fffffeP127", "3.4028236e38", "NaN", "abc"); }

    static String boxed() { return Double.valueOf("2.5") + " " + Float.valueOf("-0.25") + " " + Double.valueOf("1e3").equals(1000.0); }

    static String parse(String... inputs) {
        StringBuilder sb = new StringBuilder();
        for (String s : inputs) {
            try {
                sb.append(Double.parseDouble(s));
            } catch (NumberFormatException e) {
                sb.append(e.getMessage());
            }
            sb.append(" | ");
        }
        return sb.toString();
    }

    static String parseFloat(String... inputs) {
        StringBuilder sb = new StringBuilder();
        for (String s : inputs) {
            try {
                sb.append(Float.parseFloat(s));
            } catch (NumberFormatException e) {
                sb.append(e.getMessage());
            }
            sb.append(" | ");
        }
        return sb.toString();
    }
}
