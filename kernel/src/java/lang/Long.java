package java.lang;

public final class Long extends Number implements Comparable<Long> {

    public static final long MIN_VALUE = 0x8000000000000000L;
    public static final long MAX_VALUE = 0x7fffffffffffffffL;
    public static final int SIZE = 64;

    private static final Long[] CACHE = new Long[256];

    static {
        for (int i = 0; i < CACHE.length; i++) {
            CACHE[i] = new Long(i - 128);
        }
    }

    private final long value;

    public Long(long value) {
        this.value = value;
    }

    public static Long valueOf(long l) {
        if (l >= -128 && l <= 127) {
            return CACHE[(int) l + 128];
        }
        return new Long(l);
    }

    @Override
    public int intValue() {
        return (int) value;
    }

    @Override
    public long longValue() {
        return value;
    }

    @Override
    public float floatValue() {
        return value;
    }

    @Override
    public double doubleValue() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Long l && l.value == value;
    }

    @Override
    public int hashCode() {
        return hashCode(value);
    }

    @Override
    public String toString() {
        return toString(value);
    }

    @Override
    public int compareTo(Long other) {
        return compare(value, other.value);
    }

    public static String toString(long value) {
        byte[] buffer = new byte[20];
        int pos = buffer.length;
        // Work in negatives: MIN_VALUE has no positive counterpart.
        long v = value < 0 ? value : -value;
        do {
            buffer[--pos] = (byte) ('0' - (int) (v % 10));
            v /= 10;
        } while (v != 0);
        if (value < 0) {
            buffer[--pos] = '-';
        }
        return fromBuffer(buffer, pos);
    }

    public static String toHexString(long i) {
        return toUnsignedString(i, 4);
    }

    public static String toBinaryString(long i) {
        return toUnsignedString(i, 1);
    }

    /** {@code shift} is bits per digit: 1 for binary, 4 for hex. */
    static String toUnsignedString(long value, int shift) {
        byte[] buffer = new byte[64];
        int pos = buffer.length;
        long mask = (1L << shift) - 1;
        do {
            buffer[--pos] = (byte) "0123456789abcdef".charAt((int) (value & mask));
            value >>>= shift;
        } while (value != 0);
        return fromBuffer(buffer, pos);
    }

    private static String fromBuffer(byte[] buffer, int start) {
        byte[] digits = new byte[buffer.length - start];
        System.arraycopy(buffer, start, digits, 0, digits.length);
        return new String(digits);
    }

    public static long parseLong(String s) {
        return parseLong(s, 10);
    }

    /** Accumulates negatively, like the JDK, so MIN_VALUE parses without overflow. */
    public static long parseLong(String s, int radix) {
        if (s == null) {
            throw new NumberFormatException("Cannot parse null string: null");
        }
        if (radix < 2 || radix > 36) {
            throw new NumberFormatException("radix " + radix + " out of range");
        }
        if (s.isEmpty()) {
            throw forInputString(s, radix);
        }
        int i = 0;
        boolean negative = false;
        char first = s.charAt(0);
        if (first == '-' || first == '+') {
            negative = first == '-';
            i = 1;
            if (s.length() == 1) {
                throw forInputString(s, radix);
            }
        }
        long limit = negative ? MIN_VALUE : -MAX_VALUE;
        long multiplyLimit = limit / radix;
        long result = 0;
        for (; i < s.length(); i++) {
            int digit = digit(s.charAt(i), radix);
            if (digit < 0 || result < multiplyLimit) {
                throw forInputString(s, radix);
            }
            result *= radix;
            if (result < limit + digit) {
                throw forInputString(s, radix);
            }
            result -= digit;
        }
        return negative ? result : -result;
    }

    static NumberFormatException forInputString(String s, int radix) {
        String suffix = radix == 10 ? "" : " under radix " + radix;
        return new NumberFormatException("For input string: \"" + s + "\"" + suffix);
    }

    private static int digit(char c, int radix) {
        int d;
        if (c >= '0' && c <= '9') {
            d = c - '0';
        } else if (c >= 'a' && c <= 'z') {
            d = c - 'a' + 10;
        } else if (c >= 'A' && c <= 'Z') {
            d = c - 'A' + 10;
        } else {
            return -1;
        }
        return d < radix ? d : -1;
    }

    public static int compare(long x, long y) {
        return x < y ? -1 : (x == y ? 0 : 1);
    }

    public static int signum(long i) {
        return (int) ((i >> 63) | (-i >>> 63));
    }

    public static int hashCode(long value) {
        return (int) (value ^ (value >>> 32));
    }

    public static long min(long a, long b) {
        return Math.min(a, b);
    }

    public static long max(long a, long b) {
        return Math.max(a, b);
    }

    public static long sum(long a, long b) {
        return a + b;
    }

    public static int bitCount(long i) {
        return Integer.bitCount((int) i) + Integer.bitCount((int) (i >>> 32));
    }

    public static int numberOfLeadingZeros(long i) {
        int high = (int) (i >>> 32);
        return high != 0 ? Integer.numberOfLeadingZeros(high) : 32 + Integer.numberOfLeadingZeros((int) i);
    }

    public static int numberOfTrailingZeros(long i) {
        int low = (int) i;
        return low != 0 ? Integer.numberOfTrailingZeros(low) : 32 + Integer.numberOfTrailingZeros((int) (i >>> 32));
    }

    public static long rotateLeft(long i, int distance) {
        return (i << distance) | (i >>> -distance);
    }

    public static long rotateRight(long i, int distance) {
        return (i >>> distance) | (i << -distance);
    }
}
