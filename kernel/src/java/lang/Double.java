package java.lang;

import duke.rt.Magic;

/** The static half for now: boxing and toString need formatting, which comes later (#26). */
public final class Double {

    public static final double POSITIVE_INFINITY = 1.0 / 0.0;
    public static final double NEGATIVE_INFINITY = -1.0 / 0.0;
    public static final double NaN = 0.0 / 0.0;
    public static final double MAX_VALUE = 0x1.fffffffffffffP+1023;
    public static final double MIN_NORMAL = 0x1.0p-1022;
    public static final double MIN_VALUE = 0x0.0000000000001P-1022;
    public static final int SIZE = 64;
    public static final int BYTES = 8;

    private Double() {
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
