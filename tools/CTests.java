import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * C programs in ring 3: builds each program in tests/c static against musl, and checks its
 * output and exit status on Duke match Linux's. Needs musl-gcc on x86-64 Linux, or Docker
 * anywhere. Run from the repository root with {@code make c-test}.
 */
public class CTests {

    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path TESTS = ROOT.resolve("tests/c");
    static final Path OUT = ROOT.resolve("build/c-tests");
    // Pinned, so a new gcc or musl shows up as a change here rather than as a failure out of nowhere.
    static final String ALPINE = "alpine:3.22";
    static final List<String> ARGUMENTS = List.of("one", "two words", "3");
    static final String SHELL_ARGUMENTS = " one \"two words\" 3";

    /** With musl-gcc where it's installed (the dev container has it), otherwise in Alpine. */
    static JsTests.Result build() throws Exception {
        String flags = "-static -O2 -Wall -Werror -s";
        boolean muslGcc = Stream.of(System.getenv("PATH").split(java.io.File.pathSeparator))
                .anyMatch(dir -> Files.isExecutable(Path.of(dir, "musl-gcc")));
        if (muslGcc) {
            return JsTests.run(List.of("sh", "-c", "for f in " + TESTS + "/*.c; do musl-gcc " + flags + " -o " + OUT
                    + "/$(basename $f .c) $f || exit 1; done"));
        }
        return JsTests.run(List.of("docker", "run", "--rm", "--platform", "linux/amd64",
                "-v", TESTS + ":/src:ro", "-v", OUT + ":/out", ALPINE, "sh", "-c",
                "apk add -q gcc musl-dev && for f in /src/*.c; do gcc " + flags + " -o /out/$(basename $f .c) $f || exit 1; done"));
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);
        List<String> names;
        try (Stream<Path> files = Files.list(TESTS)) {
            names = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".c"))
                    .map(n -> n.substring(0, n.length() - 2)).sorted().toList();
        }
        JsTests.Result built = build();
        if (built.exit() != 0) {
            System.out.println("FAIL building the C programs\n" + built.output().indent(4));
            System.exit(1);
        }

        Map<String, byte[]> executables = new LinkedHashMap<>();
        for (String name : names) {
            executables.put(name, Files.readAllBytes(OUT.resolve(name)));
        }
        Path dukeDir = OUT.resolve("duke");
        Files.createDirectories(dukeDir);
        Map<String, JsTests.Result> onDuke = JsTests.runAllOnDuke(dukeDir, "c", executables, SHELL_ARGUMENTS);

        int failures = 0;
        for (String name : names) {
            List<String> linux = new ArrayList<>(JsTests.executable(OUT.resolve(name)));
            linux.addAll(ARGUMENTS);
            JsTests.Result expected = JsTests.run(linux);
            failures += JsTests.check(name, "linux", "duke", expected,
                    () -> onDuke.getOrDefault(name, new JsTests.Result("not run: an earlier program hung the kernel\n", -1)));
        }
        System.out.println("c-test: " + names.size() + " programs, " + failures + " failures");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
