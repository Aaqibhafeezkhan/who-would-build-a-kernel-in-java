import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Unmodified busybox in ring 3: builds it static against musl from the release tarball, puts it on
 * the sample disk at /bin/busybox, and checks each command line below prints the same and exits the
 * same on Duke as on Linux. Needs Docker. Run from the repository root with {@code make busybox-test}.
 */
public class BusyboxTest {

    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path OUT = ROOT.resolve("build/busybox");
    static final String VERSION = "1.37.0";
    static final String TARBALL = "busybox-" + VERSION + ".tar.bz2";
    static final String SHA256 = "3311dff32e746499f4df0d5df04d7eb396382d7e108bb9250e7b519b837043a4";
    static final String ALPINE = CTests.ALPINE;
    // tc needs the CBQ definitions Linux 6.8's headers dropped.
    static final String CONFIG = "sed -i 's/^CONFIG_TC=y/# CONFIG_TC is not set/; "
            + "s/^# CONFIG_STATIC is not set/CONFIG_STATIC=y/' .config";

    /** Each one argv after "busybox". No argument may hold a double quote, which the shell can't type. */
    static final List<List<String>> CASES = List.of(
            List.of("echo", "hello from busybox"),
            List.of("true"),
            List.of("false"),
            List.of("pwd"),
            List.of("uname", "-m"),
            List.of("cat", "/hello.txt", "/A Long File Name.md"),
            List.of("cat", "/nope"),
            List.of("wc", "-c", "/big.bin"),
            List.of("md5sum", "/big.bin"),
            List.of("sha256sum", "/big.bin"),
            List.of("seq", "3"),
            List.of("sort", "/hello.txt", "/README.TXT", "/docs/deeper/NOTES.TXT"),
            List.of("grep", "-c", "o", "/hello.txt"),
            List.of("sed", "s/FAT/fat/", "/hello.txt"),
            List.of("awk", "BEGIN { print 6 * 7 }"),
            List.of("expr", "6", "*", "7"),
            List.of("basename", "/docs/deeper/NOTES.TXT"),
            List.of("sleep", "0.1"),
            List.of("sh", "-c", "x=duke; echo $x $((6 * 7))"),
            List.of("sh", "-c", "for i in 1 2 3; do echo $i; done"),
            List.of("sh", "-c", "echo $(echo nested)"),
            List.of("sh", "-c", "/bin/busybox echo piped | /bin/busybox wc -c"),
            List.of("sh", "-c", "exit 3"));

    public static void main(String[] args) throws Exception {
        Path busybox = build();
        Path root = OUT.resolve("root");
        deleteTree(root);
        FatImage.writeSampleDirectory(root);
        Files.copy(busybox, root.resolve("bin/busybox"));

        Map<String, byte[]> files = new LinkedHashMap<>(FatImage.sample());
        files.put("bin/busybox", Files.readAllBytes(busybox));
        List<String> lines = new ArrayList<>();
        for (List<String> argv : CASES) {
            StringBuilder line = new StringBuilder("run /bin/busybox");
            for (String arg : argv) {
                if (arg.contains("\"")) {
                    throw new IllegalArgumentException("can't type a double quote: " + arg);
                }
                line.append(' ').append(arg.contains(" ") || arg.isEmpty() ? "\"" + arg + "\"" : arg);
            }
            lines.add(line.toString());
        }
        Path dukeDir = OUT.resolve("duke");
        Files.createDirectories(dukeDir);
        List<JsTests.Result> onDuke = JsTests.runLinesOnDuke(dukeDir, "busybox.img", files, lines);

        int failures = 0;
        for (int i = 0; i < CASES.size(); i++) {
            int at = i;
            JsTests.Result expected = JsTests.run(onLinux(root, CASES.get(i)));
            failures += JsTests.check(String.join(" ", CASES.get(i)), "linux", "duke", expected,
                    () -> at < onDuke.size() ? onDuke.get(at)
                            : new JsTests.Result("not run: an earlier command hung the kernel\n", -1));
        }
        System.out.println("busybox-test: " + CASES.size() + " commands, " + failures + " failures");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /**
     * The same tree at the same paths: each top-level entry of {@code root} mounted at /, its bin
     * over Alpine's, so a command line means the same file on both sides.
     */
    static List<String> onLinux(Path root, List<String> argv) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker", "run", "-q", "--rm", "--platform", "linux/amd64"));
        try (Stream<Path> entries = Files.list(root)) {
            for (Path entry : entries.sorted().toList()) {
                command.add("-v");
                command.add(entry + ":/" + entry.getFileName() + ":ro");
            }
        }
        command.add(ALPINE);
        command.add("/bin/busybox");
        command.addAll(argv);
        return command;
    }

    /** The binary, built once per version and configuration and kept in build/busybox. */
    static Path build() throws Exception {
        Path binary = OUT.resolve("busybox");
        Path stamp = OUT.resolve("built-from");
        String recipe = TARBALL + " " + SHA256 + "\n" + ALPINE + "\n" + CONFIG + "\n";
        if (Files.exists(binary) && Files.exists(stamp) && Files.readString(stamp).equals(recipe)) {
            return binary;
        }
        Files.createDirectories(OUT);
        Path tarball = OUT.resolve(TARBALL);
        if (!Files.exists(tarball) || !sha256(tarball).equals(SHA256)) {
            System.out.println("busybox-test: downloading " + TARBALL);
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<Path> got = http.send(HttpRequest.newBuilder(URI.create("https://busybox.net/downloads/" + TARBALL)).build(),
                    HttpResponse.BodyHandlers.ofFile(tarball));
            if (got.statusCode() != 200 || !sha256(tarball).equals(SHA256)) {
                Files.deleteIfExists(tarball);
                throw new IllegalStateException(TARBALL + ": HTTP " + got.statusCode() + " or the wrong sha256");
            }
        }
        System.out.println("busybox-test: building busybox " + VERSION + " (a few minutes the first time)");
        // Under Docker on an arm64 Mac, x86 gcc runs in qemu-user and now and then segfaults on a
        // file it compiles fine the next time, so a crash gets another go where the build left off.
        String script = "set -e; apk add -q gcc make musl-dev linux-headers bzip2 perl >/dev/null; "
                + "mkdir /src; tar xjf /b/" + TARBALL + " -C /src --strip-components 1; cd /src; "
                + "make -s defconfig >/dev/null 2>&1; " + CONFIG + "; "
                + "for try in $(seq 20); do "
                + "if make -s -j$(nproc) busybox >/tmp/build.log 2>&1; then cp busybox /b/busybox; exit 0; fi; "
                + "grep -q 'Segmentation fault' /tmp/build.log || break; done; tail -40 /tmp/build.log; exit 1";
        JsTests.Result built = JsTests.run(List.of("docker", "run", "--rm", "--platform", "linux/amd64",
                "-v", OUT + ":/b", ALPINE, "sh", "-c", script));
        if (built.exit() != 0) {
            System.out.println("FAIL building busybox\n" + built.output().indent(4));
            System.exit(1);
        }
        Files.writeString(stamp, recipe);
        return binary;
    }

    static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    static void deleteTree(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.delete(p);
            }
        }
    }
}
