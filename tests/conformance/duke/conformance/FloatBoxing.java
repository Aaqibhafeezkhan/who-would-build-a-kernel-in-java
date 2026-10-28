package duke.conformance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Double and Float as objects: autoboxing, Number, and equals/hashCode by bits rather than ==. */
final class FloatBoxing {

    static long autoboxRoundTrip() {
        Double d = 2.5;
        Float f = 0.25f;
        double sum = d + f;
        return Double.doubleToRawLongBits(sum);
    }

    static long sumThroughGenerics() {
        List<Double> values = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            values.add(1.0 / i);
        }
        double sum = 0;
        for (Double v : values) {
            sum += v;
        }
        return Double.doubleToRawLongBits(sum);
    }

    static int equalsIsByBits() {
        int bits = 0;
        bits |= Double.valueOf(Double.NaN).equals(Double.NaN) ? 1 : 0;
        bits |= Double.valueOf(0.0).equals(-0.0) ? 2 : 0;
        bits |= Double.valueOf(1.5).equals(1.5) ? 4 : 0;
        bits |= Float.valueOf(Float.NaN).equals(Float.NaN) ? 8 : 0;
        bits |= Float.valueOf(0f).equals(-0f) ? 16 : 0;
        bits |= Double.valueOf(1.0).equals(1.0f) ? 32 : 0;
        bits |= Float.valueOf(1f).equals(1) ? 64 : 0;
        Object nanBits = Double.longBitsToDouble(0x7ff0000000000001L);
        bits |= nanBits.equals(Double.NaN) ? 128 : 0;
        return bits;
    }

    static int hashCodes() {
        Object[] values = {1.5, -0.0, 0.0, Double.NaN, Double.MAX_VALUE, 1e-300, 2.5f, -0f, Float.NaN, Float.MIN_VALUE};
        int h = 0;
        for (Object v : values) {
            h = 31 * h + v.hashCode();
        }
        return h * 31 + Double.hashCode(-1.0) + Float.hashCode(3f);
    }

    static int compareTo() {
        Double[] ds = {1.0, -0.0, 0.0, Double.NaN, Double.NEGATIVE_INFINITY};
        int h = 0;
        for (Double a : ds) {
            for (Double b : ds) {
                h = h * 3 + a.compareTo(b) + 1;
            }
        }
        Comparable<Float> f = 2f;
        return h * 3 + f.compareTo(Float.NaN) + 1;
    }

    static long numberMethods() {
        Number d = -7.9;
        Number huge = 1e30;
        Number nan = Float.NaN;
        return d.intValue() * 1000L + d.longValue() + huge.intValue() + huge.longValue() + nan.intValue()
                + d.byteValue() + Float.floatToRawIntBits(d.floatValue()) + Float.floatToRawIntBits(huge.floatValue());
    }

    static long wholeNumbersAsDoubles() {
        Number big = (1L << 62) + 1;
        Number i = -3;
        Number s = (short) 300;
        Number b = (byte) -1;
        return Double.doubleToRawLongBits(big.doubleValue()) ^ Float.floatToRawIntBits(big.floatValue())
                ^ Double.doubleToRawLongBits(i.doubleValue() + s.doubleValue() + b.doubleValue())
                ^ Float.floatToRawIntBits(i.floatValue()) * 31L;
    }

    static int asMapKeys() {
        Map<Double, Integer> map = new HashMap<>();
        map.put(Double.NaN, 1);
        map.put(0.0, 2);
        map.put(-0.0, 3);
        map.put(0.1 + 0.2, 4);
        Integer nan = map.get(Double.longBitsToDouble(0x7ff8000000000001L));
        Integer notQuite = map.get(0.3);
        return map.size() * 10000 + (nan == null ? 0 : nan) * 1000 + map.get(0.0) * 100 + map.get(-0.0) * 10
                + (notQuite == null ? 0 : notQuite);
    }

    static int typeChecks() {
        Object d = 1.0;
        Object f = 1f;
        return (d instanceof Number ? 1 : 0) + (d instanceof Comparable<?> ? 2 : 0) + (d instanceof Double ? 4 : 0)
                + (d instanceof Float ? 8 : 0) + (f instanceof Float ? 16 : 0) + (f instanceof Double ? 32 : 0);
    }

    static int unboxingNullThrows() {
        Double d = opaque(null);
        try {
            double x = d;
            return (int) x;
        } catch (NullPointerException e) {
            return -1;
        }
    }

    static Double opaque(Double d) { return d; }
}
