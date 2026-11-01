package java.lang;

/**
 * Double.toString and Float.toString: the shortest decimal that reads back as the same value, and
 * of those the nearest, laid out as the JDK lays it out. The digits come from Burger and Dybvig's
 * free-format generation ("Printing Floating-Point Numbers Quickly and Accurately", 1996) on exact
 * integers (Bignum), so every comparison is exact and nothing depends on the rounding of a double.
 */
final class ShortestDecimal {

    private ShortestDecimal() {
    }

    static String of(double v) {
        long bits = Double.doubleToRawLongBits(v);
        int biased = (int) (bits >>> 52) & 0x7ff;
        long fraction = bits & (1L << 52) - 1;
        if (biased == 0x7ff) {
            return fraction != 0 ? "NaN" : bits < 0 ? "-Infinity" : "Infinity";
        }
        if (biased == 0) {
            return format(bits < 0, fraction, -1074, false);
        }
        return format(bits < 0, fraction | 1L << 52, biased - 1075, fraction == 0 && biased > 1);
    }

    static String of(float v) {
        int bits = Float.floatToRawIntBits(v);
        int biased = bits >>> 23 & 0xff;
        int fraction = bits & (1 << 23) - 1;
        if (biased == 0xff) {
            return fraction != 0 ? "NaN" : bits < 0 ? "-Infinity" : "Infinity";
        }
        if (biased == 0) {
            return format(bits < 0, fraction, -149, false);
        }
        return format(bits < 0, fraction | 1 << 23, biased - 150, fraction == 0 && biased > 1);
    }

    /**
     * The value is {@code f * 2^e}. {@code unevenGap} when f is the first of its binade, so the
     * next value down is half as far away as the next one up.
     */
    private static String format(boolean negative, long f, int e, boolean unevenGap) {
        StringBuilder out = new StringBuilder(26);
        if (negative) {
            out.append('-');
        }
        if (f == 0) {
            return out.append("0.0").toString();
        }
        // Scaled so that r / s is the value, and mMinus / s and mPlus / s are half the gaps to the
        // neighbours below and above: anything strictly inside reads back as this value.
        int shift = unevenGap ? 2 : 1;
        Bignum r = Bignum.of(f).shiftLeft(Math.max(e, 0) + shift);
        Bignum s = Bignum.of(1).shiftLeft(Math.max(-e, 0) + shift);
        Bignum mMinus = Bignum.of(1).shiftLeft(Math.max(e, 0));
        Bignum mPlus = Bignum.of(1).shiftLeft(Math.max(e, 0) + (unevenGap ? 1 : 0));
        // Round half to even reads the midpoints back as this value when f is even.
        boolean inclusive = (f & 1) == 0;

        // floor(log10(2)) from below, less one so it can't overshoot; the loop puts it right.
        int binaryExponent = e + 63 - Long.numberOfLeadingZeros(f);
        int k = (binaryExponent * 315653 >> 20) - 1;
        if (k >= 0) {
            s.multiplyPow10(k);
        } else {
            r.multiplyPow10(-k);
            mMinus.multiplyPow10(-k);
            mPlus.multiplyPow10(-k);
        }
        // Now r / s = value / 10^k; make k the least with the upper bound under 10^k.
        Bignum scratch = Bignum.scratch(r.bitLength() + 64);
        while (reachesHigh(r, mPlus, s, inclusive, scratch)) {
            s.multiplySmall(10);
            k++;
        }
        Bignum r0 = r.copy();
        Bignum mMinus0 = mMinus.copy();
        Bignum mPlus0 = mPlus.copy();

        byte[] digits = new byte[20];
        int n = 0;
        while (true) {
            r.multiplySmall(10);
            mMinus.multiplySmall(10);
            mPlus.multiplySmall(10);
            int d = r.divideSmallQuotient(s);
            int low = Bignum.compare(r, mMinus);
            boolean lowOk = inclusive ? low <= 0 : low < 0;
            boolean highOk = reachesHigh(r, mPlus, s, inclusive, scratch);
            if (!lowOk && !highOk) {
                digits[n++] = (byte) d;
                continue;
            }
            if (lowOk && highOk) {
                int half = Bignum.compare(r.copy().shiftLeft(1), s);
                if (half > 0 || half == 0 && (d & 1) != 0) {
                    d++;
                }
            } else if (highOk) {
                d++;
            }
            digits[n++] = (byte) d;
            break;
        }

        // The JDK prefers a nearer two-digit decimal over the shortest when that's a single digit:
        // Double.MIN_VALUE is 4.9E-324 rather than 5.0E-324.
        if (n == 1) {
            int two = twoDigitsIfNearer(r0, mMinus0, mPlus0, s, digits[0], inclusive, 100);
            int place = 0;
            if (two >= 0 && two < 10) {
                // The single digit rounded up to the next power of ten, as 9.88E-324 does to 1.0E-323:
                // the value's own two leading digits are a place lower.
                two = twoDigitsIfNearer(r0, mMinus0, mPlus0, s, digits[0], inclusive, 1000);
                place = -1;
            }
            if (two >= 10) {
                digits[0] = (byte) (two / 10);
                digits[1] = (byte) (two % 10);
                n = 2;
                k += place;
            }
        }
        layOut(out, digits, n, k - 1);
        return out.toString();
    }

    /** Whether r + mPlus reaches s, using {@code scratch} rather than allocating once per digit. */
    private static boolean reachesHigh(Bignum r, Bignum mPlus, Bignum s, boolean inclusive, Bignum scratch) {
        int c = Bignum.compare(Bignum.sumInto(r, mPlus, scratch), s);
        return inclusive ? c >= 0 : c > 0;
    }

    /**
     * value / 10^k * scale rounded to nearest, if that reads back as the value and is strictly
     * nearer than the single digit; otherwise -1. Under 10 means the scale is a place too coarse.
     */
    private static int twoDigitsIfNearer(Bignum r0, Bignum mMinus0, Bignum mPlus0, Bignum s, int single, boolean inclusive,
            int scale) {
        Bignum hundredR = r0.copy().multiplySmall(scale);
        Bignum rest = hundredR.copy();
        int two = rest.divideSmallQuotient(s);
        if (two < 10) {
            return two;
        }
        int round = Bignum.compare(rest.shiftLeft(1), s);
        if (round > 0 || round == 0 && (two & 1) != 0) {
            two++;
        }
        if (two % 10 == 0) {
            return -1;
        }
        Bignum mMinus = mMinus0.copy().multiplySmall(scale);
        Bignum mPlus = mPlus0.copy().multiplySmall(scale);
        Bignum candidate = s.copy().multiplySmall(two);
        Bignum singleValue = s.copy().multiplySmall(single * scale / 10);
        int below = Bignum.compare(Bignum.sum(candidate, mMinus), hundredR);
        int above = Bignum.compare(candidate, Bignum.sum(hundredR, mPlus));
        boolean readsBack = (inclusive ? below >= 0 : below > 0) && (inclusive ? above <= 0 : above < 0);
        if (!readsBack) {
            return -1;
        }
        return Bignum.compare(Bignum.distance(candidate, hundredR), Bignum.distance(singleValue, hundredR)) < 0 ? two : -1;
    }

    /** Plain from 10^-3 up to under 10^7, computerized scientific notation outside that, as the JDK does. */
    private static void layOut(StringBuilder out, byte[] digits, int n, int exponent) {
        if (exponent >= -3 && exponent < 7) {
            if (exponent < 0) {
                out.append("0.");
                for (int i = -1; i > exponent; i--) {
                    out.append('0');
                }
                appendDigits(out, digits, 0, n);
                return;
            }
            for (int i = 0; i <= exponent; i++) {
                out.append((char) ('0' + (i < n ? digits[i] : 0)));
            }
            out.append('.');
            if (n > exponent + 1) {
                appendDigits(out, digits, exponent + 1, n);
            } else {
                out.append('0');
            }
            return;
        }
        out.append((char) ('0' + digits[0])).append('.');
        if (n > 1) {
            appendDigits(out, digits, 1, n);
        } else {
            out.append('0');
        }
        out.append('E').append(exponent);
    }

    private static void appendDigits(StringBuilder out, byte[] digits, int from, int to) {
        for (int i = from; i < to; i++) {
            out.append((char) ('0' + digits[i]));
        }
    }
}
