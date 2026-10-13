import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Differential test of jsc: runs every program in tests/js through both back ends and checks the
 * output and exit status match what node prints for the same source.
 *
 * <ul>
 *   <li>{@code x86}: the program as a standalone x86-64 Linux executable. On any other host it runs
 *       in an amd64 Docker container.
 *   <li>{@code java}: the program translated to Java, as it is for the kernel, compiled with the
 *       kernel's JavaScript runtime and run on this JVM.
 * </ul>
 *
 * <p>Run from the repository root with {@code make js-test}, or name tests to run only those.
 */
public class JsTests {

    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path TESTS = ROOT.resolve("tests/js");
    static final Path OUT = ROOT.resolve("build/js-tests");
    static final Path JSC = ROOT.resolve("compiler/build/install/dukec/bin/jsc");

    /** Which back ends to run, from JS_BACKENDS (default both): handy without Docker, which x86 needs off Linux. */
    static final String BACKENDS = System.getenv().getOrDefault("JS_BACKENDS", "x86,java,duke");

    record Result(String output, int exit) {}

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);
        List<Path> programs;
        try (Stream<Path> files = Files.list(TESTS)) {
            programs = files.filter(p -> p.toString().endsWith(".js")).sorted().toList();
        }
        List<String> wanted = List.of(args);
        Map<String, Result> onDuke = BACKENDS.contains("duke") ? runOnDuke(programs, wanted) : Map.of();
        int failures = 0;
        int ran = 0;
        for (Path program : programs) {
            String name = program.getFileName().toString().replaceAll("\\.js$", "");
            if (!wanted.isEmpty() && !wanted.contains(name)) {
                continue;
            }
            ran++;
            Result expected = run(List.of("node", program.toString()));
            // A program whose first line is "// backends: java" uses features only the Java back end has.
            boolean javaOnly = Files.readAllLines(program).get(0).equals("// backends: java");
            if (BACKENDS.contains("x86") && !javaOnly) {
                failures += check(name + " (x86)", expected, () -> runX86(program, name));
            }
            if (BACKENDS.contains("duke") && !javaOnly) {
                failures += check(name + " (duke)", expected, () -> onDuke.getOrDefault(name, new Result("not run: an earlier program hung the kernel\n", -1)));
            }
            if (BACKENDS.contains("java")) {
                failures += check(name + " (java)", expected, () -> runJava(program, name));
            }
        }
        if (ran == 0) {
            System.out.println("no tests matched");
            System.exit(1);
        }
        System.out.println(failures == 0 ? "all " + ran + " programs passed (" + BACKENDS + ")" : failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    interface Run {
        Result run() throws Exception;
    }

    /** Compares one run with node's, printing the verdict; returns the number of failures (0 or 1). */
    static int check(String label, Result expected, Run run) throws Exception {
        Result actual = run.run();
        if (expected.output().equals(actual.output()) && expected.exit() == actual.exit()) {
            System.out.println("PASS " + label);
            return 0;
        }
        System.out.println("FAIL " + label + " (exit " + actual.exit() + ", node " + expected.exit() + ")");
        System.out.println(firstDifference(expected.output(), actual.output()));
        return 1;
    }

    static Result runX86(Path program, String name) throws Exception {
        Path exe = OUT.resolve(name);
        Result compiled = run(List.of(JSC.toString(), program.toString(), "--output", exe.toString()));
        return compiled.exit() != 0 ? compiled : run(executable(exe));
    }

    /** The module class jsc generates for {@code name.js}: words split on punctuation, each capitalized. */
    static String moduleClass(String name) {
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (char c : name.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            } else {
                upper = true;
            }
        }
        return sb.toString();
    }

    static final String HOST_MAIN = """
            import duke.js.rt.Globals;
            import duke.js.rt.JsError;

            public class JsHostMain {
                public static void main(String[] args) throws Exception {
                    Globals.setSink(s -> System.out.print(s));
                    try {
                        Class.forName("duke.js.gen." + args[0]).getMethod("run").invoke(null);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        System.out.println(e.getCause());
                        System.exit(1);
                    }
                }
            }
            """;

    /**
     * The x86 executables, all on one FAT disk, each run by the shell's {@code run} in one boot of
     * the kernel. What the shell prints between the command and its next prompt is the program's
     * output, less the "exit status" line it adds for a nonzero exit.
     */
    static Map<String, Result> runOnDuke(List<Path> programs, List<String> wanted) throws Exception {
        Path dir = OUT.resolve("duke");
        Files.createDirectories(dir);
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, Result> results = new LinkedHashMap<>();
        for (Path program : programs) {
            String name = program.getFileName().toString().replaceAll("\\.js$", "");
            if (!wanted.isEmpty() && !wanted.contains(name)) {
                continue;
            }
            Path exe = dir.resolve(name);
            Result compiled = run(List.of(JSC.toString(), program.toString(), "--output", exe.toString()));
            if (compiled.exit() != 0) {
                results.put(name, compiled);
            } else {
                files.put("js/" + name, Files.readAllBytes(exe));
            }
        }
        Path image = dir.resolve("js.img");
        FatImage.write(image, files, List.of());
        Process qemu = new ProcessBuilder("tools/qemu.sh", ShellTest.ESP.toString(), "-serial", "stdio",
                "-drive", "if=none,id=js,format=raw,snapshot=on,file=" + image, "-device", "virtio-blk-pci,drive=js")
                .directory(ROOT.toFile())
                .redirectErrorStream(true)
                .start();
        Thread reader = new Thread(() -> ShellTest.pump(qemu.getInputStream()));
        reader.setDaemon(true);
        reader.start();
        try {
            if (!ShellTest.await("duke> ", 0)) {
                throw new IllegalStateException("the kernel never reached its prompt:\n"
                        + ShellTest.clean(ShellTest.output.toString()).indent(4));
            }
            for (String name : files.keySet()) {
                String path = "/" + name;
                int from = ShellTest.output.length();
                ShellTest.type(qemu.getOutputStream(), "run " + path + "\n");
                if (!awaitPrompt(from)) {
                    results.put(name.substring(3), new Result("timed out:\n" + ShellTest.clean(ShellTest.output.substring(from)), -1));
                    break;
                }
                // The shell echoes each key with a cursor redraw, so skip the whole command line.
                String shown = ShellTest.clean(ShellTest.output.substring(from));
                int start = shown.indexOf('\n') + 1;
                String out = shown.substring(start, shown.indexOf("duke> ", start));
                int exit = 0;
                String status = path + ": exit status ";
                int at = out.lastIndexOf(status);
                if (at >= 0 && out.endsWith("\n") && out.indexOf('\n', at) == out.length() - 1) {
                    exit = Integer.parseInt(out.substring(at + status.length(), out.length() - 1));
                    out = out.substring(0, at);
                }
                results.put(name.substring(3), new Result(out, exit));
            }
        } finally {
            qemu.destroy();
            qemu.waitFor(10, TimeUnit.SECONDS);
            Files.writeString(dir.resolve("serial.log"), ShellTest.output.toString());
        }
        return results;
    }

    /** Waits for the prompt the shell prints once the command typed after {@code from} finishes. */
    static boolean awaitPrompt(int from) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ShellTest.TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            String shown = ShellTest.clean(ShellTest.output.substring(from));
            int start = shown.indexOf('\n');
            if (start >= 0 && shown.indexOf("duke> ", start) >= 0) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    static Result runJava(Path program, String name) throws Exception {
        Path dir = OUT.resolve(name + "-java");
        Path generated = dir.resolve("src");
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        Result translated = run(List.of(JSC.toString(), "--java", generated.toString(), program.toString()));
        if (translated.exit() != 0) {
            return translated;
        }
        Path host = dir.resolve("JsHostMain.java");
        Files.writeString(host, HOST_MAIN);
        List<String> javac = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/javac").toString(),
                "-nowarn", "-d", classes.toString(), host.toString(),
                generated.resolve("duke/js/gen/" + moduleClass(name) + ".java").toString()));
        try (Stream<Path> runtime = Files.list(ROOT.resolve("kernel/src/duke/js/rt"))) {
            runtime.map(Path::toString).forEach(javac::add);
        }
        Result compiled = run(javac);
        if (compiled.exit() != 0) {
            return compiled;
        }
        return run(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-Xss64m", "-cp",
                classes.toString(), "JsHostMain", moduleClass(name)));
    }

    static List<String> executable(Path exe) {
        boolean native64 = System.getProperty("os.name").equals("Linux")
                && System.getProperty("os.arch").matches("amd64|x86_64");
        if (native64) {
            return List.of(exe.toString());
        }
        return List.of("docker", "run", "-q", "--rm", "--platform", "linux/amd64", "-v", OUT + ":/t", "alpine",
                "/t/" + exe.getFileName());
    }

    static Result run(List<String> command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] bytes = p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new Result("timed out", -1);
        }
        return new Result(new String(bytes), p.exitValue());
    }

    static String firstDifference(String expected, String actual) {
        String[] e = expected.split("\n", -1);
        String[] a = actual.split("\n", -1);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.max(e.length, a.length); i++) {
            String want = i < e.length ? e[i] : "<missing>";
            String got = i < a.length ? a[i] : "<missing>";
            if (!want.equals(got)) {
                lines.add("  line " + (i + 1) + "\n    node: " + want + "\n    jsc:  " + got);
                if (lines.size() == 3) {
                    break;
                }
            }
        }
        return String.join("\n", lines);
    }
}
