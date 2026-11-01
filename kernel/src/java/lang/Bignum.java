package java.lang;

/**
 * Nonnegative integers in 32-bit words, least significant first, growing as needed: exact
 * arithmetic for converting doubles to decimal and back, where the kernel has no BigInteger.
 */
final class Bignum {

    private int[] words;
    private int length;

    private Bignum(int capacity) {
        words = new int[capacity];
    }

    static Bignum of(long value) {
        Bignum b = new Bignum(4);
        b.words[0] = (int) value;
        b.words[1] = (int) (value >>> 32);
        b.length = b.words[1] != 0 ? 2 : b.words[0] != 0 ? 1 : 0;
        return b;
    }

    /** A zero with room for {@code bits}, for {@link #sumInto} to reuse. */
    static Bignum scratch(int bits) {
        return new Bignum((bits >>> 5) + 2);
    }

    Bignum copy() {
        Bignum b = new Bignum(Math.max(length, 1) + 1);
        System.arraycopy(words, 0, b.words, 0, length);
        b.length = length;
        return b;
    }

    boolean isZero() {
        return length == 0;
    }

    int bitLength() {
        return length == 0 ? 0 : 32 * length - Integer.numberOfLeadingZeros(words[length - 1]);
    }

    private void ensureCapacity(int capacity) {
        if (capacity > words.length) {
            int[] grown = new int[Math.max(capacity, words.length * 2)];
            System.arraycopy(words, 0, grown, 0, length);
            words = grown;
        }
    }

    Bignum shiftLeft(int bits) {
        if (length == 0 || bits == 0) {
            return this;
        }
        int wordShift = bits >>> 5;
        int bitShift = bits & 31;
        ensureCapacity(length + wordShift + 1);
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

    Bignum shiftRight(int bits) {
        int wordShift = bits >>> 5;
        int bitShift = bits & 31;
        if (wordShift >= length) {
            length = 0;
            return this;
        }
        for (int i = 0; i < length - wordShift; i++) {
            long lo = (words[i + wordShift] & 0xFFFFFFFFL) >>> bitShift;
            long hi = i + wordShift + 1 < length && bitShift != 0
                    ? (words[i + wordShift + 1] & 0xFFFFFFFFL) << 32 - bitShift : 0;
            words[i] = (int) (lo | hi);
        }
        length -= wordShift;
        trim();
        return this;
    }

    /** {@code this * m + add}, for {@code m} and {@code add} below 2^31. */
    Bignum multiplyAdd(int m, int add) {
        long carry = add;
        for (int i = 0; i < length; i++) {
            long p = (words[i] & 0xFFFFFFFFL) * m + carry;
            words[i] = (int) p;
            carry = p >>> 32;
        }
        if (carry != 0) {
            ensureCapacity(length + 1);
            words[length++] = (int) carry;
        }
        return this;
    }

    Bignum multiplySmall(int m) {
        return multiplyAdd(m, 0);
    }

    Bignum multiplyPow10(int n) {
        for (; n >= 9; n -= 9) {
            multiplySmall(1_000_000_000);
        }
        int rest = 1;
        for (; n > 0; n--) {
            rest *= 10;
        }
        return multiplySmall(rest);
    }

    /** Replaces this with this mod d and returns the quotient, which must be small: it's found by subtracting. */
    int divideSmallQuotient(Bignum d) {
        int q = 0;
        while (compare(this, d) >= 0) {
            subtract(d);
            q++;
        }
        return q;
    }

    /** Replaces this with this mod d and returns the quotient, which must be under 2^63. */
    long divide(Bignum d) {
        int shift = bitLength() - d.bitLength();
        if (shift < 0) {
            return 0;
        }
        Bignum divisor = d.copy().shiftLeft(shift);
        long q = 0;
        for (int i = shift; i >= 0; i--) {
            q <<= 1;
            if (compare(this, divisor) >= 0) {
                subtract(divisor);
                q |= 1;
            }
            divisor.shiftRight(1);
        }
        return q;
    }

    void subtract(Bignum other) {
        long borrow = 0;
        for (int i = 0; i < length; i++) {
            long diff = (words[i] & 0xFFFFFFFFL) - (i < other.length ? other.words[i] & 0xFFFFFFFFL : 0) - borrow;
            words[i] = (int) diff;
            borrow = diff < 0 ? 1 : 0;
        }
        trim();
    }

    static Bignum sum(Bignum a, Bignum b) {
        return sumInto(a, b, scratch(32 * Math.max(a.length, b.length) + 32));
    }

    static Bignum sumInto(Bignum a, Bignum b, Bignum result) {
        int n = Math.max(a.length, b.length);
        result.ensureCapacity(n + 1);
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

    static Bignum distance(Bignum a, Bignum b) {
        Bignum d = compare(a, b) >= 0 ? a.copy() : b.copy();
        d.subtract(compare(a, b) >= 0 ? b : a);
        return d;
    }

    static int compare(Bignum a, Bignum b) {
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
