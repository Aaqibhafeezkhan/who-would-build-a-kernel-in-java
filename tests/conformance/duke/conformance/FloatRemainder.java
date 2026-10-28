package duke.conformance;

/** % on floats and doubles: truncating, signed like the dividend, and exact, so compared as bits. */
final class FloatRemainder {

    static long simple() { return bits(opaque(5.5) % opaque(2.0)); }
    static long negativeDividend() { return bits(opaque(-5.5) % opaque(2.0)); }
    static long negativeDivisor() { return bits(opaque(5.5) % opaque(-2.0)); }
    static long inexactDivisor() { return bits(opaque(1.0) % opaque(0.1)); }
    // Exponents far apart, so fprem has to go round several times.
    static long hugeDividend() { return bits(opaque(1e300) % opaque(3.0)); }
    static long widestGap() { return bits(opaque(Double.MAX_VALUE) % opaque(Double.MIN_VALUE)); }
    static long maxByOddSubnormal() { return bits(opaque(Double.MAX_VALUE) % opaque(3 * Double.MIN_VALUE)); }
    static long subnormals() { return bits(opaque(7 * Double.MIN_VALUE) % opaque(2 * Double.MIN_VALUE)); }
    static long smallerDividend() { return bits(opaque(1.5) % opaque(4.0)); }
    static long byInfinity() { return bits(opaque(-3.25) % opaque(Double.NEGATIVE_INFINITY)); }
    static long negativeZero() { return bits(opaque(-0.0) % opaque(1.0)); }
    static long exactMultipleKeepsSign() { return bits(opaque(-6.0) % opaque(3.0)); }
    static long byZero() { return Double.doubleToLongBits(opaque(1.0) % opaque(0.0)); }
    static long ofInfinity() { return Double.doubleToLongBits(opaque(Double.POSITIVE_INFINITY) % opaque(2.0)); }
    static long ofNaN() { return Double.doubleToLongBits(opaque(Double.NaN) % opaque(2.0)); }
    static long byNaN() { return Double.doubleToLongBits(opaque(2.0) % opaque(Double.NaN)); }
    static long inALoop() {
        double sum = 0;
        for (int i = 1; i <= 100; i++) {
            sum += (i * 1.7) % (i % 7 + 0.5);
        }
        return bits(sum);
    }
    // Leaves the operands below on the Java stack where they were.
    static long underOtherOperands() { return opaqueLong(41) + bits(opaque(9.0) % opaque(4.0)) + opaqueLong(1); }

    static int floatSimple() { return bits(opaque(5.5f) % opaque(2f)); }
    static int floatNegativeDividend() { return bits(opaque(-7f) % opaque(2.5f)); }
    static int floatHugeDividend() { return bits(opaque(1e30f) % opaque(3f)); }
    static int floatWidestGap() { return bits(opaque(Float.MAX_VALUE) % opaque(Float.MIN_VALUE)); }
    static int floatInexactDivisor() { return bits(opaque(1f) % opaque(0.1f)); }
    static int floatNegativeZero() { return bits(opaque(-0f) % opaque(5f)); }
    static int floatByInfinity() { return bits(opaque(2.5f) % opaque(Float.POSITIVE_INFINITY)); }
    static int floatByZero() { return Float.floatToIntBits(opaque(1f) % opaque(0f)); }
    static int floatOfInfinity() { return Float.floatToIntBits(opaque(Float.NEGATIVE_INFINITY) % opaque(2f)); }
    static int floatUnderOtherOperands() { return opaque(41) + bits(opaque(9f) % opaque(4f)) + opaque(1); }

    static long bits(double d) { return Double.doubleToRawLongBits(d); }

    static int bits(float f) { return Float.floatToRawIntBits(f); }

    static double opaque(double v) { return v; }

    static float opaque(float v) { return v; }

    static int opaque(int v) { return v; }

    static long opaqueLong(long v) { return v; }
}
