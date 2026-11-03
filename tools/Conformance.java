import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Differential test of the compiler: runs every test method in tests/conformance on the host JVM,
 * then compiles the same classes into a kernel, boots it in QEMU and checks it prints identical
 * results. A test is any non-private static method with no parameters returning a primitive or a
 * single-line String.
 *
 * <p>Run from the repository root with {@code make conformance}.
 */
public class Conformance {

    static final Path TESTS = Harness.ROOT.resolve("tests/conformance");
    static final Path OUT = Harness.ROOT.resolve("build/conformance");
    static final String PACKAGE = "duke.conformance";
    static final String DONE = "DUKE-CONFORMANCE-DONE";

    /** {@code --gc-stress N} collects every N allocations while the suite runs. */
    static int gcStress;

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--gc-stress")) {
            gcStress = Integer.parseInt(args[1]);
        }
        Harness.deleteRecursively(OUT);
        List<Path> testSources = Harness.javaFiles(TESTS);

        Path hostClasses = OUT.resolve("host");
        Harness.javac(List.of("-d", hostClasses.toString()), testSources);
        Map<String, Method> tests = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {hostClasses.toUri().toURL()})) {
            for (Path source : testSources) {
                String simpleName = source.getFileName().toString().replace(".java", "");
                Class<?> c = loader.loadClass(PACKAGE + "." + simpleName);
                Method[] methods = c.getDeclaredMethods();
                Arrays.sort(methods, Comparator.comparing(Method::getName));
                for (Method m : methods) {
                    int mods = m.getModifiers();
                    if (!Modifier.isStatic(mods) || Modifier.isPrivate(mods) || m.getParameterCount() != 0
                            || m.getReturnType() == void.class || m.isSynthetic()) {
                        continue;
                    }
                    m.setAccessible(true);
                    String name = simpleName + "." + m.getName();
                    tests.put(name, m);
                    expected.put(name, normalize(m.invoke(null)));
                }
            }
        }

        Path gen = OUT.resolve("gen");
        writeMain(gen, tests);

        Path kernelClasses = OUT.resolve("kclasses");
        Harness.compileKernel(kernelClasses, List.of(TESTS, gen));
        Path esp = Harness.buildImage(kernelClasses, PACKAGE.replace('.', '/') + "/Main.main", OUT);
        Path serial = OUT.resolve("serial.log");
        // The GC-stress run takes about 150 s on a fast CI runner and over twice that on a slow one.
        int boot = Harness.runStatus(OUT.resolve("boot.txt"), "tools/boot-test.sh", esp.toString(),
                serial.toString(), DONE, "600");

        Map<String, String> actual = parse(serial);
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String got = actual.get(e.getKey());
            if (got == null) {
                failures.add(e.getKey() + ": no result (kernel stopped before reaching it)");
            } else if (!got.equals(e.getValue())) {
                failures.add(e.getKey() + ": expected \"" + e.getValue() + "\", got \"" + got + "\"");
            }
        }

        int passed = expected.size() - failures.size();
        failures.forEach(f -> System.out.println("FAIL " + f));
        if (boot != 0) {
            System.out.println(Files.readString(OUT.resolve("boot.txt")));
        }
        String summary = "conformance" + (gcStress > 0 ? " (GC every " + gcStress + " allocations)" : "") + ": "
                + passed + "/" + expected.size() + " passed";
        System.out.println(summary);
        writeStepSummary(summary, failures);
        System.exit(failures.isEmpty() && boot == 0 ? 0 : 1);
    }

    static String normalize(Object value) {
        return switch (value) {
            case null -> "null";
            case Boolean b -> b ? "1" : "0";
            case Character c -> Long.toString(c);
            case Number n -> Long.toString(n.longValue());
            case String s -> {
                if (s.contains("\n")) {
                    throw new IllegalArgumentException("string results must be single-line: " + s);
                }
                yield s;
            }
            default -> throw new IllegalArgumentException("unsupported test result type " + value.getClass());
        };
    }

    static void writeMain(Path gen, Map<String, Method> tests) throws IOException {
        StringBuilder src = new StringBuilder();
        src.append("package ").append(PACKAGE).append(";\n\n");
        src.append("import duke.kernel.Console;\nimport duke.kernel.Kernel;\n\n");
        // Full bring-up, so the suite runs on the real memory system (paging, growable heap).
        src.append("final class Main {\n    static void main() throws Exception {\n        Kernel.init();\n");
        if (gcStress > 0) {
            src.append("        duke.rt.Heap.stress(").append(gcStress).append(");\n");
        }
        for (Map.Entry<String, Method> e : tests.entrySet()) {
            String call = e.getKey() + "()";
            Class<?> type = e.getValue().getReturnType();
            String value;
            if (type == boolean.class) {
                value = "(" + call + " ? 1L : 0L)";
            } else if (type == String.class) {
                value = "String.valueOf((Object) " + call + ")";
            } else {
                value = "(long) " + call;
            }
            src.append("        Console.print(\"").append(e.getKey()).append("=\");\n");
            src.append("        Console.print(").append(value).append(");\n");
            src.append("        Console.println(\"\");\n");
        }
        src.append("        Console.println(\"gc: \" + duke.rt.Heap.collections() + \" collections, timer: \"\n"
                + "                + duke.kernel.time.Timer.ticks() + \" interrupts taken\");\n");
        src.append("        Console.println(\"").append(DONE).append("\");\n    }\n}\n");
        Path file = gen.resolve(PACKAGE.replace('.', '/')).resolve("Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, src);
    }

    static Map<String, String> parse(Path serial) throws IOException {
        Map<String, String> results = new LinkedHashMap<>();
        Pattern line = Pattern.compile("([A-Za-z0-9_]+\\.[A-Za-z0-9_]+)=(.*)");
        for (String l : Harness.readSerial(serial).split("\n")) {
            Matcher m = line.matcher(l);
            if (m.matches()) {
                results.put(m.group(1), m.group(2));
            }
        }
        return results;
    }

    static void writeStepSummary(String summary, List<String> failures) throws IOException {
        String path = System.getenv("GITHUB_STEP_SUMMARY");
        if (path == null) {
            return;
        }
        StringBuilder md = new StringBuilder("### " + summary + "\n");
        for (String f : failures) {
            md.append("- ").append(f).append('\n');
        }
        Files.writeString(Path.of(path), md, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
}
