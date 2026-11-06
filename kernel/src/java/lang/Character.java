package java.lang;

/** Latin-1 only, matching String. */
public final class Character implements Comparable<Character> {

    public static final char MIN_VALUE = '\u0000';
    public static final char MAX_VALUE = (char) 0xFFFF;

    private static final Character[] CACHE = new Character[128];

    static {
        for (int i = 0; i < CACHE.length; i++) {
            CACHE[i] = new Character((char) i);
        }
    }

    private final char value;

    private Character(char value) {
        this.value = value;
    }

    public static Character valueOf(char c) {
        if (c < 128) {
            return CACHE[c];
        }
        return new Character(c);
    }

    public static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    public static boolean isLetter(char c) {
        return isUpperCase(c) || isLowerCase(c);
    }

    public static boolean isLetterOrDigit(char c) {
        return isLetter(c) || isDigit(c);
    }

    /** A-Z and U+00C0..U+00DE except the multiplication sign. */
    public static boolean isUpperCase(char c) {
        return c >= 'A' && c <= 'Z' || c >= 0xC0 && c <= 0xDE && c != 0xD7;
    }

    /**
     * a-z, U+00DF..U+00FF except the division sign, the micro sign, and the two ordinal indicators
     * (U+00AA, U+00BA), which Unicode lists as Other_Lowercase.
     */
    public static boolean isLowerCase(char c) {
        return c >= 'a' && c <= 'z' || c >= 0xDF && c <= 0xFF && c != 0xF7 || c == 0xAA || c == 0xB5 || c == 0xBA;
    }

    public static boolean isWhitespace(char c) {
        return c == ' ' || c >= '\t' && c <= '\r' || c >= 0x1C && c <= 0x1F;
    }

    /** The micro sign and y-diaeresis uppercase to characters outside Latin-1. */
    public static char toUpperCase(char c) {
        if (c >= 'a' && c <= 'z' || c >= 0xE0 && c <= 0xFE && c != 0xF7) {
            return (char) (c - 32);
        }
        if (c == 0xB5) {
            return (char) 0x39C;
        }
        if (c == 0xFF) {
            return (char) 0x178;
        }
        return c;
    }

    public static char toLowerCase(char c) {
        if (c >= 'A' && c <= 'Z' || c >= 0xC0 && c <= 0xDE && c != 0xD7) {
            return (char) (c + 32);
        }
        return c;
    }

    public static int digit(char c, int radix) {
        if (radix < 2 || radix > 36) {
            return -1;
        }
        int d = -1;
        if (c >= '0' && c <= '9') {
            d = c - '0';
        } else if (c >= 'a' && c <= 'z') {
            d = c - 'a' + 10;
        } else if (c >= 'A' && c <= 'Z') {
            d = c - 'A' + 10;
        }
        return d < radix ? d : -1;
    }

    public static char forDigit(int digit, int radix) {
        if (radix < 2 || radix > 36 || digit < 0 || digit >= radix) {
            return '\0';
        }
        return (char) (digit < 10 ? '0' + digit : 'a' + digit - 10);
    }

    public static int getNumericValue(char c) {
        int value = digit(c, 36);
        if (value >= 0) {
            return value;
        }
        if (c == 0xB2 || c == 0xB3 || c == 0xB9) {
            return c == 0xB2 ? 2 : c == 0xB3 ? 3 : 1;
        }
        if (c >= 0xBC && c <= 0xBE) {
            return -2;
        }
        return -1;
    }
    public static boolean isAlphabetic(int codePoint) {
        return codePoint >= 0 && codePoint <= 0xFF && isLetter((char) codePoint);
    }

    public static String toString(char c) {
        return String.valueOf(c);
    }

    public static int compare(char x, char y) {
        return x - y;
    }

    public static int hashCode(char value) {
        return value;
    }

    public char charValue() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Character c && c.value == value;
    }

    @Override
    public int hashCode() {
        return value;
    }

    @Override
    public String toString() {
        return toString(value);
    }

    @Override
    public int compareTo(Character other) {
        return compare(value, other.value);
    }
}
