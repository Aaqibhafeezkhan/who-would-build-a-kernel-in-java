package duke.conformance;

/** Overflow-checked Math methods: results and exception messages must match the JDK. */
final class MathExact {

    static String addExactInt() {
        String r = "";
        r = then(r, add(opaque(2), opaque(3)));
        r = then(r, add(opaque(-5), opaque(5)));
        r = then(r, add(opaque(Integer.MAX_VALUE), opaque(0)));
        r = then(r, add(opaque(Integer.MAX_VALUE), opaque(Integer.MIN_VALUE)));
        r = then(r, add(opaque(Integer.MAX_VALUE), opaque(1)));
        r = then(r, add(opaque(Integer.MIN_VALUE), opaque(-1)));
        r = then(r, add(opaque(Integer.MIN_VALUE), opaque(Integer.MIN_VALUE)));
        return r;
    }

    static String addExactLong() {
        String r = "";
        r = then(r, add(opaque(2L), opaque(3L)));
        r = then(r, add(opaque(-5L), opaque(5L)));
        r = then(r, add(opaque(Long.MAX_VALUE), opaque(0L)));
        r = then(r, add(opaque(Long.MAX_VALUE), opaque(Long.MIN_VALUE)));
        r = then(r, add(opaque(Long.MAX_VALUE), opaque(1L)));
        r = then(r, add(opaque(Long.MIN_VALUE), opaque(-1L)));
        r = then(r, add(opaque(Long.MIN_VALUE), opaque(Long.MIN_VALUE)));
        return r;
    }

    static String subtractExactInt() {
        String r = "";
        r = then(r, subtract(opaque(5), opaque(3)));
        r = then(r, subtract(opaque(Integer.MIN_VALUE), opaque(1)));
        r = then(r, subtract(opaque(Integer.MAX_VALUE), opaque(-1)));
        r = then(r, subtract(opaque(0), opaque(Integer.MIN_VALUE)));
        r = then(r, subtract(opaque(-1), opaque(Integer.MIN_VALUE)));
        r = then(r, subtract(opaque(Integer.MIN_VALUE), opaque(Integer.MIN_VALUE)));
        r = then(r, subtract(opaque(Integer.MIN_VALUE), opaque(-1)));
        return r;
    }

    static String subtractExactLong() {
        String r = "";
        r = then(r, subtract(opaque(5L), opaque(3L)));
        r = then(r, subtract(opaque(Long.MIN_VALUE), opaque(1L)));
        r = then(r, subtract(opaque(Long.MAX_VALUE), opaque(-1L)));
        r = then(r, subtract(opaque(0L), opaque(Long.MIN_VALUE)));
        r = then(r, subtract(opaque(-1L), opaque(Long.MIN_VALUE)));
        r = then(r, subtract(opaque(Long.MIN_VALUE), opaque(Long.MIN_VALUE)));
        r = then(r, subtract(opaque(Long.MIN_VALUE), opaque(-1L)));
        return r;
    }

    static String multiplyExactInt() {
        String r = "";
        r = then(r, multiply(opaque(46340), opaque(46340)));
        r = then(r, multiply(opaque(46341), opaque(46341)));
        r = then(r, multiply(opaque(Integer.MIN_VALUE), opaque(-1)));
        r = then(r, multiply(opaque(-1), opaque(Integer.MIN_VALUE)));
        r = then(r, multiply(opaque(Integer.MIN_VALUE), opaque(1)));
        r = then(r, multiply(opaque(Integer.MAX_VALUE), opaque(2)));
        r = then(r, multiply(opaque(0), opaque(Integer.MIN_VALUE)));
        r = then(r, multiply(opaque(65536), opaque(32768)));
        r = then(r, multiply(opaque(65536), opaque(-32768)));
        return r;
    }

    static String multiplyExactLong() {
        String r = "";
        r = then(r, multiply(opaque(3037000499L), opaque(3037000499L)));
        r = then(r, multiply(opaque(3037000500L), opaque(3037000500L)));
        r = then(r, multiply(opaque(Long.MIN_VALUE), opaque(-1L)));
        r = then(r, multiply(opaque(-1L), opaque(Long.MIN_VALUE)));
        r = then(r, multiply(opaque(Long.MIN_VALUE), opaque(1L)));
        r = then(r, multiply(opaque(Long.MAX_VALUE), opaque(2L)));
        r = then(r, multiply(opaque(0L), opaque(Long.MIN_VALUE)));
        r = then(r, multiply(opaque(4294967296L), opaque(2147483648L)));
        r = then(r, multiply(opaque(4294967296L), opaque(-2147483648L)));
        return r;
    }

    static String negateExactInt() {
        String r = "";
        r = then(r, negate(opaque(5)));
        r = then(r, negate(opaque(-5)));
        r = then(r, negate(opaque(0)));
        r = then(r, negate(opaque(Integer.MAX_VALUE)));
        r = then(r, negate(opaque(Integer.MIN_VALUE)));
        return r;
    }

    static String negateExactLong() {
        String r = "";
        r = then(r, negate(opaque(5L)));
        r = then(r, negate(opaque(-5L)));
        r = then(r, negate(opaque(0L)));
        r = then(r, negate(opaque(Long.MAX_VALUE)));
        r = then(r, negate(opaque(Long.MIN_VALUE)));
        return r;
    }

    static String incrementExactInt() {
        String r = "";
        r = then(r, increment(opaque(0)));
        r = then(r, increment(opaque(-1)));
        r = then(r, increment(opaque(Integer.MAX_VALUE - 1)));
        r = then(r, increment(opaque(Integer.MAX_VALUE)));
        r = then(r, increment(opaque(Integer.MIN_VALUE)));
        return r;
    }

    static String incrementExactLong() {
        String r = "";
        r = then(r, increment(opaque(0L)));
        r = then(r, increment(opaque(-1L)));
        r = then(r, increment(opaque(Long.MAX_VALUE - 1)));
        r = then(r, increment(opaque(Long.MAX_VALUE)));
        r = then(r, increment(opaque(Long.MIN_VALUE)));
        return r;
    }

    static String toIntExactEdges() {
        String r = "";
        r = then(r, toInt(opaque(0L)));
        r = then(r, toInt(opaque(-1L)));
        r = then(r, toInt(opaque(2147483647L)));
        r = then(r, toInt(opaque(2147483648L)));
        r = then(r, toInt(opaque(-2147483648L)));
        r = then(r, toInt(opaque(-2147483649L)));
        // Low 32 bits are zero, or all ones with the high bits clear: both must still overflow.
        r = then(r, toInt(opaque(4294967296L)));
        r = then(r, toInt(opaque(4294967295L)));
        r = then(r, toInt(opaque(Long.MAX_VALUE)));
        r = then(r, toInt(opaque(Long.MIN_VALUE)));
        return r;
    }

    private static String add(int x, int y) {
        try {
            return String.valueOf(Math.addExact(x, y));
        } catch (ArithmeticException e) {
            return e.getMessage();
        }
    }

    private static String add(long x, long y) {
        try {
            return String.valueOf(Math.addExact(x, y));
        } catch (ArithmeticException e) {
            return e.getMessage();
        }
    }

    private static String subtract(int x, int y) {
        try {
            return String.valueOf(Math.subtractExact(x, y));
        } catch (ArithmeticException e) {
            return e.getMessage();
        }
    }

    private static String subtract(long x, long y) {
        try {
            return String.valueOf(Math.subtractExact(x, y));
        } catch (ArithmeticException e) {
            return e.getMessage();
        }
    }

    private static String multiply(int x, int y) {
        try {
            return String.valueOf(Math.multiplyExact(x, y));
        } catch (ArithmeticException e) {
            return e.getMessage();
        }
    }

    private static String multiply(long x, long y) {
        try {
            return String.valueOf(Math.multiplyExact(x, y));
        } catch (ArithmeticException e) {
            return e.getMessage();
        }
    }

    private static String negate(int a) {
        try {
            return String.valueOf(Math.negateExact(a));
        } catch (ArithmeticException e) {
            return e.getMessage();
        }
    }

    private static String negate(long a) {
        try {
            return String.valueOf(Math.negateExact(a));
        } catch (ArithmeticException e) {
            return e.getMessage();
        }
    }

    private static String increment(int a) {
        try {
            return String.valueOf(Math.incrementExact(a));
        } catch (ArithmeticException e) {
            return e.getMessage();
        }
    }

    private static String increment(long a) {
        try {
            return String.valueOf(Math.incrementExact(a));
        } catch (ArithmeticException e) {
            return e.getMessage();
        }
    }

    private static String toInt(long value) {
        try {
            return String.valueOf(Math.toIntExact(value));
        } catch (ArithmeticException e) {
            return e.getMessage();
        }
    }

    private static String then(String acc, String next) {
        return acc.concat(next).concat("|");
    }

    // javac folds compile-time constants, so route operands through a call.
    private static int opaque(int v) {
        return v;
    }

    private static long opaque(long v) {
        return v;
    }
}
