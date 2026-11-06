package duke.conformance;

final class ControlFlow {

    static int loopSum() {
        int sum = 0;
        for (int i = 1; i <= 100; i++) {
            sum += i;
        }
        return sum;
    }

    static int nestedLoops() {
        int count = 0;
        for (int i = 0; i < 10; i++) {
            for (int j = i; j < 10; j++) {
                if ((i + j) % 3 == 0) {
                    continue;
                }
                count += j;
            }
        }
        return count;
    }

    static int whileBreak() {
        int n = 0;
        while (true) {
            n += 7;
            if (n > 50) {
                break;
            }
        }
        return n;
    }

    static int fib() {
        return fib(20);
    }

    private static int fib(int n) {
        return n < 2 ? n : fib(n - 1) + fib(n - 2);
    }

    static boolean evenOdd() {
        return isEven(10) && !isEven(7);
    }

    private static boolean isEven(int n) {
        return n == 0 || isOdd(n - 1);
    }

    private static boolean isOdd(int n) {
        return n != 0 && isEven(n - 1);
    }

    static int denseSwitch() {
        int total = 0;
        for (int i = -1; i < 8; i++) {
            switch (i) {
                case 0 -> total += 1;
                case 1 -> total += 10;
                case 2 -> total += 100;
                case 3, 4 -> total += 1000;
                case 5 -> total += 10000;
                default -> total += 100000;
            }
        }
        return total;
    }

    /** Keys on both sides of a range that starts below zero and has holes. */
    static String negativeRangeSwitch() {
        StringBuilder out = new StringBuilder();
        int[] keys = {Integer.MIN_VALUE, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, Integer.MAX_VALUE};
        for (int key : keys) {
            switch (key) {
                case -6 -> out.append('a');
                case -5 -> out.append('b');
                case -3 -> out.append('c');
                case -2 -> out.append('d');
                case 0 -> out.append('e');
                case 2 -> out.append('f');
                default -> out.append('.');
            }
        }
        return out.toString();
    }

    /** A range starting at Integer.MIN_VALUE, so subtracting the low value wraps for most keys. */
    static String switchAtIntegerMinimum() {
        StringBuilder out = new StringBuilder();
        int[] keys = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 3, Integer.MIN_VALUE + 4, -1, 0,
                Integer.MAX_VALUE};
        for (int key : keys) {
            switch (key) {
                case Integer.MIN_VALUE -> out.append('w');
                case Integer.MIN_VALUE + 1 -> out.append('x');
                case Integer.MIN_VALUE + 2 -> out.append('y');
                case Integer.MIN_VALUE + 3 -> out.append('z');
                default -> out.append('.');
            }
        }
        return out.toString();
    }

    /** Narrowed from a long with high bits set, so the key's register had more than 32 bits in it. */
    static int switchOnNarrowedLong() {
        int total = 0;
        for (long wide = 0x7_0000_0000L; wide < 0x7_0000_0006L; wide++) {
            switch ((int) wide) {
                case 1 -> total += 1;
                case 2 -> total += 20;
                case 3 -> total += 300;
                case 4 -> total += 4000;
                default -> total += 50000;
            }
        }
        return total;
    }

    static String switchOnStringsAndChars() {
        StringBuilder out = new StringBuilder();
        for (String s : new String[] {"alpha", "beta", "gamma", "delta", "omega"}) {
            out.append(switch (s) {
                case "alpha" -> 1;
                case "beta" -> 2;
                case "gamma" -> 3;
                case "delta" -> 4;
                default -> 0;
            });
        }
        for (char c = 'a'; c <= 'h'; c++) {
            switch (c) {
                case 'b', 'c' -> out.append('x');
                case 'd' -> out.append('y');
                case 'e', 'f', 'g' -> out.append('z');
                default -> out.append('-');
            }
        }
        return out.toString();
    }

    static int sparseSwitch() {
        int total = 0;
        for (int i = 0; i < 4; i++) {
            int key = i == 0 ? -1000 : i == 1 ? 7 : i == 2 ? 1 << 20 : 3;
            switch (key) {
                case -1000:
                    total += 1;
                    break;
                case 7:
                    total += 20;
                    // fall through
                case 1 << 20:
                    total += 300;
                    break;
                default:
                    total += 4000;
            }
        }
        return total;
    }

    static int conditionals() {
        int r = 0;
        for (int i = -3; i <= 3; i++) {
            if (i < 0) r += 1;
            if (i <= 0) r += 10;
            if (i > 0) r += 100;
            if (i >= 0) r += 1000;
            if (i == 0) r += 10000;
            if (i != 0) r += 100000;
        }
        return r;
    }

    static boolean referenceCompare() {
        String a = "same";
        String b = "same";
        String c = null;
        return a == b && c == null && a != null;
    }
}
