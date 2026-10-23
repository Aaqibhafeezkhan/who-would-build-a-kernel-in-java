package duke.conformance;

/** Floats and doubles through everything that holds a value: locals, fields, arrays, arguments, returns. */
final class FloatStorage {

    static double staticDouble = 2.5;
    static float staticFloat = -0.75f;
    static final double CONSTANT = 6.02214076e23;

    double instanceDouble;
    float instanceFloat;
    long after;

    static long staticFields() {
        staticDouble *= opaque(3.0);
        staticFloat -= opaque(1.0f);
        return Double.doubleToRawLongBits(staticDouble) ^ Float.floatToRawIntBits(staticFloat);
    }

    static long staticConstant() { return Double.doubleToRawLongBits(opaqueConstant()); }

    static double opaqueConstant() { return CONSTANT; }

    // A long after the float: a float store that wrote eight bytes would clobber it.
    static long instanceFields() {
        FloatStorage s = new FloatStorage();
        s.after = 0x1122334455667788L;
        s.instanceFloat = opaque(1.25f);
        s.instanceDouble = opaque(-8.5);
        s.instanceDouble += s.instanceFloat;
        return Double.doubleToRawLongBits(s.instanceDouble) + s.after;
    }

    static long doubleArray() {
        double[] values = new double[8];
        for (int i = 0; i < values.length; i++) {
            values[i] = i * opaque(0.5) - 1;
        }
        double sum = 0;
        for (double v : values) {
            sum += v * v;
        }
        return Double.doubleToRawLongBits(sum) + values.length;
    }

    static int floatArrayNeighbors() {
        float[] values = new float[5];
        values[2] = opaque(Float.NaN);
        values[1] = opaque(-1.5f);
        values[3] = opaque(2.0f);
        return Float.floatToRawIntBits(values[1]) ^ Float.floatToRawIntBits(values[3]) ^ (values[0] == 0.0f ? 1 : 0) ^ (values[4] == 0 ? 2 : 0);
    }

    static long multiDimensional() {
        float[][] f = new float[3][2];
        double[][] d = new double[2][3];
        f[2][1] = opaque(4.5f);
        d[1][2] = opaque(-4.5);
        return Float.floatToRawIntBits(f[2][1]) + Double.doubleToRawLongBits(d[1][2]) + (f[1][0] == 0 && d[0][1] == 0 ? 1 : 0);
    }

    // Arguments of every width, interleaved: a slot counted wrong reads its neighbour.
    static long mixedArguments() {
        return Double.doubleToRawLongBits(mixed(opaque(1.5f), 7L, opaque(2.25), 3, opaque(-0.5f), opaque(100.0)));
    }

    static double mixed(float a, long b, double c, int d, float e, double f) {
        return a * b + c * d - e / f;
    }

    static long returnsDouble() {
        double x = opaque(10.0);
        double y = halve(halve(x)) + halve(1);
        return Double.doubleToRawLongBits(y);
    }

    static double halve(double v) { return v / 2; }

    static float twice(float v) { return v * 2; }

    static int returnsFloat() { return Float.floatToRawIntBits(twice(twice(opaque(0.375f)))); }

    static long localsAcrossCalls() {
        double a = opaque(1.0 / 3);
        float b = opaque(2.0f / 3);
        long c = 5;
        double d = halve(a) + twice(b);
        return Double.doubleToRawLongBits(a + b + c + d);
    }

    static double opaque(double v) { return v; }

    static float opaque(float v) { return v; }
}
