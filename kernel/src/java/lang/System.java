package java.lang;

import duke.rt.Heap;
import java.io.PrintStream;
import duke.rt.Magic;
import duke.rt.Tib;

public final class System {

    public static final PrintStream out = new PrintStream();

    private System() {
    }

    /** JDK semantics, including copying the prefix before an incompatible element of a reference array. */
    public static void arraycopy(Object src, int srcPos, Object dest, int destPos, int length) {
        if (src == null || dest == null) {
            throw new NullPointerException();
        }
        long srcTib = Tib.of(src);
        long destTib = Tib.of(dest);
        if (!Tib.isArray(srcTib)) {
            throw new ArrayStoreException("arraycopy: source type " + Tib.name(srcTib) + " is not an array");
        }
        if (!Tib.isArray(destTib)) {
            throw new ArrayStoreException("arraycopy: destination type " + Tib.name(destTib) + " is not an array");
        }
        boolean srcRefs = (Tib.flags(srcTib) & Tib.FLAG_REFERENCE_ARRAY) != 0;
        boolean destRefs = (Tib.flags(destTib) & Tib.FLAG_REFERENCE_ARRAY) != 0;
        if (srcRefs != destRefs || !srcRefs && srcTib != destTib) {
            throw new ArrayStoreException("arraycopy: type mismatch: can not copy " + typeName(srcTib)
                    + " into " + typeName(destTib));
        }
        checkRange("source", srcPos, length, Tib.arrayLength(src), srcTib);
        checkRange("destination", destPos, length, Tib.arrayLength(dest), destTib);
        int width = Tib.size(srcTib);
        long from = Magic.addressOf(src) + Tib.ARRAY_DATA + (long) srcPos * width;
        long to = Magic.addressOf(dest) + Tib.ARRAY_DATA + (long) destPos * width;
        if (!srcRefs || Tib.isAssignable(srcTib, destTib)) {
            Magic.copyMemory(to, from, (long) length * width);
            return;
        }
        long element = Tib.element(destTib);
        for (int i = 0; i < length; i++) {
            long ref = Magic.peekLong(from + 8L * i);
            if (ref != 0 && !Tib.isAssignable(Magic.peekLong(ref), element)) {
                throw new ArrayStoreException("arraycopy: element type mismatch: can not cast one of the elements of "
                        + typeName(srcTib) + " to the type of the destination array, " + Tib.name(element));
            }
            Magic.pokeLong(to + 8L * i, ref);
        }
    }

    private static void checkRange(String which, int pos, int length, int arrayLength, long tib) {
        if (length < 0) {
            throw new ArrayIndexOutOfBoundsException("arraycopy: length " + length + " is negative");
        }
        if (pos < 0) {
            throw new ArrayIndexOutOfBoundsException("arraycopy: " + which + " index " + pos + " out of bounds for "
                    + typeName(tib, arrayLength));
        }
        if (pos > arrayLength - length) {
            throw new ArrayIndexOutOfBoundsException("arraycopy: last " + which + " index " + (pos + length)
                    + " out of bounds for " + typeName(tib, arrayLength));
        }
    }

    /** HotSpot's spelling in arraycopy messages: int[], java.lang.String[]. */
    private static String typeName(long tib) {
        long element = Tib.element(tib);
        if (element != 0) {
            return (Tib.isArray(element) ? typeName(element) : Tib.name(element)) + "[]";
        }
        return switch (Tib.name(tib)) {
            case "[Z" -> "boolean[]";
            case "[B" -> "byte[]";
            case "[C" -> "char[]";
            case "[S" -> "short[]";
            case "[I" -> "int[]";
            case "[J" -> "long[]";
            default -> Tib.name(tib);
        };
    }

    /** With the length in the brackets, as HotSpot writes it for index errors: int[5]. */
    private static String typeName(long tib, int length) {
        String name = typeName(tib);
        return name.substring(0, name.length() - 1) + length + "]";
    }

    public static void gc() {
        Heap.collect();
    }

    public static int identityHashCode(Object object) {
        return object == null ? 0 : Heap.identityHash(object);
    }
}
