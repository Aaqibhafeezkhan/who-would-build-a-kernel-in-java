package java.lang;

import duke.rt.Magic;

/** The static half for now: boxing and toString need formatting, which comes later (#26). */
public final class Float {

    public static final float POSITIVE_INFINITY = 1.0f / 0.0f;
    public static final float NEGATIVE_INFINITY = -1.0f / 0.0f;
    public static final float NaN = 0.0f / 0.0f;
    public static final float MAX_VALUE = 0x1.fffffeP+127f;
    public static final float MIN_NORMAL = 0x1.0p-126f;
    public static final float MIN_VALUE = 0x0.000002P-126f;
    public static final int SIZE = 32;
    public static final int BYTES = 4;

    private Float() {
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
