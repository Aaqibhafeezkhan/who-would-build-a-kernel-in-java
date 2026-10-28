package java.lang;

public final class Short extends Number implements Comparable<Short> {

    public static final short MIN_VALUE = -32768;
    public static final short MAX_VALUE = 32767;

    private static final Short[] CACHE = new Short[256];

    static {
        for (int i = 0; i < CACHE.length; i++) {
            CACHE[i] = new Short((short) (i - 128));
        }
    }

    private final short value;

    private Short(short value) {
        this.value = value;
    }

    public static Short valueOf(short s) {
        if (s >= -128 && s <= 127) {
            return CACHE[s + 128];
        }
        return new Short(s);
    }

    public static String toString(short s) {
        return Integer.toString(s);
    }

    public static int compare(short x, short y) {
        return x - y;
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
    public short shortValue() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Short s && s.value == value;
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
    public int compareTo(Short other) {
        return compare(value, other.value);
    }
}
