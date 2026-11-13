package java.util;

import java.util.function.Supplier;

/** javac calls requireNonNull for bound method references, so this exists before the rest of java.util. */
public final class Objects {

    private Objects() {
    }

    public static <T> T requireNonNull(T object) {
        if (object == null) {
            throw new NullPointerException();
        }
        return object;
    }

    public static <T> T requireNonNull(T object, String message) {
        if (object == null) {
            throw new NullPointerException(message);
        }
        return object;
    }

    /** The JDK's bounds check and message, used by List implementations. */
    public static int checkIndex(int index, int length) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + length);
        }
        return index;
    }

    public static boolean equals(Object a, Object b) {
        return a == b || a != null && a.equals(b);
    }

    public static int hashCode(Object o) {
        return o == null ? 0 : o.hashCode();
    }

    public static int hash(Object... values) {
        return Arrays.hashCode(values);
    }

    public static <T> T requireNonNullElse(T object, T defaultObject) {
        return object != null ? object : requireNonNull(defaultObject, "defaultObj");
    }

    public static <T> T requireNonNullElseGet(T object, Supplier<? extends T> supplier) {
        return object != null ? object : requireNonNull(requireNonNull(supplier, "supplier").get(), "supplier.get()");
    }

    public static boolean isNull(Object o) {
        return o == null;
    }

    public static boolean nonNull(Object o) {
        return o != null;
    }

    public static String toString(Object o) {
        return String.valueOf(o);
    }

    public static String toString(Object o, String nullDefault) {
        return o == null ? nullDefault : o.toString();
    }
}
