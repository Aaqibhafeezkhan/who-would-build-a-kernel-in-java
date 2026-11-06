package java.lang.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.TypeDescriptor;

/** Only so javac can compile records; dukec generates their equals, hashCode and toString at build time (#64). */
public final class ObjectMethods {

    private ObjectMethods() {
    }

    public static Object bootstrap(MethodHandles.Lookup lookup, String methodName, TypeDescriptor type,
            Class<?> recordClass, String names, MethodHandle... getters) {
        return null;
    }
}
