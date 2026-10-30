import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Boots one kernel per file in tests/panics and checks the serial output contains every line named
 * in the file's leading {@code // expect: ...} comments, in order. The first is the panic line.
 * Covers the runtime checks the conformance suite can't reach, because there a failed check stops
 * the whole run.
 *
 * <p>Run from the repository root with {@code make panic-tests}.
 */
public class PanicTests {

    static final Path TESTS = Harness.ROOT.resolve("tests/panics");
    static final Path OUT = Harness.ROOT.resolve("build/panics");
    static final int TIMEOUT_SECONDS = 120;

    record Case(String name, List<String> expected) {}

    record Result(Case test, boolean passed, String output) {}

    public static void main(String[] args) throws Exception {
        Harness.deleteRecursively(OUT);
        List<Case> cases = new ArrayList<>();
        for (Path source : Harness.javaFiles(TESTS)) {
            List<String> expected = new ArrayList<>();
            for (String line : Files.readAllLines(source)) {
                if (!line.startsWith("// expect: ")) {
                    break;
                }
                expected.add(line.substring("// expect: ".length()));
            }
            if (expected.isEmpty()) {
                throw new IllegalStateException(source + " must start with // expect: <panic line>");
            }
            cases.add(new Case(source.getFileName().toString().replace(".java", ""), expected));
        }

        Path classes = OUT.resolve("classes");
        Harness.compileKernel(classes, List.of(TESTS));

        List<Future<Result>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2))) {
            for (Case c : cases) {
                futures.add(pool.submit(() -> boot(classes, c)));
            }
        }

        int passed = 0;
        for (Future<Result> f : futures) {
            Result r = f.get();
            if (r.passed()) {
                passed++;
                System.out.println("ok   " + r.test().name());
            } else {
                System.out.println("FAIL " + r.test().name() + ": expected, in order:");
                r.test().expected().forEach(line -> System.out.println("    " + line));
                System.out.println("  got:");
                System.out.println(r.output().indent(4));
            }
        }
        System.out.println("panic-tests: " + passed + "/" + cases.size() + " passed");
        System.exit(passed == cases.size() ? 0 : 1);
    }

    /** Whole lines only: output arrives a character at a time, so a line isn't done until its newline. */
    static boolean containsInOrder(String output, List<String> expected) {
        int next = 0;
        for (String line : output.split("\n", -1)) {
            if (next < expected.size() && line.equals(expected.get(next)) && output.contains(line + "\n")) {
                next++;
            }
        }
        return next == expected.size();
    }

    static Result boot(Path classes, Case c) throws Exception {
        Path dir = OUT.resolve(c.name());
        Path esp = Harness.buildImage(classes, "duke/panics/" + c.name() + ".main", dir);
        Path serial = dir.resolve("serial.log");
        Postmortem postmortem = Postmortem.in(dir, dir.resolve("kernel.map"));
        List<String> command = new ArrayList<>(List.of("tools/qemu.sh", esp.toString(), "-serial", "file:" + serial));
        command.addAll(postmortem.qemuArguments());
        Process qemu = new ProcessBuilder(command)
                .directory(Harness.ROOT.toFile())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("qemu.txt").toFile())
                .start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline) {
                String output = Harness.readSerial(serial);
                if (containsInOrder(output, c.expected())) {
                    return new Result(c, true, output);
                }
                if (!qemu.isAlive()) {
                    break;
                }
                Thread.sleep(100);
            }
            return new Result(c, false, Harness.readSerial(serial) + postmortem.report());
        } finally {
            qemu.destroy();
            qemu.waitFor();
        }
    }
}
