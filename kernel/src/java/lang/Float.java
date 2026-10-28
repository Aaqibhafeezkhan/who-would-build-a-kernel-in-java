package java.lang;

import duke.rt.Magic;

/** Without toString until formatting lands (#26), so printing one fails loudly rather than as Float@hash. */
public final class Float extends Number implements Comparable<Float> {

    public static final float POSITIVE_INFINITY = 1.0f / 0.0f;
    public static final float NEGATIVE_INFINITY = -1.0f / 0.0f;
    public static final float NaN = 0.0f / 0.0f;
    public static final float MAX_VALUE = 0x1.fffffeP+127f;
    public static final float MIN_NORMAL = 0x1.0p-126f;
    public static final float MIN_VALUE = 0x0.000002P-126f;
    public static final int SIZE = 32;
    public static final int BYTES = 4;

    private final float value;

    private Float(float value) {
        this.value = value;
    }

    public static Float valueOf(float value) {
        return new Float(value);
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
        return value;
    }

    @Override
    public double doubleValue() {
        return value;
    }

    /** By bits, unlike ==: NaN equals NaN, and 0.0 doesn't equal -0.0. */
    @Override
    public boolean equals(Object other) {
        return other instanceof Float f && floatToIntBits(f.value) == floatToIntBits(value);
    }

    @Override
    public int hashCode() {
        return hashCode(value);
    }

    @Override
    public String toString() {
        throw new UnsupportedOperationException("Float.toString isn't written yet (#26)");
    }

    @Override
    public int compareTo(Float other) {
        return compare(value, other.value);
    }

    public static int hashCode(float value) {
        return floatToIntBits(value);
    }

    public static int floatToRawIntBits(float value) {
        return Magic.floatToRawIntBits(value);
    }

    /** Every NaN as the one canonical NaN. */
    public static int floatToIntBits(float value) {
        return isNaN(value) ? 0x7fc00000 : floatToRawIntBits(value);
    }

    public static float intBitsToFloat(int bits) {
        return Magic.intBitsToFloat(bits);
    }

    public static boolean isNaN(float v) {
        return v != v;
    }

    public static boolean isInfinite(float v) {
        return v == POSITIVE_INFINITY || v == NEGATIVE_INFINITY;
    }

    public static boolean isFinite(float f) {
        return f - f == 0.0f;
    }

    /** As the JDK orders them: -0.0 below 0.0, and NaN above everything, equal to itself. */
    public static int compare(float f1, float f2) {
        if (f1 < f2) {
            return -1;
        }
        if (f1 > f2) {
            return 1;
        }
        int bits1 = floatToIntBits(f1);
        int bits2 = floatToIntBits(f2);
        return bits1 == bits2 ? 0 : bits1 < bits2 ? -1 : 1;
    }
}
