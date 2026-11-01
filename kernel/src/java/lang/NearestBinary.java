package java.lang;

/**
 * Double.parseDouble and Float.parseFloat: Double.valueOf(String)'s grammar, decimal and hex,
 * rounded to the nearest double or float (ties to even) from the exact value. A float is rounded
 * from the decimal directly, not through a double, which would round twice.
 */
final class NearestBinary {

    // A halfway point between two doubles has at most 767 significant digits. Keeping more, plus a
    // nonzero digit standing in for any dropped ones, rounds every input as all its digits would.
    private static final int MAX_DIGITS = 800;
    private static final int MAX_HEX_DIGITS = 40;
    // Beyond these a value is certainly infinite or certainly rounds to zero, and the powers of ten
    // stay a manageable size.
    private static final int MAX_DECIMAL_MAGNITUDE = 400;
    private static final int MAX_BINARY_MAGNITUDE = 1200;
    private static final long EXPONENT_CAP = 1_000_000_000L;

    private NearestBinary() {
    }

    static double parseDouble(String in) {
        return Double.longBitsToDouble(parse(in, 53, -1074, 1023));
    }

    static float parseFloat(String in) {
        return Float.intBitsToFloat((int) parse(in, 24, -149, 127));
    }

    /**
     * The bits of the nearest value with {@code precision} significant bits, whose smallest
     * subnormal is 2^minExponent and whose largest exponent is maxExponent.
     */
    private static long parse(String in, int precision, int minExponent, int maxExponent) {
        String s = in.trim();
        if (s.isEmpty()) {
            throw new NumberFormatException("empty String");
        }
        int exponentBits = 32 - Integer.numberOfLeadingZeros(2 * maxExponent + 1);
        long signBit = 1L << precision - 1 + exponentBits;
        long infinity = (long) (2 * maxExponent + 1) << precision - 1;
        int i = 0;
        boolean negative = false;
        if (s.charAt(0) == '+' || s.charAt(0) == '-') {
            negative = s.charAt(0) == '-';
            i++;
        }
        long sign = negative ? signBit : 0;
        String rest = s.substring(i);
        if (rest.equals("NaN")) {
            return infinity | 1L << precision - 2;
        }
        if (rest.equals("Infinity")) {
            return sign | infinity;
        }
        long magnitude = rest.startsWith("0x") || rest.startsWith("0X")
                ? hex(in, s, i + 2, precision, minExponent, maxExponent)
                : decimal(in, s, i, precision, minExponent, maxExponent);
        return sign | magnitude;
    }

    private static long decimal(String in, String s, int i, int precision, int minExponent, int maxExponent) {
        Bignum digits = Bignum.of(0);
        int count = 0;
        long exponent = 0;
        boolean sawDigit = false;
        boolean dropped = false;
        for (; i < s.length() && isDigit(s.charAt(i)); i++) {
            int d = s.charAt(i) - '0';
            sawDigit = true;
            if (count == 0 && d == 0) {
                continue;
            }
            if (count < MAX_DIGITS) {
                digits.multiplyAdd(10, d);
                count++;
            } else {
                exponent++;
                dropped |= d != 0;
            }
        }
        if (i < s.length() && s.charAt(i) == '.') {
            for (i++; i < s.length() && isDigit(s.charAt(i)); i++) {
                int d = s.charAt(i) - '0';
                sawDigit = true;
                if (count == 0 && d == 0) {
                    exponent--;
                } else if (count < MAX_DIGITS) {
                    digits.multiplyAdd(10, d);
                    count++;
                    exponent--;
                } else {
                    dropped |= d != 0;
                }
            }
        }
        if (i < s.length() && s.charAt(i) == '.') {
            throw new NumberFormatException("multiple points");
        }
        if (!sawDigit) {
            throw forInputString(in);
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            long[] parsed = signedExponent(in, s, i + 1);
            exponent += parsed[0];
            i = (int) parsed[1];
        }
        checkEnd(in, s, i);
        if (dropped) {
            digits.multiplyAdd(10, 1);
            count++;
            exponent--;
        }
        if (digits.isZero() || count + exponent < -MAX_DECIMAL_MAGNITUDE) {
            return 0;
        }
        if (count + exponent > MAX_DECIMAL_MAGNITUDE) {
            return (long) (2 * maxExponent + 1) << precision - 1;
        }
        return round(digits, (int) exponent, 0, precision, minExponent, maxExponent);
    }

    /** The JDK's hex form: 0x, hex digits with an optional point, then a binary exponent, which isn't optional. */
    private static long hex(String in, String s, int i, int precision, int minExponent, int maxExponent) {
        Bignum digits = Bignum.of(0);
        int count = 0;
        long exponent = 0;
        boolean sawDigit = false;
        boolean dropped = false;
        boolean fraction = false;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') {
                if (fraction) {
                    throw new NumberFormatException("multiple points");
                }
                fraction = true;
                continue;
            }
            int d = Character.digit(c, 16);
            if (d < 0) {
                break;
            }
            sawDigit = true;
            if (count == 0 && d == 0) {
                exponent -= fraction ? 4 : 0;
            } else if (count < MAX_HEX_DIGITS) {
                digits.multiplyAdd(16, d);
                count++;
                exponent -= fraction ? 4 : 0;
            } else {
                exponent += fraction ? 0 : 4;
                dropped |= d != 0;
            }
        }
        if (!sawDigit || i == s.length() || (s.charAt(i) != 'p' && s.charAt(i) != 'P')) {
            throw forInputString(in);
        }
        long[] parsed = signedExponent(in, s, i + 1);
        exponent += parsed[0];
        checkEnd(in, s, (int) parsed[1]);
        if (dropped) {
            digits.multiplyAdd(16, 1);
            count++;
            exponent -= 4;
        }
        if (digits.isZero() || 4L * count + exponent < -MAX_BINARY_MAGNITUDE) {
            return 0;
        }
        if (4L * count + exponent > MAX_BINARY_MAGNITUDE) {
            return (long) (2 * maxExponent + 1) << precision - 1;
        }
        return round(digits, 0, (int) exponent, precision, minExponent, maxExponent);
    }

    /** An optionally signed run of at least one digit: its value, capped, and the index after it. */
    private static long[] signedExponent(String in, String s, int i) {
        boolean negative = false;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            negative = s.charAt(i) == '-';
            i++;
        }
        int start = i;
        long value = 0;
        for (; i < s.length() && isDigit(s.charAt(i)); i++) {
            value = Math.min(value * 10 + (s.charAt(i) - '0'), EXPONENT_CAP);
        }
        if (i == start) {
            throw forInputString(in);
        }
        return new long[] {negative ? -value : value, i};
    }

    /** Allows one f, F, d or D, then nothing. */
    private static void checkEnd(String in, String s, int i) {
        if (i < s.length() && "fFdD".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (i != s.length()) {
            throw forInputString(in);
        }
    }

    /** The bits of digits * 10^exponent10 * 2^exponent2, rounded to nearest, ties to even. */
    private static long round(Bignum digits, int exponent10, int exponent2, int precision, int minExponent,
            int maxExponent) {
        Bignum n = digits;
        Bignum d = Bignum.of(1);
        if (exponent10 >= 0) {
            n.multiplyPow10(exponent10);
        } else {
            d.multiplyPow10(-exponent10);
        }
        if (exponent2 >= 0) {
            n.shiftLeft(exponent2);
        } else {
            d.shiftLeft(-exponent2);
        }
        // q = floor(n * 2^shift / d) gets precision + 1 bits (the significand and one to round
        // on), or one more, which is folded in below; subnormals get fewer.
        int shift = precision + 1 - (n.bitLength() - d.bitLength());
        if (1 - shift < minExponent) {
            shift = 1 - minExponent;
        }
        if (shift >= 0) {
            n.shiftLeft(shift);
        } else {
            d.shiftLeft(-shift);
        }
        long q = n.divide(d);
        boolean sticky = !n.isZero();
        if (q >>> precision + 1 != 0) {
            sticky |= (q & 1) != 0;
            q >>>= 1;
            shift--;
        }
        long significand = q >>> 1;
        if ((q & 1) != 0 && (sticky || (significand & 1) != 0)) {
            significand++;
        }
        int lowestExponent = 1 - shift;
        if (significand >>> precision != 0) {
            significand >>>= 1;
            lowestExponent++;
        }
        if (significand >>> precision - 1 == 0) {
            return significand;
        }
        int exponent = lowestExponent + precision - 1;
        if (exponent > maxExponent) {
            return (long) (2 * maxExponent + 1) << precision - 1;
        }
        return (long) (exponent + maxExponent) << precision - 1 | significand & (1L << precision - 1) - 1;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static NumberFormatException forInputString(String in) {
        return new NumberFormatException("For input string: \"" + in + "\"");
    }
}
