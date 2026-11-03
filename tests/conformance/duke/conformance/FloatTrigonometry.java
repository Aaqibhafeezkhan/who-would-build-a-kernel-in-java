package duke.conformance;

/** StrictMath's trig functions, fdlibm's to the bit, from small arguments through the reduction of huge ones. */
final class FloatTrigonometry {

    static final double[] EDGES = {
        0.0, -0.0, 1e-300, -1e-310, Double.MIN_VALUE, 0x1p-28, 0x1p-27, 0.1, 0.5, -0.5, 0.6744, 0.78125, 1.0, -1.0,
        Math.PI / 4, Math.PI / 2, -Math.PI / 2, 3 * Math.PI / 4, Math.PI, 2 * Math.PI, 10.0, 100.0, 1e6,
        0x1p19 * Math.PI / 2, 0x1p20, 1e22, 1e300, Double.MAX_VALUE, -Double.MAX_VALUE, Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY, Double.NaN, 0.975, -0.975, 0.9999999999999999, 1.0000000000000002, 2.4375, 0x1p66,
        // The double closest to a multiple of pi/2, where the large reduction cancels the most bits.
        0x1.6ac5b262ca1ffp+849,
    };

    static long sinCosTan() {
        long h = 0;
        for (double x : EDGES) {
            h = h * 31 + Double.doubleToLongBits(StrictMath.sin(x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.cos(x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.tan(x));
        }
        return h;
    }

    static long sweep() {
        long h = 0;
        for (int i = -3000; i <= 3000; i += 3) {
            double x = i * 0.0127;
            h = h * 31 + Double.doubleToLongBits(StrictMath.sin(x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.cos(x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.tan(x));
        }
        return h;
    }

    // 1.2345678901234567 * 2^e over the large reduction's exponents, so each window of 2/pi gets used.
    static long hugeArguments() {
        long h = 0;
        for (int e = 20; e < 1024; e += 3) {
            double x = Double.longBitsToDouble((long) (e + 1023) << 52 | 0x3c0ca428c59fbL);
            h = h * 31 + Double.doubleToLongBits(StrictMath.sin(x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.cos(-x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.tan(x));
        }
        return h;
    }

    // Near multiples of pi/2 the medium reduction needs its second piece of pi/2, and for these
    // doubles, closest of all to one, its third.
    static final double[] CLOSEST_TO_HALF_PI_MULTIPLES = {
        0x1.fe9e875a67a0bp15, 0x1.65a1dd290660fp16, 0x1.6d9be62d85044p16, 0x1.0dec1da5fa53fp17,
        0x1.193e3c2b5f498p17, 0x1.41fde4b8af12ep17, 0x1.49decbc1d972p17, 0x1.b16eb242b3df5p17,
    };

    static long nearMultiplesOfHalfPi() {
        long h = 0;
        for (double x : CLOSEST_TO_HALF_PI_MULTIPLES) {
            h = h * 31 + Double.doubleToLongBits(StrictMath.sin(x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.cos(-x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.tan(x));
        }
        for (int n = 1; n < 2000; n += 7) {
            double x = n * (Math.PI / 2);
            for (int d = -2; d <= 2; d++) {
                double v = Double.longBitsToDouble(Double.doubleToRawLongBits(x) + d);
                h = h * 31 + Double.doubleToLongBits(StrictMath.sin(v));
                h = h * 31 + Double.doubleToLongBits(StrictMath.cos(v));
            }
        }
        return h;
    }

    static long inverse() {
        long h = 0;
        for (double x : EDGES) {
            h = h * 31 + Double.doubleToLongBits(StrictMath.asin(x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.acos(x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.atan(x));
        }
        for (int i = -1000; i <= 1000; i++) {
            double x = i / 1000.0;
            h = h * 31 + Double.doubleToLongBits(StrictMath.asin(x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.acos(x));
            h = h * 31 + Double.doubleToLongBits(StrictMath.atan(x * 7));
        }
        return h;
    }

    static long atan2() {
        long h = 0;
        for (double y : EDGES) {
            for (double x : EDGES) {
                h = h * 31 + Double.doubleToLongBits(StrictMath.atan2(y, x));
            }
        }
        return h;
    }

    static String values() {
        return StrictMath.sin(1) + " " + StrictMath.cos(1) + " " + StrictMath.tan(1) + " " + StrictMath.atan2(1, -1)
                + " " + StrictMath.acos(-1) + " " + StrictMath.sin(1e22);
    }
}
