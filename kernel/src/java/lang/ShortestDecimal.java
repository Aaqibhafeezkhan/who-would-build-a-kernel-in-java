package java.lang;

/**
 * Double.toString and Float.toString: the shortest decimal that reads back as the same value, and
 * of those the nearest, laid out as the JDK lays it out. The digits come from Burger and Dybvig's
 * free-format generation ("Printing Floating-Point Numbers Quickly and Accurately", 1996) on exact
 * integers, so every comparison is exact and nothing depends on the rounding of a double.
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
        Big r = Big.of(f).shiftLeft(Math.max(e, 0) + shift);
        Big s = Big.of(1).shiftLeft(Math.max(-e, 0) + shift);
        Big mMinus = Big.of(1).shiftLeft(Math.max(e, 0));
        Big mPlus = Big.of(1).shiftLeft(Math.max(e, 0) + (unevenGap ? 1 : 0));
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
        Big scratch = new Big();
        while (reachesHigh(r, mPlus, s, inclusive, scratch)) {
            s.multiplySmall(10);
            k++;
        }
        Big r0 = r.copy();
        Big mMinus0 = mMinus.copy();
        Big mPlus0 = mPlus.copy();

        byte[] digits = new byte[20];
        int n = 0;
        while (true) {
            r.multiplySmall(10);
            mMinus.multiplySmall(10);
            mPlus.multiplySmall(10);
            int d = r.divideSmallQuotient(s);
            int low = Big.compare(r, mMinus);
            boolean lowOk = inclusive ? low <= 0 : low < 0;
            boolean highOk = reachesHigh(r, mPlus, s, inclusive, scratch);
            if (!lowOk && !highOk) {
                digits[n++] = (byte) d;
                continue;
            }
            if (lowOk && highOk) {
                int half = Big.compare(r.copy().shiftLeft(1), s);
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
    private static boolean reachesHigh(Big r, Big mPlus, Big s, boolean inclusive, Big scratch) {
        int c = Big.compare(Big.sumInto(r, mPlus, scratch), s);
        return inclusive ? c >= 0 : c > 0;
    }

    /**
     * value / 10^k * scale rounded to nearest, if that reads back as the value and is strictly
     * nearer than the single digit; otherwise -1. Under 10 means the scale is a place too coarse.
     */
    private static int twoDigitsIfNearer(Big r0, Big mMinus0, Big mPlus0, Big s, int single, boolean inclusive,
            int scale) {
        Big hundredR = r0.copy().multiplySmall(scale);
        Big rest = hundredR.copy();
        int two = rest.divideSmallQuotient(s);
        if (two < 10) {
            return two;
        }
        int round = Big.compare(rest.shiftLeft(1), s);
        if (round > 0 || round == 0 && (two & 1) != 0) {
            two++;
        }
        if (two % 10 == 0) {
            return -1;
        }
        Big mMinus = mMinus0.copy().multiplySmall(scale);
        Big mPlus = mPlus0.copy().multiplySmall(scale);
        Big candidate = s.copy().multiplySmall(two);
        Big singleValue = s.copy().multiplySmall(single * scale / 10);
        int below = Big.compare(Big.sum(candidate, mMinus), hundredR);
        int above = Big.compare(candidate, Big.sum(hundredR, mPlus));
        boolean readsBack = (inclusive ? below >= 0 : below > 0) && (inclusive ? above <= 0 : above < 0);
        if (!readsBack) {
            return -1;
        }
        return Big.compare(Big.distance(candidate, hundredR), Big.distance(singleValue, hundredR)) < 0 ? two : -1;
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

    /** Nonnegative integers in 32-bit words, least significant first, big enough for any double times 10^340. */
    private static final class Big {
        private static final int WORDS = 48;

        private final int[] words = new int[WORDS];
        private int length;

        static Big of(long value) {
            Big b = new Big();
            b.words[0] = (int) value;
            b.words[1] = (int) (value >>> 32);
            b.length = b.words[1] != 0 ? 2 : b.words[0] != 0 ? 1 : 0;
            return b;
        }

        Big copy() {
            Big b = new Big();
            System.arraycopy(words, 0, b.words, 0, length);
            b.length = length;
            return b;
        }

        Big shiftLeft(int bits) {
            if (length == 0) {
                return this;
            }
            int wordShift = bits >>> 5;
            int bitShift = bits & 31;
            // Top down, so each word is read before it's overwritten.
            for (int i = length + wordShift; i >= wordShift; i--) {
                int src = i - wordShift;
                long hi = src < length ? words[src] & 0xFFFFFFFFL : 0;
                long lo = src >= 1 && bitShift != 0 ? (words[src - 1] & 0xFFFFFFFFL) >>> 32 - bitShift : 0;
                words[i] = (int) (hi << bitShift | lo);
            }
            for (int i = 0; i < wordShift; i++) {
                words[i] = 0;
            }
            length += wordShift + 1;
            trim();
            return this;
        }

        Big multiplySmall(int m) {
            long carry = 0;
            for (int i = 0; i < length; i++) {
                long p = (words[i] & 0xFFFFFFFFL) * m + carry;
                words[i] = (int) p;
                carry = p >>> 32;
            }
            if (carry != 0) {
                words[length++] = (int) carry;
            }
            return this;
        }

        Big multiplyPow10(int n) {
            for (; n >= 9; n -= 9) {
                multiplySmall(1_000_000_000);
            }
            int rest = 1;
            for (; n > 0; n--) {
                rest *= 10;
            }
            return multiplySmall(rest);
        }

        /** Replaces this with this mod d and returns the quotient, which must be under 2^31. */
        int divideSmallQuotient(Big d) {
            int q = 0;
            while (compare(this, d) >= 0) {
                subtract(d);
                q++;
            }
            return q;
        }

        private void subtract(Big other) {
            long borrow = 0;
            for (int i = 0; i < length; i++) {
                long diff = (words[i] & 0xFFFFFFFFL) - (i < other.length ? other.words[i] & 0xFFFFFFFFL : 0) - borrow;
                words[i] = (int) diff;
                borrow = diff < 0 ? 1 : 0;
            }
            trim();
        }

        static Big sum(Big a, Big b) {
            return sumInto(a, b, new Big());
        }

        static Big sumInto(Big a, Big b, Big result) {
            int n = Math.max(a.length, b.length);
            long carry = 0;
            for (int i = 0; i < n; i++) {
                long s = (i < a.length ? a.words[i] & 0xFFFFFFFFL : 0) + (i < b.length ? b.words[i] & 0xFFFFFFFFL : 0) + carry;
                result.words[i] = (int) s;
                carry = s >>> 32;
            }
            result.length = n;
            if (carry != 0) {
                result.words[result.length++] = (int) carry;
            }
            return result;
        }

        static Big distance(Big a, Big b) {
            if (compare(a, b) >= 0) {
                Big d = a.copy();
                d.subtract(b);
                return d;
            }
            Big d = b.copy();
            d.subtract(a);
            return d;
        }

        static int compare(Big a, Big b) {
            if (a.length != b.length) {
                return a.length < b.length ? -1 : 1;
            }
            for (int i = a.length - 1; i >= 0; i--) {
                if (a.words[i] != b.words[i]) {
                    return Integer.compareUnsigned(a.words[i], b.words[i]);
                }
            }
            return 0;
        }

        private void trim() {
            while (length > 0 && words[length - 1] == 0) {
                length--;
            }
        }
    }
}
