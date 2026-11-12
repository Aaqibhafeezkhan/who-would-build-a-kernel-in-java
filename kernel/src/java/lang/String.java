package java.lang;

/**
 * Latin-1 only for now. Literals are laid out by the compiler, so {@code value} being the only
 * reference field is part of its contract with this class.
 */
public final class String implements Comparable<String> {

    private final byte[] value;

    String(byte[] value) {
        this.value = value;
    }

    /** For StringBuilder: copies this string's bytes into {@code destination} at {@code offset}. */
    void copyBytes(byte[] destination, int offset) {
        System.arraycopy(value, 0, destination, offset, value.length);
    }

    public int length() {
        return value.length;
    }

    public boolean isEmpty() {
        return value.length == 0;
    }

    public char charAt(int index) {
        if (index < 0 || index >= value.length) {
            throw new StringIndexOutOfBoundsException("Index " + index + " out of bounds for length " + value.length);
        }
        return (char) (value[index] & 0xFF);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof String s) || s.value.length != value.length) {
            return false;
        }
        for (int i = 0; i < value.length; i++) {
            if (value[i] != s.value[i]) {
                return false;
            }
        }
        return true;
    }

    /** Same algorithm as the JDK, so hashes agree with HotSpot for Latin-1 strings. */
    @Override
    public int hashCode() {
        int h = 0;
        for (byte b : value) {
            h = 31 * h + (b & 0xFF);
        }
        return h;
    }

    @Override
    public String toString() {
        return this;
    }

    @Override
    public int compareTo(String other) {
        int n = Math.min(value.length, other.value.length);
        for (int i = 0; i < n; i++) {
            int diff = (value[i] & 0xFF) - (other.value[i] & 0xFF);
            if (diff != 0) {
                return diff;
            }
        }
        return value.length - other.value.length;
    }

    public int indexOf(int ch) {
        return indexOf(ch, 0);
    }

    public int indexOf(int ch, int fromIndex) {
        for (int i = Math.max(fromIndex, 0); i < value.length; i++) {
            if ((value[i] & 0xFF) == ch) {
                return i;
            }
        }
        return -1;
    }

    public int lastIndexOf(int ch) {
        for (int i = value.length - 1; i >= 0; i--) {
            if ((value[i] & 0xFF) == ch) {
                return i;
            }
        }
        return -1;
    }

    public int indexOf(String other) {
        return indexOf(other, 0);
    }

    public int indexOf(String other, int fromIndex) {
        int start = Math.max(fromIndex, 0);
        int last = value.length - other.value.length;
        if (other.value.length == 0) {
            return Math.min(start, value.length);
        }
        if (start > last) {
            return -1;
        }
        for (int i = start; i <= last; i++) {
            if (regionMatches(i, other)) {
                return i;
            }
        }
        return -1;
    }

    public int lastIndexOf(String other) {
        int start = value.length - other.value.length;
        if (other.value.length == 0) {
            return value.length;
        }
        for (int i = start; i >= 0; i--) {
            if (regionMatches(i, other)) {
                return i;
            }
        }
        return -1;
    }

    public boolean contains(String sequence) {
        return indexOf(sequence) >= 0;
    }

    public boolean startsWith(String prefix) {
        return startsWith(prefix, 0);
    }

    public boolean startsWith(String prefix, int toffset) {
        return regionMatches(toffset, prefix);
    }

    public boolean endsWith(String suffix) {
        return regionMatches(value.length - suffix.value.length, suffix);
    }

    private boolean regionMatches(int offset, String other) {
        if (offset < 0 || offset > value.length - other.value.length) {
            return false;
        }
        for (int i = 0; i < other.value.length; i++) {
            if (value[offset + i] != other.value[i]) {
                return false;
            }
        }
        return true;
    }

    /** Without leading and trailing whitespace as Character.isWhitespace defines it. */
    public String strip() {
        int start = 0;
        int end = value.length;
        while (start < end && Character.isWhitespace((char) (value[start] & 0xFF))) {
            start++;
        }
        while (end > start && Character.isWhitespace((char) (value[end - 1] & 0xFF))) {
            end--;
        }
        return substring(start, end);
    }

    /** Without leading and trailing characters up to and including space, which strip() keeps. */
    public String trim() {
        int start = 0;
        int end = value.length;
        while (start < end && (value[start] & 0xFF) <= ' ') {
            start++;
        }
        while (end > start && (value[end - 1] & 0xFF) <= ' ') {
            end--;
        }
        return substring(start, end);
    }

    public String substring(int beginIndex) {
        return substring(beginIndex, value.length);
    }

    public String substring(int beginIndex, int endIndex) {
        if (beginIndex < 0 || endIndex > value.length || beginIndex > endIndex) {
            throw new StringIndexOutOfBoundsException(
                    "Range [" + beginIndex + ", " + endIndex + ") out of bounds for length " + value.length);
        }
        if (beginIndex == 0 && endIndex == value.length) {
            return this;
        }
        byte[] copy = new byte[endIndex - beginIndex];
        System.arraycopy(value, beginIndex, copy, 0, copy.length);
        return new String(copy);
    }

    public String concat(String other) {
        if (other.value.length == 0) {
            return this;
        }
        byte[] joined = new byte[value.length + other.value.length];
        System.arraycopy(value, 0, joined, 0, value.length);
        System.arraycopy(other.value, 0, joined, value.length, other.value.length);
        return new String(joined);
    }

    public String toUpperCase() {
        byte[] upper = new byte[value.length];
        for (int i = 0; i < value.length; i++) {
            char c = (char) (value[i] & 0xFF);
            char mapped = Character.toUpperCase(c);
            // The JDK maps these two characters outside Latin-1, which this String cannot store.
            upper[i] = (byte) (mapped <= 0xFF ? mapped : c);
        }
        return new String(upper);
    }

    public String toLowerCase() {
        byte[] lower = new byte[value.length];
        for (int i = 0; i < value.length; i++) {
            lower[i] = (byte) Character.toLowerCase((char) (value[i] & 0xFF));
        }
        return new String(lower);
    }

    public String repeat(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count is negative: " + count);
        }
        if (count == 0) {
            return "";
        }
        if (count == 1 || value.length == 0) {
            return this;
        }
        if (count > Integer.MAX_VALUE / value.length) {
            throw new OutOfMemoryError("Required length exceeds implementation limit");
        }
        byte[] repeated = new byte[value.length * count];
        for (int i = 0; i < repeated.length; i++) {
            repeated[i] = value[i % value.length];
        }
        return new String(repeated);
    }

    public String replace(char oldChar, char newChar) {
        byte[] replaced = new byte[value.length];
        boolean changed = false;
        for (int i = 0; i < value.length; i++) {
            char c = (char) (value[i] & 0xFF);
            if (c == oldChar) {
                if (newChar > 0xFF) {
                    throw new IllegalArgumentException(
                            "non-Latin-1 char " + (int) newChar + " (Strings are Latin-1 only for now)");
                }
                replaced[i] = (byte) newChar;
                changed |= oldChar != newChar;
            } else {
                replaced[i] = value[i];
            }
        }
        return changed ? new String(replaced) : this;
    }

    public boolean isBlank() {
        for (int i = 0; i < value.length; i++) {
            if (!Character.isWhitespace((char) (value[i] & 0xFF))) {
                return false;
            }
        }
        return true;
    }

    /** Same upper-then-lower comparison as the JDK, so Latin-1 edge cases agree with HotSpot. */
    public boolean equalsIgnoreCase(String other) {
        if (other == null || other.value.length != value.length) {
            return false;
        }
        for (int i = 0; i < value.length; i++) {
            char a = (char) (value[i] & 0xFF);
            char b = (char) (other.value[i] & 0xFF);
            if (a == b) {
                continue;
            }
            char upperA = Character.toUpperCase(a);
            char upperB = Character.toUpperCase(b);
            if (upperA != upperB && Character.toLowerCase(upperA) != Character.toLowerCase(upperB)) {
                return false;
            }
        }
        return true;
    }

    public String[] split(String separator) {
        return split(separator, 0);
    }

    /**
     * The JDK takes a regex; there is no regex engine here, so only literal separators work and
     * anything else throws rather than splitting wrong.
     */
    public String[] split(String separator, int limit) {
        if (separator.isEmpty()) {
            throw new UnsupportedOperationException("Empty separator");
        }
        for (int i = 0; i < separator.length(); i++) {
            if (".$|()[{^?*+\\".indexOf(separator.charAt(i)) >= 0) {
                throw new UnsupportedOperationException("Regex separator: " + separator);
            }
        }
        int pieces = 1;
        for (int from = 0; limit <= 0 || pieces < limit; ) {
            int at = indexOf(separator, from);
            if (at < 0) {
                break;
            }
            pieces++;
            from = at + separator.value.length;
        }
        String[] parts = new String[pieces];
        int start = 0;
        for (int p = 0; p < pieces - 1; p++) {
            int at = indexOf(separator, start);
            parts[p] = substring(start, at);
            start = at + separator.value.length;
        }
        parts[pieces - 1] = substring(start);
        if (limit == 0 && pieces > 1) {
            int size = pieces;
            while (size > 0 && parts[size - 1].isEmpty()) {
                size--;
            }
            if (size < pieces) {
                String[] trimmed = new String[size];
                for (int i = 0; i < size; i++) {
                    trimmed[i] = parts[i];
                }
                return trimmed;
            }
        }
        return parts;
    }

    public static String valueOf(Object object) {
        return object == null ? "null" : object.toString();
    }

    public static String valueOf(int i) {
        return Integer.toString(i);
    }

    public static String valueOf(long l) {
        return Long.toString(l);
    }

    public static String valueOf(float f) {
        return Float.toString(f);
    }

    public static String valueOf(double d) {
        return Double.toString(d);
    }

    public static String valueOf(boolean b) {
        return b ? "true" : "false";
    }

    public static String valueOf(char c) {
        return new String(new byte[] {(byte) c});
    }
}
