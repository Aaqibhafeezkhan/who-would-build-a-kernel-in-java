package java.lang;

/** Integer operations only until floating point lands (#26). */
public final class Math {

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
}
