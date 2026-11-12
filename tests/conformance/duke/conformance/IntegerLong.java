package duke.conformance;

final class IntegerLong {

    static String integerRadix2() {
        return Integer.toString(0, 2).concat("|").concat(Integer.toString(-1, 2)).concat("|")
                .concat(Integer.toString(Integer.MIN_VALUE, 2)).concat("|")
                .concat(Integer.toString(Integer.MAX_VALUE, 2));
    }

    static String integerRadix16() {
        return Integer.toString(0, 16).concat("|").concat(Integer.toString(-255, 16)).concat("|")
                .concat(Integer.toString(Integer.MIN_VALUE, 16)).concat("|")
                .concat(Integer.toString(Integer.MAX_VALUE, 16));
    }

    static String integerRadix36() {
        return Integer.toString(0, 36).concat("|").concat(Integer.toString(-123456789, 36)).concat("|")
                .concat(Integer.toString(Integer.MIN_VALUE, 36)).concat("|")
                .concat(Integer.toString(Integer.MAX_VALUE, 36));
    }

    static String integerRadixFallback() {
        return Integer.toString(-123456789, 1).concat("|").concat(Integer.toString(123456789, 37));
    }

    static String longRadix2() {
        return Long.toString(0L, 2).concat("|").concat(Long.toString(-1L, 2)).concat("|")
                .concat(Long.toString(Long.MIN_VALUE, 2)).concat("|").concat(Long.toString(Long.MAX_VALUE, 2));
    }

    static String longRadix16() {
        return Long.toString(0L, 16).concat("|").concat(Long.toString(-65535L, 16)).concat("|")
                .concat(Long.toString(Long.MIN_VALUE, 16)).concat("|").concat(Long.toString(Long.MAX_VALUE, 16));
    }

    static String longRadix36() {
        return Long.toString(0L, 36).concat("|").concat(Long.toString(-123456789012345L, 36)).concat("|")
                .concat(Long.toString(Long.MIN_VALUE, 36)).concat("|").concat(Long.toString(Long.MAX_VALUE, 36));
    }

    static String longRadixFallback() {
        return Long.toString(-123456789012345L, 1).concat("|").concat(Long.toString(123456789012345L, 37));
    }

    static String integerOctal() {
        return Integer.toOctalString(0).concat("|").concat(Integer.toOctalString(7))
                .concat("|").concat(Integer.toOctalString(-1)).concat("|")
                .concat(Integer.toOctalString(Integer.MIN_VALUE));
    }

    static String longOctal() {
        return Long.toOctalString(0L).concat("|").concat(Long.toOctalString(7L))
                .concat("|").concat(Long.toOctalString(-1L)).concat("|")
                .concat(Long.toOctalString(Long.MIN_VALUE));
    }

    static String integerReverse() {
        return Integer.toString(Integer.reverse(0)).concat("|")
                .concat(Integer.toString(Integer.reverse(1))).concat("|")
                .concat(Integer.toString(Integer.reverse(Integer.MIN_VALUE))).concat("|")
                .concat(Integer.toString(Integer.reverse(0x01234567)));
    }

    static String longReverse() {
        return Long.toString(Long.reverse(0L)).concat("|")
                .concat(Long.toString(Long.reverse(1L))).concat("|")
                .concat(Long.toString(Long.reverse(Long.MIN_VALUE))).concat("|")
                .concat(Long.toString(Long.reverse(0x0123456789abcdefL)));
    }

    static String longReverseBytes() {
        return Long.toString(Long.reverseBytes(0L)).concat("|")
                .concat(Long.toString(Long.reverseBytes(1L))).concat("|")
                .concat(Long.toString(Long.reverseBytes(Long.MIN_VALUE))).concat("|")
                .concat(Long.toString(Long.reverseBytes(0x0123456789abcdefL)));
    }
}
