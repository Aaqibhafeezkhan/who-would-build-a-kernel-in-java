package java.lang;

import duke.rt.Magic;

/** Without toString until formatting lands (#26), so printing one fails loudly rather than as Double@hash. */
public final class Double extends Number implements Comparable<Double> {

    public static final double POSITIVE_INFINITY = 1.0 / 0.0;
    public static final double NEGATIVE_INFINITY = -1.0 / 0.0;
    public static final double NaN = 0.0 / 0.0;
    public static final double MAX_VALUE = 0x1.fffffffffffffP+1023;
    public static final double MIN_NORMAL = 0x1.0p-1022;
    public static final double MIN_VALUE = 0x0.0000000000001P-1022;
    public static final int SIZE = 64;
    public static final int BYTES = 8;

    private final double value;

    private Double(double value) {
        this.value = value;
    }

    public static Double valueOf(double value) {
        return new Double(value);
    }

    @Override
    public int intValue() {
        return (int) value;
    }

    @Override
    public long longValue() {
        return (long) value;
    }

    @Override
    public float floatValue() {
        return (float) value;
    }

    @Override
    public double doubleValue() {
        return value;
    }

    /** By bits, unlike ==: NaN equals NaN, and 0.0 doesn't equal -0.0. */
    @Override
    public boolean equals(Object other) {
        return other instanceof Double d && doubleToLongBits(d.value) == doubleToLongBits(value);
    }

    @Override
    public int hashCode() {
        return hashCode(value);
    }

    @Override
    public String toString() {
        throw new UnsupportedOperationException("Double.toString isn't written yet (#26)");
    }

    @Override
    public int compareTo(Double other) {
        return compare(value, other.value);
    }

    public static int hashCode(double value) {
        long bits = doubleToLongBits(value);
        return (int) (bits ^ bits >>> 32);
    }

    public static long doubleToRawLongBits(double value) {
        return Magic.doubleToRawLongBits(value);
    }

    /** Every NaN as the one canonical NaN. */
    public static long doubleToLongBits(double value) {
        return isNaN(value) ? 0x7ff8000000000000L : doubleToRawLongBits(value);
    }

    public static double longBitsToDouble(long bits) {
        return Magic.longBitsToDouble(bits);
    }

    public static boolean isNaN(double v) {
        return v != v;
    }

    public static boolean isInfinite(double v) {
        return v == POSITIVE_INFINITY || v == NEGATIVE_INFINITY;
    }

    public static boolean isFinite(double d) {
        return d - d == 0.0;
    }

    /** As the JDK orders them: -0.0 below 0.0, and NaN above everything, equal to itself. */
    public static int compare(double d1, double d2) {
        if (d1 < d2) {
            return -1;
        }
        if (d1 > d2) {
            return 1;
        }
        long bits1 = doubleToLongBits(d1);
        long bits2 = doubleToLongBits(d2);
        return bits1 == bits2 ? 0 : bits1 < bits2 ? -1 : 1;
    }
}
