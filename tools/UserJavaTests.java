import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Java programs in ring 3: builds each program in tests/user with {@code dukec --user} into a
 * static Linux executable, and checks its output and exit status match HotSpot's for the same
 * source, run on Linux and on Duke.
 *
 * <p>Linux needs Docker unless this is x86-64 Linux; USER_BACKENDS=duke skips it. Run from the
 * repository root with {@code make user-test}.
 */
public class UserJavaTests {

    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path TESTS = ROOT.resolve("tests/user");
    static final Path OUT = ROOT.resolve("build/user-tests");
    static final Path DUKEC = ROOT.resolve("compiler/build/install/dukec/bin/dukec");
    static final String PACKAGE = "duke.user";
    static final String BACKENDS = System.getenv().getOrDefault("USER_BACKENDS", "linux,duke");
    /** Every program gets these, and the same again quoted for Duke's shell. */
    static final List<String> ARGUMENTS = List.of("one", "two words", "3");
    static final String SHELL_ARGUMENTS = " one \"two words\" 3";

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);
        List<Path> sources;
        try (Stream<Path> files = Files.walk(TESTS)) {
            sources = files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        List<String> names = sources.stream().map(p -> p.getFileName().toString().replaceAll("\\.java$", "")).toList();

        Path hostClasses = OUT.resolve("host");
        Harness.javac(List.of("-d", hostClasses.toString()), sources);

        // The user java.base: its own duke.kernel stand-ins first, then the kernel's java.* and runtime.
        Path classes = OUT.resolve("classes");
        List<Path> roots = List.of(ROOT.resolve("user/src"), ROOT.resolve("kernel/src"), TESTS);
        List<Path> userBase = new ArrayList<>(Harness.javaFiles(ROOT.resolve("user/src")));
        userBase.addAll(Harness.javaFiles(ROOT.resolve("kernel/src/java")));
        userBase.addAll(Harness.javaFiles(ROOT.resolve("kernel/src/duke/rt")));
        userBase.addAll(sources);
        String sourcePath = "java.base=" + String.join(java.io.File.pathSeparator, roots.stream().map(Path::toString).toList());
        Harness.javac(List.of("--system", "none", "-XDstringConcat=inline", "--module-source-path", sourcePath,
                "-d", classes.toString()), userBase);

        Map<String, byte[]> executables = new LinkedHashMap<>();
        Map<String, JsTests.Result> builds = new LinkedHashMap<>();
        for (String name : names) {
            Path exe = OUT.resolve(name);
            JsTests.Result built = JsTests.run(List.of(DUKEC.toString(), "--user", "--classes", classes.toString(),
                    "--entry", PACKAGE.replace('.', '/') + "/" + name + ".main", "--output", exe.toString(),
                    "--map", OUT.resolve(name + ".map").toString()));
            builds.put(name, built);
            if (built.exit() == 0) {
                executables.put(name, Files.readAllBytes(exe));
            }
        }
        Path dukeDir = OUT.resolve("duke");
        Files.createDirectories(dukeDir);
        Map<String, JsTests.Result> onDuke = BACKENDS.contains("duke") ? JsTests.runAllOnDuke(dukeDir, "java", executables, SHELL_ARGUMENTS) : Map.of();

        int failures = 0;
        for (String name : names) {
            List<String> java = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-cp", hostClasses.toString(), PACKAGE + "." + name));
            java.addAll(ARGUMENTS);
            JsTests.Result expected = JsTests.run(java);
            JsTests.Result built = builds.get(name);
            if (built.exit() != 0) {
                System.out.println("FAIL " + name + ": dukec failed\n" + built.output().indent(4));
                failures++;
                continue;
            }
            if (BACKENDS.contains("linux")) {
                failures += JsTests.check(name + " (linux)", "hotspot", "dukec", expected, () -> {
                    List<String> linux = new ArrayList<>(JsTests.executable(OUT.resolve(name)));
                    linux.addAll(ARGUMENTS);
                    return JsTests.run(linux);
                });
            }
            if (BACKENDS.contains("duke")) {
                failures += JsTests.check(name + " (duke)", "hotspot", "dukec", expected,
                        () -> onDuke.getOrDefault(name, new JsTests.Result("not run: an earlier program hung the kernel\n", -1)));
            }
        }
        System.out.println("user-test: " + names.size() + " programs, " + failures + " failures");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
