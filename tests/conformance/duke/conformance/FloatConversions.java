package duke.conformance;

/** Java saturates where cvttsd2si gives the most negative value, and NaN goes to 0. */
final class FloatConversions {

    static int d2i() { return (int) opaque(3.99); }
    static int d2iNegative() { return (int) opaque(-3.99); }
    static int d2iNaN() { return (int) opaque(Double.NaN); }
    static int d2iPositiveInfinity() { return (int) opaque(Double.POSITIVE_INFINITY); }
    static int d2iNegativeInfinity() { return (int) opaque(Double.NEGATIVE_INFINITY); }
    static int d2iTooBig() { return (int) opaque(1e10); }
    static int d2iTooSmall() { return (int) opaque(-1e10); }
    static int d2iJustBelowMax() { return (int) opaque(2147483646.9); }
    static int d2iMin() { return (int) opaque(-2147483648.0); }
    static int d2iJustBelowMin() { return (int) opaque(-2147483648.9); }
    static long d2l() { return (long) opaque(-1e15 - 0.5); }
    static long d2lNaN() { return (long) opaque(Double.NaN); }
    static long d2lTooBig() { return (long) opaque(1e19); }
    static long d2lTooSmall() { return (long) opaque(-1e19); }
    static long d2lMin() { return (long) opaque(-0x1p63); }
    static long d2lLargestBelowTwoTo63() { return (long) opaque(0x1.fffffffffffffp62); }
    static int f2i() { return (int) opaque(-7.75f); }
    static int f2iNaN() { return (int) opaque(Float.NaN); }
    static int f2iTooBig() { return (int) opaque(3e9f); }
    static int f2iTooSmall() { return (int) opaque(-3e9f); }
    static long f2l() { return (long) opaque(1.5e18f); }
    static long f2lNaN() { return (long) opaque(Float.NaN); }
    static long f2lTooBig() { return (long) opaque(Float.POSITIVE_INFINITY); }
    static long f2lTooSmall() { return (long) opaque(-1e30f); }
    static long d2iNegativeZero() { return (int) opaque(-0.0); }

    static long i2d() { return Double.doubleToRawLongBits(opaque(-123456789)); }
    static int i2fRounds() { return Float.floatToRawIntBits(opaque(16777217)); }
    static int i2fMin() { return Float.floatToRawIntBits(opaque(Integer.MIN_VALUE)); }
    static long l2dRounds() { return Double.doubleToRawLongBits(opaque(Long.MAX_VALUE)); }
    static long l2dMin() { return Double.doubleToRawLongBits(opaque(Long.MIN_VALUE)); }
    static long l2dOdd() { return Double.doubleToRawLongBits(opaque((1L << 53) + 1)); }
    static int l2f() { return Float.floatToRawIntBits(opaque(-(1L << 40) - 1)); }
    static long f2d() { return Double.doubleToRawLongBits(opaque(0.1f)); }
    static long f2dNaN() { return Double.doubleToLongBits(opaque(Float.NaN)); }
    static long f2dNegativeZero() { return Double.doubleToRawLongBits(opaque(-0.0f)); }
    static int d2f() { return Float.floatToRawIntBits((float) opaque(0.1)); }
    static int d2fOverflow() { return Float.floatToRawIntBits((float) opaque(1e39)); }
    static int d2fUnderflow() { return Float.floatToRawIntBits((float) opaque(-1e-50)); }
    static int d2fSubnormal() { return Float.floatToRawIntBits((float) opaque(1e-40)); }
    static int d2fHalfwayRoundsToEven() { return Float.floatToRawIntBits((float) opaque(1.0 + 0x1p-24)); }
    static int d2fNaN() { return Float.floatToIntBits((float) opaque(Double.NaN)); }
    static int roundTrip() { return (int) (double) (float) opaque(123456.789); }

    static double opaque(double v) { return v; }

    static float opaque(float v) { return v; }

    static int opaque(int v) { return v; }

    static long opaque(long v) { return v; }
}
