package java.lang;

public final class Byte extends Number implements Comparable<Byte> {

    public static final byte MIN_VALUE = -128;
    public static final byte MAX_VALUE = 127;

    private static final Byte[] CACHE = new Byte[256];

    static {
        for (int i = 0; i < CACHE.length; i++) {
            CACHE[i] = new Byte((byte) (i - 128));
        }
    }

    private final byte value;

    private Byte(byte value) {
        this.value = value;
    }

    public static Byte valueOf(byte b) {
        return CACHE[b + 128];
    }

    public static String toString(byte b) {
        return Integer.toString(b);
    }

    public static int compare(byte x, byte y) {
        return x - y;
    }

    public static int toUnsignedInt(byte b) {
        return b & 0xFF;
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
    public byte byteValue() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Byte b && b.value == value;
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
    public int compareTo(Byte other) {
        return compare(value, other.value);
    }
}
