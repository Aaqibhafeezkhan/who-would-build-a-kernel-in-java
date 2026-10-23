package duke.conformance;

/** As DoubleArithmetic, in single precision: the same SSE instructions with the other prefix. */
final class FloatArithmetic {

    static int add() { return bits(opaque(0.1f) + opaque(0.2f)); }
    static int sub() { return bits(opaque(1.0f) - opaque(0x1p-24f)); }
    static int mul() { return bits(opaque(1.1f) * opaque(1.1f)); }
    static int div() { return bits(opaque(1.0f) / opaque(3.0f)); }
    static int divByZero() { return bits(opaque(1.0f) / opaque(-0.0f)); }
    static int zeroByZero() { return Float.floatToIntBits(opaque(0.0f) / opaque(0.0f)); }
    static int overflow() { return bits(opaque(Float.MAX_VALUE) * 2); }
    static int underflowToSubnormal() { return bits(opaque(Float.MIN_NORMAL) / 3); }
    static int neg() { return bits(-opaque(1.5f)); }
    static int negZero() { return bits(-opaque(0.0f)); }
    // In float, adding 1 stops changing the sum long before 2^24 terms.
    static int sumStalls() {
        float sum = 0;
        for (int i = 0; i < 20_000_000; i += 1000) {
            sum += 1000;
        }
        return bits(sum);
    }

    static int compareNaN() {
        float a = opaque(Float.NaN);
        float b = opaque(1.0f);
        return DoubleArithmetic.flags(a < b, a <= b, a > b, a >= b, a == b, a != b, b < a, b > a);
    }
    static int compareOrdered() {
        float a = opaque(-1.0f);
        float b = opaque(1.0f);
        return DoubleArithmetic.flags(a < b, a <= b, a > b, a >= b, a == b, a != b);
    }
    static int floatCompare() {
        return Float.compare(opaque(0.0f), -0.0f) * 100 + Float.compare(opaque(1.0f), Float.NaN) * 10
                + Float.compare(opaque(Float.NaN), Float.NaN);
    }
    static int bitsRoundTrip() { return bits(Float.intBitsToFloat(opaque(0x40490fdb))); }

    static int bits(float f) { return Float.floatToRawIntBits(f); }

    static float opaque(float v) { return v; }

    static int opaque(int v) { return v; }
}
