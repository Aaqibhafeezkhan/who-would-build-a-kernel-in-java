package duke.conformance;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class Records {

    record Point(int x, int y) {
    }

    record Line(Point from, Point to) {
    }

    record Empty() {
    }

    record Named(String name) {
    }

    record Pair<A, B>(A first, B second) {
    }

    record Primitives(byte b, short s, char c, int i, long l, float f, double d, boolean z) {
    }

    record Holder(int[] values) {
    }

    record Range(int low, int high) {
        Range {
            if (low > high) {
                throw new IllegalArgumentException(low + " > " + high);
            }
        }

        int length() {
            return high - low;
        }
    }

    static final class Loud {
        @Override
        public boolean equals(Object o) {
            throw new IllegalStateException("compared");
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    record Ordered(Loud first, int second) {
    }

    record Custom(int value) {
        @Override
        public String toString() {
            return "custom " + value;
        }
    }

    static String pointToString() {
        return new Point(1, -2).toString();
    }

    static String pointEquals() {
        Point p = new Point(3, 4);
        return "" + p.equals(p) + p.equals(new Point(3, 4)) + p.equals(new Point(3, 5)) + p.equals(new Point(4, 4))
                + p.equals(null) + p.equals("Point[x=3, y=4]");
    }

    static int pointHashCode() {
        return new Point(3, 4).hashCode() ^ new Point(-7, 1 << 30).hashCode() * 7;
    }

    static String nested() {
        Line a = new Line(new Point(0, 0), new Point(5, 6));
        Line b = new Line(new Point(0, 0), new Point(5, 6));
        Line c = new Line(new Point(0, 0), null);
        return a + " " + a.equals(b) + " " + (a.hashCode() == b.hashCode()) + " " + a.hashCode() + " " + c + " "
                + c.equals(a) + " " + a.equals(c) + " " + c.hashCode();
    }

    static String empty() {
        Empty e = new Empty();
        return e + " " + e.hashCode() + " " + e.equals(new Empty());
    }

    static String nulls() {
        Named n = new Named(null);
        return n + " " + n.hashCode() + " " + n.equals(new Named(null)) + " " + n.equals(new Named("x"))
                + " " + new Named("x").equals(n) + " " + new Named("duke").hashCode();
    }

    static String generic() {
        Pair<String, Integer> p = new Pair<>("a", 1);
        return p + " " + p.hashCode() + " " + p.equals(new Pair<>("a", 1)) + " " + p.equals(new Pair<>("a", 1L));
    }

    static String everyPrimitive() {
        Primitives p = new Primitives((byte) -3, (short) 300, 'q', -40, 1L << 40, 1.5f, -0.1, true);
        Primitives q = new Primitives((byte) -3, (short) 300, 'q', -40, 1L << 40, 1.5f, -0.1, true);
        Primitives r = new Primitives((byte) -3, (short) 300, 'q', -40, 1L << 40, 1.5f, -0.1, false);
        return p + " " + p.hashCode() + " " + p.equals(q) + " " + p.equals(r) + " " + r.hashCode();
    }

    /** Floating-point components compare like Float.compare and Double.compare, not ==. */
    static String floatingEquality() {
        Primitives nan = new Primitives((byte) 0, (short) 0, 'a', 0, 0, Float.NaN, Double.NaN, false);
        Primitives nan2 = new Primitives((byte) 0, (short) 0, 'a', 0, 0, Float.NaN, Double.NaN, false);
        Primitives zero = new Primitives((byte) 0, (short) 0, 'a', 0, 0, 0.0f, 0.0, false);
        Primitives negativeZero = new Primitives((byte) 0, (short) 0, 'a', 0, 0, -0.0f, 0.0, false);
        Primitives negativeZeroD = new Primitives((byte) 0, (short) 0, 'a', 0, 0, 0.0f, -0.0, false);
        return "" + nan.equals(nan2) + zero.equals(negativeZero) + zero.equals(negativeZeroD) + " " + nan.hashCode()
                + " " + negativeZero.hashCode() + " " + negativeZeroD.hashCode() + " " + nan;
    }

    /** Array components compare and hash by identity. */
    static String arrayComponent() {
        int[] values = {1, 2, 3};
        Holder h = new Holder(values);
        return "" + h.equals(new Holder(values)) + h.equals(new Holder(new int[] {1, 2, 3}))
                + (h.hashCode() == values.hashCode()) + h.toString().startsWith("Holder[values=[I@")
                + new Holder(null);
    }

    /** ObjectMethods checks the last component first, so a cheap mismatch there skips the first's equals. */
    static String equalsComponentOrder() {
        Loud loud = new Loud();
        String sameFirst = "" + new Ordered(loud, 1).equals(new Ordered(loud, 1));
        String differentSecond = "" + new Ordered(new Loud(), 1).equals(new Ordered(new Loud(), 2));
        String sameSecond;
        try {
            sameSecond = "" + new Ordered(new Loud(), 1).equals(new Ordered(new Loud(), 1));
        } catch (IllegalStateException e) {
            sameSecond = e.getMessage();
        }
        return sameFirst + " " + differentSecond + " " + sameSecond;
    }

    static String compactConstructorAndMethods() {
        Range r = new Range(2, 9);
        String thrown;
        try {
            new Range(9, 2);
            thrown = "none";
        } catch (IllegalArgumentException e) {
            thrown = e.getMessage();
        }
        return r + " " + r.length() + " " + r.low() + " " + thrown;
    }

    static String overriddenToString() {
        Custom c = new Custom(5);
        return c + " " + c.hashCode() + " " + c.equals(new Custom(5));
    }

    static String localRecord() {
        record Temperature(String place, double celsius) {
        }
        return new Temperature("Lisbon", 21.5).toString();
    }

    static String asKeys() {
        Map<Point, String> map = new HashMap<>();
        map.put(new Point(1, 2), "a");
        map.put(new Point(2, 1), "b");
        map.put(new Point(1, 2), "c");
        Set<Line> lines = new HashSet<>();
        lines.add(new Line(new Point(0, 0), new Point(1, 1)));
        lines.add(new Line(new Point(0, 0), new Point(1, 1)));
        return map.size() + " " + map.get(new Point(1, 2)) + " " + lines.size();
    }
}
