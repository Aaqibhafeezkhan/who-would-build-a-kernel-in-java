package java.lang;

public final class Integer extends Number implements Comparable<Integer> {

    public static final int MIN_VALUE = 0x80000000;
    public static final int MAX_VALUE = 0x7fffffff;
    public static final int SIZE = 32;

    // Same range as the JDK's default cache: code can observe it through ==.
    private static final Integer[] CACHE = new Integer[256];

    static {
        for (int i = 0; i < CACHE.length; i++) {
            CACHE[i] = new Integer(i - 128);
        }
    }

    private final int value;

    public Integer(int value) {
        this.value = value;
    }

    public static Integer valueOf(int i) {
        if (i >= -128 && i <= 127) {
            return CACHE[i + 128];
        }
        return new Integer(i);
    }

    public static Integer valueOf(String s) {
        return valueOf(parseInt(s));
    }

    @Override
    public int intValue() {
        return value;
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
        return other instanceof Integer i && i.value == value;
    }

    @Override
    public int hashCode() {
        return value;
    }

    @Override
    public String toString() {
        return toString(value);
    }

    @Override
    public int compareTo(Integer other) {
        return compare(value, other.value);
    }

    public static String toString(int i) {
        return Long.toString(i);
    }

    public static String toHexString(int i) {
        return Long.toUnsignedString(i & 0xFFFF_FFFFL, 4);
    }

    public static String toBinaryString(int i) {
        return Long.toUnsignedString(i & 0xFFFF_FFFFL, 1);
    }

    public static int parseInt(String s) {
        return parseInt(s, 10);
    }

    public static int parseInt(String s, int radix) {
        long value = Long.parseLong(s, radix);
        if (value < MIN_VALUE || value > MAX_VALUE) {
            throw Long.forInputString(s, radix);
        }
        return (int) value;
    }

    public static int compare(int x, int y) {
        return x < y ? -1 : (x == y ? 0 : 1);
    }

    public static int compareUnsigned(int x, int y) {
        return compare(x + MIN_VALUE, y + MIN_VALUE);
    }

    public static int signum(int i) {
        return (i >> 31) | (-i >>> 31);
    }

    public static int hashCode(int value) {
        return value;
    }

    public static int min(int a, int b) {
        return Math.min(a, b);
    }

    public static int max(int a, int b) {
        return Math.max(a, b);
    }

    public static int sum(int a, int b) {
        return a + b;
    }

    public static int bitCount(int i) {
        i = i - ((i >>> 1) & 0x55555555);
        i = (i & 0x33333333) + ((i >>> 2) & 0x33333333);
        i = (i + (i >>> 4)) & 0x0f0f0f0f;
        i = i + (i >>> 8);
        i = i + (i >>> 16);
        return i & 0x3f;
    }

    public static int numberOfLeadingZeros(int i) {
        if (i == 0) {
            return 32;
        }
        int n = 0;
        while (i > 0) {
            i <<= 1;
            n++;
        }
        return n;
    }

    public static int numberOfTrailingZeros(int i) {
        if (i == 0) {
            return 32;
        }
        int n = 0;
        while ((i & 1) == 0) {
            i >>>= 1;
            n++;
        }
        return n;
    }

    public static int highestOneBit(int i) {
        return i & (MIN_VALUE >>> numberOfLeadingZeros(i));
    }

    public static int lowestOneBit(int i) {
        return i & -i;
    }

    public static int rotateLeft(int i, int distance) {
        return (i << distance) | (i >>> -distance);
    }

    public static int rotateRight(int i, int distance) {
        return (i >>> distance) | (i << -distance);
    }

    public static int reverseBytes(int i) {
        return (i << 24) | ((i & 0xff00) << 8) | ((i >>> 8) & 0xff00) | (i >>> 24);
    }
}
