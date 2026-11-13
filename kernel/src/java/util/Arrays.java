package java.util;

import duke.rt.Heap;

/**
 * Sorting results are what the JDK gives: primitive sorts are deterministic regardless of
 * algorithm, and object sorts are stable (merge sort here, TimSort there).
 */
public final class Arrays {

    private Arrays() {
    }

    public static void fill(int[] a, int value) {
        for (int i = 0; i < a.length; i++) {
            a[i] = value;
        }
    }

    public static void fill(int[] a, int fromIndex, int toIndex, int value) {
        rangeCheck(fromIndex, toIndex, a.length);
        for (int i = fromIndex; i < toIndex; i++) {
            a[i] = value;
        }
    }

    public static void fill(long[] a, long value) {
        for (int i = 0; i < a.length; i++) {
            a[i] = value;
        }
    }

    public static void fill(long[] a, int fromIndex, int toIndex, long value) {
        rangeCheck(fromIndex, toIndex, a.length);
        for (int i = fromIndex; i < toIndex; i++) {
            a[i] = value;
        }
    }

    private static void rangeCheck(int fromIndex, int toIndex, int length) {
        if (fromIndex > toIndex) {
            throw new IllegalArgumentException("fromIndex(" + fromIndex + ") > toIndex(" + toIndex + ")");
        }
        if (fromIndex < 0) {
            throw new ArrayIndexOutOfBoundsException("Array index out of range: " + fromIndex);
        }
        if (toIndex > length) {
            throw new ArrayIndexOutOfBoundsException("Array index out of range: " + toIndex);
        }
    }

    public static void fill(byte[] a, byte value) {
        for (int i = 0; i < a.length; i++) {
            a[i] = value;
        }
    }

    public static void fill(char[] a, char value) {
        for (int i = 0; i < a.length; i++) {
            a[i] = value;
        }
    }

    public static void fill(boolean[] a, boolean value) {
        for (int i = 0; i < a.length; i++) {
            a[i] = value;
        }
    }

    public static void fill(Object[] a, Object value) {
        for (int i = 0; i < a.length; i++) {
            a[i] = value;
        }
    }

    public static int[] copyOf(int[] original, int newLength) {
        int[] copy = new int[newLength];
        System.arraycopy(original, 0, copy, 0, Math.min(original.length, newLength));
        return copy;
    }

    public static long[] copyOf(long[] original, int newLength) {
        long[] copy = new long[newLength];
        System.arraycopy(original, 0, copy, 0, Math.min(original.length, newLength));
        return copy;
    }

    public static byte[] copyOf(byte[] original, int newLength) {
        byte[] copy = new byte[newLength];
        System.arraycopy(original, 0, copy, 0, Math.min(original.length, newLength));
        return copy;
    }

    public static char[] copyOf(char[] original, int newLength) {
        char[] copy = new char[newLength];
        System.arraycopy(original, 0, copy, 0, Math.min(original.length, newLength));
        return copy;
    }

    /** Same runtime array type as {@code original}, like the JDK's. */
    @SuppressWarnings("unchecked")
    public static <T> T[] copyOf(T[] original, int newLength) {
        T[] copy = (T[]) Heap.allocateLike(original, newLength);
        System.arraycopy(original, 0, copy, 0, Math.min(original.length, newLength));
        return copy;
    }

    public static int[] copyOfRange(int[] original, int from, int to) {
        if (from > to) {
            throw new IllegalArgumentException(from + " > " + to);
        }
        int[] copy = new int[to - from];
        System.arraycopy(original, from, copy, 0, Math.min(original.length - from, to - from));
        return copy;
    }

    public static boolean equals(int[] a, int[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    public static boolean equals(long[] a, long[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    public static boolean equals(byte[] a, byte[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    public static boolean equals(Object[] a, Object[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!Objects.equals(a[i], b[i])) {
                return false;
            }
        }
        return true;
    }

    public static int hashCode(int[] a) {
        if (a == null) {
            return 0;
        }
        int h = 1;
        for (int v : a) {
            h = 31 * h + v;
        }
        return h;
    }

    public static int hashCode(long[] a) {
        if (a == null) {
            return 0;
        }
        int h = 1;
        for (long v : a) {
            h = 31 * h + Long.hashCode(v);
        }
        return h;
    }

    public static int hashCode(Object[] a) {
        if (a == null) {
            return 0;
        }
        int h = 1;
        for (Object o : a) {
            h = 31 * h + Objects.hashCode(o);
        }
        return h;
    }

    public static String toString(int[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(a[i]);
        }
        return sb.append(']').toString();
    }

    public static String toString(long[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(a[i]);
        }
        return sb.append(']').toString();
    }

    public static String toString(byte[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(a[i]);
        }
        return sb.append(']').toString();
    }

    public static String toString(char[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(a[i]);
        }
        return sb.append(']').toString();
    }

    public static String toString(boolean[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(a[i]);
        }
        return sb.append(']').toString();
    }

    public static String toString(Object[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(String.valueOf(a[i]));
        }
        return sb.append(']').toString();
    }

    public static void sort(int[] a) {
        int[] scratch = new int[a.length];
        mergeSort(a, scratch, 0, a.length);
    }

    public static void sort(long[] a) {
        long[] scratch = new long[a.length];
        mergeSort(a, scratch, 0, a.length);
    }

    public static void sort(char[] a) {
        int[] wide = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            wide[i] = a[i];
        }
        sort(wide);
        for (int i = 0; i < a.length; i++) {
            a[i] = (char) wide[i];
        }
    }

    public static void sort(byte[] a) {
        int[] wide = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            wide[i] = a[i];
        }
        sort(wide);
        for (int i = 0; i < a.length; i++) {
            a[i] = (byte) wide[i];
        }
    }

    public static void sort(short[] a) {
        int[] wide = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            wide[i] = a[i];
        }
        sort(wide);
        for (int i = 0; i < a.length; i++) {
            a[i] = (short) wide[i];
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void sort(Object[] a) {
        sort(a, (x, y) -> ((Comparable) x).compareTo(y));
    }

    public static <T> void sort(T[] a, Comparator<? super T> c) {
        if (c == null) {
            sort(a);
            return;
        }
        Object[] scratch = new Object[a.length];
        mergeSort(a, scratch, 0, a.length, c);
    }

    public static int binarySearch(int[] a, int key) {
        int low = 0;
        int high = a.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (a[mid] < key) {
                low = mid + 1;
            } else if (a[mid] > key) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return -(low + 1);
    }

    @SafeVarargs
    public static <T> List<T> asList(T... a) {
        return new FixedSizeList<>(a);
    }

    private static void mergeSort(int[] a, int[] scratch, int from, int to) {
        if (to - from < 2) {
            return;
        }
        int mid = (from + to) >>> 1;
        mergeSort(a, scratch, from, mid);
        mergeSort(a, scratch, mid, to);
        int i = from;
        int j = mid;
        int k = from;
        while (i < mid && j < to) {
            scratch[k++] = a[i] <= a[j] ? a[i++] : a[j++];
        }
        while (i < mid) {
            scratch[k++] = a[i++];
        }
        while (j < to) {
            scratch[k++] = a[j++];
        }
        System.arraycopy(scratch, from, a, from, to - from);
    }

    private static void mergeSort(long[] a, long[] scratch, int from, int to) {
        if (to - from < 2) {
            return;
        }
        int mid = (from + to) >>> 1;
        mergeSort(a, scratch, from, mid);
        mergeSort(a, scratch, mid, to);
        int i = from;
        int j = mid;
        int k = from;
        while (i < mid && j < to) {
            scratch[k++] = a[i] <= a[j] ? a[i++] : a[j++];
        }
        while (i < mid) {
            scratch[k++] = a[i++];
        }
        while (j < to) {
            scratch[k++] = a[j++];
        }
        System.arraycopy(scratch, from, a, from, to - from);
    }

    /** Stable: on ties the left run's element goes first. */
    @SuppressWarnings("unchecked")
    private static <T> void mergeSort(T[] a, Object[] scratch, int from, int to, Comparator<? super T> c) {
        if (to - from < 2) {
            return;
        }
        int mid = (from + to) >>> 1;
        mergeSort(a, scratch, from, mid, c);
        mergeSort(a, scratch, mid, to, c);
        int i = from;
        int j = mid;
        int k = from;
        while (i < mid && j < to) {
            scratch[k++] = c.compare(a[j], a[i]) < 0 ? a[j++] : a[i++];
        }
        while (i < mid) {
            scratch[k++] = a[i++];
        }
        while (j < to) {
            scratch[k++] = a[j++];
        }
        for (int n = from; n < to; n++) {
            a[n] = (T) scratch[n];
        }
    }

    /** Arrays.asList: writes go through to the array, size changes are unsupported. */
    private static final class FixedSizeList<E> extends AbstractList<E> {
        private final E[] a;

        FixedSizeList(E[] a) {
            this.a = Objects.requireNonNull(a);
        }

        @Override
        public int size() {
            return a.length;
        }

        @Override
        public E get(int index) {
            return a[index];
        }

        @Override
        public E set(int index, E element) {
            E old = a[index];
            a[index] = element;
            return old;
        }

        @Override
        public Object[] toArray() {
            Object[] copy = new Object[a.length];
            System.arraycopy(a, 0, copy, 0, a.length);
            return copy;
        }
    }
}
