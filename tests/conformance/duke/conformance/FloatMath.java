package duke.conformance;

/** Math on doubles and floats, over the edge cases: zeros' signs, NaN, infinities, subnormals, 2^52. */
final class FloatMath {

    static final double[] EDGES = {
        0.0, -0.0, 0.5, -0.5, 0.49999999999999994, -0.49999999999999994, 1.0, -1.0, 1.5, -1.5, 2.5, -2.5,
        0x1p52 - 0.5, -(0x1p52 - 0.5), 0x1p52, -0x1p52, 0x1p52 + 1, 0x1p53 + 2, 1e300, -1e300,
        Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE, -Double.MAX_VALUE,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, 1e-310, 2.0, 0.1, 1e15 + 0.3,
        -1e15 - 0.3, 9007199254740991.0, Long.MAX_VALUE, (double) Long.MIN_VALUE,
    };

    static long floor() {
        long h = 0;
        for (double d : EDGES) {
            h = h * 31 + Double.doubleToLongBits(Math.floor(d));
        }
        return h;
    }

    static long ceil() {
        long h = 0;
        for (double d : EDGES) {
            h = h * 31 + Double.doubleToLongBits(Math.ceil(d));
        }
        return h;
    }

    static long sqrt() {
        long h = 0;
        for (double d : EDGES) {
            h = h * 31 + Double.doubleToLongBits(Math.sqrt(d));
        }
        for (int i = 0; i < 1000; i++) {
            h = h * 31 + Double.doubleToRawLongBits(Math.sqrt(i * 1.37));
        }
        return h;
    }

    static long sqrtNegativeZero() { return Double.doubleToRawLongBits(Math.sqrt(-0.0)); }

    static long abs() {
        long h = 0;
        for (double d : EDGES) {
            h = h * 31 + Double.doubleToLongBits(Math.abs(d));
            h = h * 31 + Float.floatToIntBits(Math.abs((float) d));
        }
        return h;
    }

    static long minAndMax() {
        long h = 0;
        for (double a : EDGES) {
            for (double b : EDGES) {
                h = h * 31 + Double.doubleToLongBits(Math.min(a, b));
                h = h * 31 + Double.doubleToLongBits(Math.max(a, b));
            }
        }
        return h;
    }

    static long minAndMaxOnFloats() {
        long h = 0;
        for (double x : EDGES) {
            for (double y : EDGES) {
                float a = (float) x;
                float b = (float) y;
                h = h * 31 + Float.floatToIntBits(Math.min(a, b));
                h = h * 31 + Float.floatToIntBits(Math.max(a, b));
            }
        }
        return h;
    }

    static long copySign() {
        long h = 0;
        for (double a : EDGES) {
            h = h * 31 + Double.doubleToLongBits(Math.copySign(a, -0.0));
            h = h * 31 + Double.doubleToLongBits(Math.copySign(a, 3.0));
            h = h * 31 + Float.floatToIntBits(Math.copySign((float) a, -1f));
        }
        return h;
    }

    static long circleArea() { return Double.doubleToRawLongBits(Math.PI * 2.5 * 2.5 + Math.E); }

    static long hypotenuse() { return Double.doubleToRawLongBits(Math.sqrt(3.0 * 3.0 + 4.1 * 4.1)); }
}
