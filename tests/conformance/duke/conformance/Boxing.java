package duke.conformance;

final class Boxing {

    static int autoboxRoundTrip() {
        Integer boxed = 41;
        int unboxed = boxed + 1;
        Long wide = 1L << 40;
        Character c = 'x';
        Boolean b = true;
        Byte y = (byte) -3;
        Short s = (short) 300;
        return unboxed + (int) (wide >> 40) + c + (b ? 1000 : 0) + y + s;
    }

    static int cacheIdentityAtBoundaries() {
        Integer a127 = 127;
        Integer b127 = 127;
        Integer a128 = 128;
        Integer b128 = 128;
        Integer aNeg = -128;
        Integer bNeg = -128;
        Integer aNeg129 = -129;
        Integer bNeg129 = -129;
        Long l127 = 127L;
        Long m127 = 127L;
        Character c127 = (char) 127;
        Character d127 = (char) 127;
        Character c128 = (char) 128;
        Character d128 = (char) 128;
        int bits = 0;
        bits |= a127 == b127 ? 1 : 0;
        bits |= a128 == b128 ? 2 : 0;
        bits |= aNeg == bNeg ? 4 : 0;
        bits |= aNeg129 == bNeg129 ? 8 : 0;
        bits |= l127 == m127 ? 16 : 0;
        bits |= c127 == d127 ? 32 : 0;
        bits |= c128 == d128 ? 64 : 0;
        bits |= Boolean.valueOf(true) == Boolean.TRUE ? 128 : 0;
        bits |= a128.equals(b128) ? 256 : 0;
        return bits;
    }

    static String boxedToString() {
        Object[] values = {1, -2L, 'c', true, (byte) 4, (short) -5, Integer.MIN_VALUE};
        StringBuilder sb = new StringBuilder();
        for (Object v : values) {
            sb.append(v).append(',');
        }
        return sb.toString();
    }

    static int boxedHashCodes() {
        Object[] values = {1, -2L, 'c', true, false, (byte) 4, (short) -5, 1L << 33};
        int h = 0;
        for (Object v : values) {
            h = 31 * h + v.hashCode();
        }
        return h;
    }

    // The static hashCode(value) overloads widen to int, so a negative byte or short must come back
    // sign-extended rather than zero-padded.
    static int staticHashCodes() {
        int h = 0;
        for (byte b : new byte[] {-1, (byte) 0xAA, Byte.MIN_VALUE, Byte.MAX_VALUE}) {
            h = 31 * h + Byte.hashCode(b);
        }
        for (short s : new short[] {-1, (short) 0xAAAA, Short.MIN_VALUE, Short.MAX_VALUE}) {
            h = 31 * h + Short.hashCode(s);
        }
        for (char c : new char[] {'A', '\u007f', Character.MAX_VALUE}) {
            h = 31 * h + Character.hashCode(c);
        }
        return h;
    }

    static int equalsAcrossTypes() {
        Object i = 5;
        Object l = 5L;
        Object s = (short) 5;
        int bits = 0;
        bits |= i.equals(5) ? 1 : 0;
        bits |= i.equals(l) ? 2 : 0;
        bits |= l.equals(5L) ? 4 : 0;
        bits |= s.equals(i) ? 8 : 0;
        return bits;
    }

    static int compareToThroughComparable() {
        Comparable<Integer> a = 3;
        Comparable<String> s = "abc";
        return a.compareTo(7) * 10 + Integer.signum(s.compareTo("abd"));
    }

    static long numberMethods() {
        Number n = 300;
        Number big = 1L << 35;
        return n.byteValue() + n.shortValue() + big.intValue() + big.longValue();
    }

    static int boxedInArraysAndGenerics() {
        Box<Integer> box = new Box<>(10);
        box.set(box.get() + 5);
        Object[] mixed = {box.get(), 'z', null};
        return box.get() * 100 + (mixed[0] instanceof Integer ? 1 : 0) + (mixed[1] instanceof Character ? 10 : 0);
    }

    static final class Box<T> {
        private T value;

        Box(T value) {
            this.value = value;
        }

        T get() {
            return value;
        }

        void set(T value) {
            this.value = value;
        }
    }

    static int parseBoolean() {
        return (Boolean.parseBoolean("TRUE") ? 1 : 0) + (Boolean.parseBoolean("yes") ? 10 : 0)
                + (Boolean.parseBoolean(null) ? 100 : 0) + (Boolean.parseBoolean("true") ? 1000 : 0);
    }

    // One bit per predicate per Latin-1 character, folded into a hash.
    static long latin1CharacterClasses() {
        long h = 0;
        for (char c = 0; c < 256; c++) {
            int bits = (Character.isDigit(c) ? 1 : 0) | (Character.isLetter(c) ? 2 : 0)
                    | (Character.isUpperCase(c) ? 4 : 0) | (Character.isLowerCase(c) ? 8 : 0)
                    | (Character.isWhitespace(c) ? 16 : 0) | (Character.isLetterOrDigit(c) ? 32 : 0);
            h = h * 31 + bits;
        }
        return h;
    }

    static long latin1CaseMapping() {
        long h = 0;
        for (char c = 0; c < 256; c++) {
            h = h * 31 + Character.toUpperCase(c);
            h = h * 31 + Character.toLowerCase(c);
        }
        return h;
    }

    static int digits() {
        return Character.digit('7', 10) + Character.digit('f', 16) * 10 + Character.digit('g', 16) * 1000
                + Character.digit('Z', 36) * 10000;
    }
}
