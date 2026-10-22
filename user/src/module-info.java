/**
 * java.base for a Java program in ring 3: the kernel's java.lang, java.util and runtime, with the
 * few duke.kernel classes they call replaced by ones built on Linux system calls.
 */
module java.base {
    exports java.io;
    exports java.lang;
    exports java.lang.annotation;
    exports java.lang.invoke;
    exports java.util;
    exports java.util.function;
    exports duke.rt;
    exports duke.kernel;
}
