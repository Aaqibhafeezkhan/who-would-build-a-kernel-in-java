package java.lang;

/** Formats the exact binary value of a float or double as a hexadecimal significand and exponent. */
final class HexadecimalFloatingPoint {

    private static final char[] DIGITS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

    private HexadecimalFloatingPoint() {
    }

    static String of(double value) {
        long bits = Double.doubleToRawLongBits(value);
        int exponent = (int) (bits >>> 52) & 0x7ff;
        long fraction = bits & 0x000fffffffffffffL;
        if (exponent == 0x7ff) {
            return Double.toString(value);
        }
        StringBuilder out = new StringBuilder(24);
        if (bits < 0) {
            out.append('-');
        }
        if (exponent == 0) {
            append(out, '0', fraction, 48, 13);
            out.append('p').append(-1022);
        } else {
            append(out, '1', fraction, 48, 13);
            out.append('p').append(exponent - 1023);
        }
        return out.toString();
    }

    static String of(float value) {
        int bits = Float.floatToRawIntBits(value);
        int exponent = bits >>> 23 & 0xff;
        int fraction = bits & 0x007fffff;
        if (exponent == 0xff) {
            return Float.toString(value);
        }
        StringBuilder out = new StringBuilder(16);
        if (bits < 0) {
            out.append('-');
        }
        if (exponent == 0) {
            append(out, '0', (long) fraction << 1, 20, 6);
            out.append('p').append(-126);
        } else {
            append(out, '1', (long) fraction << 1, 20, 6);
            out.append('p').append(exponent - 127);
        }
        return out.toString();
    }

    private static void append(StringBuilder out, char leading, long fraction, int shift, int digits) {
        out.append("0x").append(leading).append('.');
        int count = digits;
        while (count > 1 && ((fraction >>> ((digits - count) * 4)) & 0xf) == 0) {
            count--;
        }
        for (int i = 0; i < count; i++) {
            out.append(DIGITS[(int) (fraction >>> (shift - i * 4) & 0xf)]);
        }
    }
}
