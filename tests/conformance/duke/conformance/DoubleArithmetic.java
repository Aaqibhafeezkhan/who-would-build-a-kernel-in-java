package duke.conformance;

/** Results as bits, so an off-by-one-ulp rounding shows; NaNs canonical, since their raw bits differ by CPU. */
final class DoubleArithmetic {

    static long add() { return bits(opaque(0.1) + opaque(0.2)); }
    static long sub() { return bits(opaque(1.0) - opaque(0x1p-53)); }
    static long mul() { return bits(opaque(1.1) * opaque(1.1)); }
    static long div() { return bits(opaque(1.0) / opaque(3.0)); }
    static long divByZero() { return bits(opaque(-1.0) / opaque(0.0)); }
    static long divByNegativeZero() { return bits(opaque(1.0) / opaque(-0.0)); }
    static long zeroByZero() { return Double.doubleToLongBits(opaque(0.0) / opaque(0.0)); }
    static long infinityMinusInfinity() { return Double.doubleToLongBits(opaque(Double.POSITIVE_INFINITY) - Double.POSITIVE_INFINITY); }
    static long overflow() { return bits(opaque(Double.MAX_VALUE) * 2); }
    static long underflowToSubnormal() { return bits(opaque(Double.MIN_NORMAL) / 3); }
    static long subnormalTimesSubnormal() { return bits(opaque(Double.MIN_VALUE) * Double.MIN_VALUE); }
    static long negativeZeroSum() { return bits(opaque(-0.0) + opaque(-0.0)); }
    static long zeroSumOfOpposites() { return bits(opaque(1.5) + opaque(-1.5)); }
    static long neg() { return bits(-opaque(1.5)); }
    static long negZero() { return bits(-opaque(0.0)); }
    static long negNaNStaysNaN() { return Double.doubleToLongBits(-opaque(Double.NaN)); }
    static long harmonic() {
        double sum = 0;
        for (int i = 1; i <= 1000; i++) {
            sum += 1.0 / i;
        }
        return bits(sum);
    }
    static long compoundInterest() {
        double balance = 1000;
        for (int year = 0; year < 30; year++) {
            balance *= 1.05;
            balance -= 12.5;
        }
        return bits(balance);
    }

    static int compareOrdered() {
        double a = opaque(1.0);
        double b = opaque(2.0);
        return flags(a < b, a <= b, a > b, a >= b, a == b, a != b);
    }
    static int compareEqual() {
        double a = opaque(2.0);
        double b = opaque(2.0);
        return flags(a < b, a <= b, a > b, a >= b, a == b, a != b);
    }
    // Every comparison but != is false with NaN on either side: javac picks dcmpl or dcmpg to make it so.
    static int compareNaNLeft() {
        double a = opaque(Double.NaN);
        double b = opaque(1.0);
        return flags(a < b, a <= b, a > b, a >= b, a == b, a != b);
    }
    static int compareNaNRight() {
        double a = opaque(1.0);
        double b = opaque(Double.NaN);
        return flags(a < b, a <= b, a > b, a >= b, a == b, a != b);
    }
    static int compareZeros() {
        double a = opaque(-0.0);
        double b = opaque(0.0);
        return flags(a < b, a <= b, a > b, a >= b, a == b, a != b);
    }
    static int compareInfinities() {
        double a = opaque(Double.NEGATIVE_INFINITY);
        double b = opaque(-Double.MAX_VALUE);
        return flags(a < b, a <= b, a > b, a >= b, a == b, a != b);
    }

    static int doubleCompare() {
        return Double.compare(opaque(-0.0), 0.0) * 100 + Double.compare(opaque(Double.NaN), Double.POSITIVE_INFINITY) * 10
                + Double.compare(opaque(Double.NaN), Double.NaN);
    }
    static int classify() {
        return flags(Double.isNaN(opaque(Double.NaN)), Double.isNaN(opaque(1.0)), Double.isInfinite(opaque(Double.NEGATIVE_INFINITY)),
                Double.isInfinite(opaque(Double.MAX_VALUE)), Double.isFinite(opaque(Double.MAX_VALUE)), Double.isFinite(opaque(Double.NaN)));
    }
    static long bitsRoundTrip() { return bits(Double.longBitsToDouble(opaque(0x400921fb54442d18L))); }

    static int flags(boolean... bs) {
        int f = 0;
        for (boolean b : bs) {
            f = f * 2 + (b ? 1 : 0);
        }
        return f;
    }

    static long bits(double d) { return Double.doubleToRawLongBits(d); }

    static double opaque(double v) { return v; }

    static long opaque(long v) { return v; }
}
