package duke.conformance;

/** StrictMath's log10, expm1, log1p, sinh, cosh, tanh, cbrt and hypot, fdlibm's to the bit. */
final class FloatHyperbolic {

    static final double[] EDGES = {
        0.0, -0.0, 1e-300, -1e-310, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL, 0x1p-55, 0x1p-54,
        0x1p-29, 0x1p-28, 0.1, -0.1, 0.25, -0.25, 0.34657359027997264, 0.5, -0.5, 1.0, -1.0, -0.2929, 0.41422,
        2.0, 10.0, 22.0, -22.0, 38.97, -38.97, 56 * 0.6931471805599453, 100.0, 709.78, 710.4758600739439,
        710.475860073944, -710.4758600739439, -710.475860073944, 1e10, 1e300, Double.MAX_VALUE,
        -Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, -0.9999999999999999,
        -1.0000000000000002, 8.0, 27.0, -1000.0, 1e-5, 0x1p53,
        // Under the overflow threshold's high word, with a low word that's negative as an int.
        0x1.633cep9, -0x1.633ce7fffffffp9,
        // The one subnormal in 50 million found where cbrt's first guess keeps x's low word.
        0x0.149018e92698cp-1022,
    };

    static long logs() {
        long h = 0;
        for (double x : EDGES) {
            h = h * 31 + Double.doubleToLongBits(StrictMath.log10(x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.log1p(x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.expm1(x));
        }
        for (int i = -2000; i <= 2000; i++) {
            double x = i * 0.0173;
            h = h * 31 + Double.doubleToLongBits(StrictMath.expm1(x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.log1p(x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.log10(Math.abs(x) * 1e3));
        }
        return h;
    }

    static long hyperbolic() {
        long h = 0;
        for (double x : EDGES) {
            h = h * 31 + Double.doubleToLongBits(StrictMath.sinh(x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.cosh(x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.tanh(x));
        }
        for (int i = -3000; i <= 3000; i += 3) {
            double x = i * 0.2371;
            h = h * 31 + Double.doubleToLongBits(StrictMath.sinh(x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.cosh(x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.tanh(x / 20));
        }
        return h;
    }

    static long cbrt() {
        long h = 0;
        for (double x : EDGES) {
            h = h * 31 + Double.doubleToLongBits(StrictMath.cbrt(x));
        }
        for (int i = 0; i < 3000; i++) {
            h = h * 31 + Double.doubleToLongBits(StrictMath.cbrt((i - 1500) * 1.37e-3));
            h = h * 31 + Double.doubleToLongBits(StrictMath.cbrt(Double.longBitsToDouble(i * 0x2cd5a1e3b70f9L)));
        }
        return h;
    }

    static long hypot() {
        long h = 0;
        for (double x : EDGES) {
            for (double y : EDGES) {
                h = h * 31 + Double.doubleToLongBits(StrictMath.hypot(x, y));
            }
        }
        for (int i = 1; i < 2000; i++) {
            h = h * 31 + Double.doubleToLongBits(StrictMath.hypot(i * 0.731, 1000.0 / i));
        }
        return h;
    }

    static String values() {
        return StrictMath.log10(1000) + " " + StrictMath.cbrt(27) + " " + StrictMath.cbrt(-8) + " "
                + StrictMath.hypot(3, 4) + " " + StrictMath.expm1(1e-10) + " " + StrictMath.log1p(-1) + " "
                + StrictMath.tanh(Double.NEGATIVE_INFINITY);
    }
}
