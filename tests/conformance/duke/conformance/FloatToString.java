package duke.conformance;

/** Double.toString and Float.toString: HotSpot's shortest digits, and where it switches to E notation. */
final class FloatToString {

    static String simple() { return join(0.0, -0.0, 1.0, -1.0, 0.5, 100.0, 123.456, 1.0 / 3); }
    static String shortestRoundTrip() { return join(0.1, 0.2, 0.1 + 0.2, 0.3, 2.0 / 3, 1e23, 2e23, 9007199254740993.0); }
    static String plainRangeEdges() { return join(1e-3, 9.99e-4, 0.00123, 1e7, 9999999.0, 9999999.5, 1.0E7 + 1, 1234567.0); }
    static String scientific() { return join(1e10, 1.5e-5, 6.02214076e23, -1.602176634e-19, 1e100, 1e-100, 1e308, 1e-308); }
    static String extremes() {
        return join(Double.MAX_VALUE, Double.MIN_VALUE, 2 * Double.MIN_VALUE, 3 * Double.MIN_VALUE, Double.MIN_NORMAL,
                Double.longBitsToDouble(0x000fffffffffffffL), 0x1p-1022 * 3, 0x1p1023);
    }
    // Doubles that print as two digits though one would read back: the JDK takes the nearer.
    static String twoDigitPreference() { return join(4.9e-324, 9.9e-324, 9.9e-323, 1.0e-322, 5e-324 * 7); }
    static String specials() { return join(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY); }
    static String powersOfTwo() {
        StringBuilder sb = new StringBuilder();
        for (int e = -1074; e <= 1023; e += 37) {
            long bits = e >= -1022 ? (long) (e + 1023) << 52 : 1L << e + 1074;
            sb.append(Double.longBitsToDouble(bits)).append(' ');
        }
        return sb.toString();
    }
    static long manyValues() {
        long h = 0;
        long bits = 0x123456789abcdefL;
        for (int i = 0; i < 200; i++) {
            bits = bits * 6364136223846793005L + 1442695040888963407L;
            double d = Double.longBitsToDouble(bits);
            h = h * 31 + Double.toString(d).hashCode();
            h = h * 31 + Float.toString(Float.intBitsToFloat((int) (bits >>> 17))).hashCode();
        }
        return h;
    }

    static String floats() {
        return joinF(0f, -0f, 0.1f, 1f / 3, 1e7f, 9999999f, 1e-3f, 1e10f, Float.MAX_VALUE, Float.MIN_VALUE,
                Float.MIN_NORMAL, 16777217f, 3.4e38f, 2 * Float.MIN_VALUE, 7 * Float.MIN_VALUE, Float.NaN);
    }

    static String concatAndValueOf() {
        double d = 2.5;
        float f = -0.75f;
        Double boxed = 1e-7;
        return "d=" + d + " f=" + f + " boxed=" + boxed + " " + String.valueOf(3.0) + String.valueOf(4f)
                + new StringBuilder().append(1e21).append(0.1f);
    }

    static String join(double... values) {
        StringBuilder sb = new StringBuilder();
        for (double v : values) {
            sb.append(Double.toString(v)).append(' ');
        }
        return sb.toString();
    }

    static String joinF(float... values) {
        StringBuilder sb = new StringBuilder();
        for (float v : values) {
            sb.append(Float.toString(v)).append(' ');
        }
        return sb.toString();
    }
}
