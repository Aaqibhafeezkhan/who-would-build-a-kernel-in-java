package java.lang;

import duke.rt.Magic;

public final class Math {

    public static final double E = 2.718281828459045;
    public static final double PI = 3.141592653589793;

    private Math() {
    }

    public static int abs(int a) {
        return a < 0 ? -a : a;
    }

    public static long abs(long a) {
        return a < 0 ? -a : a;
    }

    public static int min(int a, int b) {
        return a <= b ? a : b;
    }

    public static long min(long a, long b) {
        return a <= b ? a : b;
    }

    public static int max(int a, int b) {
        return a >= b ? a : b;
    }

    public static long max(long a, long b) {
        return a >= b ? a : b;
    }

    public static int floorDiv(int x, int y) {
        int q = x / y;
        return (x % y != 0 && (x ^ y) < 0) ? q - 1 : q;
    }

    public static long floorDiv(long x, long y) {
        long q = x / y;
        return (x % y != 0 && (x ^ y) < 0) ? q - 1 : q;
    }

    public static int floorMod(int x, int y) {
        int m = x % y;
        return (m != 0 && (m ^ y) < 0) ? m + y : m;
    }

    public static long floorMod(long x, long y) {
        long m = x % y;
        return (m != 0 && (m ^ y) < 0) ? m + y : m;
    }

    public static int clamp(long value, int min, int max) {
        return (int) min(max, max(value, min));
    }

    public static int addExact(int x, int y) {
        int r = x + y;
        // Overflow means x and y share a sign and the wrapped result has the other one.
        if (((x ^ r) & (y ^ r)) < 0) {
            throw new ArithmeticException("integer overflow");
        }
        return r;
    }

    public static long addExact(long x, long y) {
        long r = x + y;
        if (((x ^ r) & (y ^ r)) < 0) {
            throw new ArithmeticException("long overflow");
        }
        return r;
    }

    public static int subtractExact(int x, int y) {
        int r = x - y;
        // Overflow means x and y differ in sign and the result's sign differs from x's.
        if (((x ^ y) & (x ^ r)) < 0) {
            throw new ArithmeticException("integer overflow");
        }
        return r;
    }

    public static long subtractExact(long x, long y) {
        long r = x - y;
        if (((x ^ y) & (x ^ r)) < 0) {
            throw new ArithmeticException("long overflow");
        }
        return r;
    }

    public static int multiplyExact(int x, int y) {
        long r = (long) x * y;
        if ((int) r != r) {
            throw new ArithmeticException("integer overflow");
        }
        return (int) r;
    }

    public static long multiplyExact(long x, long y) {
        if (x == 0 || y == 0) {
            return 0;
        }
        long r = x * y;
        // Dividing back exposes wraparound. MIN_VALUE * -1 is checked first because
        // its division wraps to MIN_VALUE as well and would look consistent.
        if ((x == Long.MIN_VALUE && y == -1) || r / y != x) {
            throw new ArithmeticException("long overflow");
        }
        return r;
    }

    public static int negateExact(int a) {
        if (a == Integer.MIN_VALUE) {
            throw new ArithmeticException("integer overflow");
        }
        return -a;
    }

    public static long negateExact(long a) {
        if (a == Long.MIN_VALUE) {
            throw new ArithmeticException("long overflow");
        }
        return -a;
    }

    public static int incrementExact(int a) {
        if (a == Integer.MAX_VALUE) {
            throw new ArithmeticException("integer overflow");
        }
        return a + 1;
    }

    public static long incrementExact(long a) {
        if (a == Long.MAX_VALUE) {
            throw new ArithmeticException("long overflow");
        }
        return a + 1;
    }

    public static int toIntExact(long value) {
        if ((int) value != value) {
            throw new ArithmeticException("integer overflow");
        }
        return (int) value;
    }

    public static double abs(double a) {
        return Double.longBitsToDouble(Double.doubleToRawLongBits(a) & Long.MAX_VALUE);
    }

    public static float abs(float a) {
        return Float.intBitsToFloat(Float.floatToRawIntBits(a) & Integer.MAX_VALUE);
    }

    // NaN wins, and -0.0 is below 0.0, which < can't see. Equal values other than zeros have the
    // same bits, so or-ing (and-ing in max) the bits only ever picks a zero's sign.
    public static double min(double a, double b) {
        if (a != a) {
            return a;
        }
        if (a == b) {
            return Double.longBitsToDouble(Double.doubleToRawLongBits(a) | Double.doubleToRawLongBits(b));
        }
        return a < b ? a : b;
    }

    public static double max(double a, double b) {
        if (a != a) {
            return a;
        }
        if (a == b) {
            return Double.longBitsToDouble(Double.doubleToRawLongBits(a) & Double.doubleToRawLongBits(b));
        }
        return a > b ? a : b;
    }

    public static float min(float a, float b) {
        if (a != a) {
            return a;
        }
        if (a == b) {
            return Float.intBitsToFloat(Float.floatToRawIntBits(a) | Float.floatToRawIntBits(b));
        }
        return a < b ? a : b;
    }

    public static float max(float a, float b) {
        if (a != a) {
            return a;
        }
        if (a == b) {
            return Float.intBitsToFloat(Float.floatToRawIntBits(a) & Float.floatToRawIntBits(b));
        }
        return a > b ? a : b;
    }

    public static double copySign(double magnitude, double sign) {
        return Double.longBitsToDouble(Double.doubleToRawLongBits(magnitude) & Long.MAX_VALUE
                | Double.doubleToRawLongBits(sign) & Long.MIN_VALUE);
    }

    public static float copySign(float magnitude, float sign) {
        return Float.intBitsToFloat(Float.floatToRawIntBits(magnitude) & Integer.MAX_VALUE
                | Float.floatToRawIntBits(sign) & Integer.MIN_VALUE);
    }

    public static double sqrt(double a) {
        return Magic.sqrt(a);
    }

    // Through long rather than roundsd, which needs SSE4.1 and QEMU's default CPU hasn't got it.
    // From 2^52 up every double is whole already; the sign copy keeps floor(-0.0) and ceil(-0.5) at -0.0.
    public static double floor(double a) {
        if (!(abs(a) < 0x1p52)) {
            return a;
        }
        double r = (long) a;
        if (r > a) {
            r -= 1;
        }
        return r == 0 ? copySign(r, a) : r;
    }

    public static double ceil(double a) {
        if (!(abs(a) < 0x1p52)) {
            return a;
        }
        double r = (long) a;
        if (r < a) {
            r += 1;
        }
        return r == 0 ? copySign(r, a) : r;
    }
}
