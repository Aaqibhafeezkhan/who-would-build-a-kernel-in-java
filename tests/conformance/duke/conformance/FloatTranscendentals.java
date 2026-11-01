package duke.conformance;

/** StrictMath.exp, log and pow, which are fdlibm's to the bit: edges, thresholds, and sweeps between them. */
final class FloatTranscendentals {

    static final double[] EDGES = {
        0.0, -0.0, 1.0, -1.0, 2.0, -2.0, 0.5, -0.5, 3.0, -3.0, 0.1, 10.0, 1e-300, 1e300, 1e-310, -1e-310,
        Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE, -Double.MAX_VALUE, Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY, Double.NaN, Math.E, 0.9999999999999999, 1.0000000000000002,
        // exp overflows past the first and underflows past the second; 1024 and -1075 are pow's.
        709.782712893384, 709.7827128933841, -745.1332191019411, -745.1332191019412, 1024.0, -1075.0,
        0x1p31, 0x1p31 + 1, 0x1p53, 0x1p53 + 2, 0x1p64, 0x1p64 * 3, -0x1p53 - 2, 1e-9, -1e-9, 0x1p-30,
    };

    static long exp() {
        long h = 0;
        for (double x : EDGES) {
            h = h * 31 + Double.doubleToLongBits(StrictMath.exp(x));
        }
        for (int i = -7500; i <= 7500; i += 7) {
            h = h * 31 + Double.doubleToLongBits(StrictMath.exp(i * 0.1003));
        }
        return h;
    }

    static long log() {
        long h = 0;
        for (double x : EDGES) {
            h = h * 31 + Double.doubleToLongBits(StrictMath.log(x));
        }
        for (int i = 0; i < 2000; i++) {
            h = h * 31 + Double.doubleToLongBits(StrictMath.log(1 + (i - 1000) * 0x1p-14));
            h = h * 31 + Double.doubleToLongBits(StrictMath.log(Double.longBitsToDouble(i * 0x1fd2e5c8d3a4b1L)));
        }
        return h;
    }

    static long pow() {
        long h = 0;
        for (double x : EDGES) {
            for (double y : EDGES) {
                h = h * 31 + Double.doubleToLongBits(StrictMath.pow(x, y));
            }
        }
        return h;
    }

    static long powSweep() {
        long h = 0;
        for (int i = 1; i < 300; i++) {
            for (int j = -30; j < 30; j++) {
                h = h * 31 + Double.doubleToLongBits(StrictMath.pow(i * 0.37, j * 1.13));
                h = h * 31 + Double.doubleToLongBits(StrictMath.pow(-i * 0.5, j));
            }
        }
        return h;
    }

    // 2^-1074 is reached through scalbn, the only place pow builds a subnormal.
    static String subnormalPow() {
        return StrictMath.pow(2, -1074) + " " + StrictMath.pow(2, -1050.5) + " " + StrictMath.pow(0.5, 1060) + " "
                + StrictMath.pow(-2, -1073) + " " + StrictMath.pow(10, -320);
    }

    static String exact() {
        return StrictMath.pow(2, 10) + " " + StrictMath.pow(10, 22) + " " + StrictMath.exp(0) + " "
                + StrictMath.log(1) + " " + StrictMath.pow(-8, 1.0 / 3) + " " + Math.pow(3, 4);
    }
}
