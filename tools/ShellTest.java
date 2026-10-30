import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives the real kernel's shell: boots it with the serial port on pipes and the QEMU monitor on
 * a Unix socket, types commands over serial and as PS/2 keystrokes (monitor "sendkey"), and checks
 * the replies. Covers IOAPIC routing, both interrupt handlers, the input ring and the shell.
 *
 * <p>Run from the repository root with {@code make shell-test}.
 */
public class ShellTest {

    static final Path ESP = Harness.ROOT.resolve("build/esp");
    static final Path OUT = Harness.ROOT.resolve("build/shell-test");
    static final long TIMEOUT_MILLIS = 120_000;

    /** What the churn command's second loop adds up: the ids 0, 100, ... plus the lengths of "item<id>". */
    static long sumIds() {
        long sum = 0;
        for (int i = 0; i < 200000; i += 100) {
            sum += i + ("item" + i).length();
        }
        return sum;
    }

    static final StringBuffer output = new StringBuffer();
    static int checks;

    public static void main(String[] args) throws Exception {
        Harness.deleteRecursively(OUT);
        Files.createDirectories(OUT);
        Postmortem postmortem = Postmortem.in(OUT, ESP.resolveSibling("kernel.map"));
        Path monitor = postmortem.monitor;
        Path fat32 = OUT.resolve("fat32.img");
        FatImage.writeSample(fat32);
        List<String> command = new ArrayList<>(List.of("tools/qemu.sh", ESP.toString(), "-serial", "stdio",
                "-drive", "if=none,id=fat32,format=raw,snapshot=on,file=" + fat32,
                "-device", "virtio-blk-pci,drive=fat32"));
        command.addAll(postmortem.qemuArguments());
        Process qemu = new ProcessBuilder(command)
                .directory(Harness.ROOT.toFile())
                .redirectErrorStream(true)
                .start();
        Thread reader = new Thread(() -> pump(qemu.getInputStream()));
        reader.setDaemon(true);
        reader.start();
        OutputStream serial = qemu.getOutputStream();
        int failures = 0;
        try {
            if (!await("duke> ", 0)) {
                // A triple fault exits silently under -no-reboot, which looks just like a hang.
                String state = qemu.isAlive() ? "still running" : "exited with status " + qemu.exitValue();
                System.out.println("shell-test: " + Runtime.getRuntime().availableProcessors() + " cores; busiest processes:");
                new ProcessBuilder("sh", "-c", "ps -eo pid,pcpu,etime,args --sort=-pcpu 2>/dev/null | head -12 || ps -Ao pid,pcpu,etime,command -r | head -12")
                        .inheritIO().start().waitFor();
                System.out.print(postmortem.report());
                qemu.destroy();
                qemu.waitFor(10, TimeUnit.SECONDS);
                Files.writeString(OUT.resolve("serial.log"), output.toString());
                System.out.println("shell-test: the kernel never reached its prompt (QEMU " + state + "); QEMU said:\n"
                        + clean(output.toString()).indent(4));
                System.exit(1);
            }
            failures += check("serial", () -> type(serial, "echo hello over serial\n"), "hello over serial");
            failures += check("serial editing", () -> type(serial, "ecxx\u007f\u007fho fixed\n"), "fixed");
            failures += check("serial left arrow insert", () -> type(serial, "echo due\u001b[Dk\n"), "\nduke\n");
            failures += check("serial right arrow", () -> type(serial,
                    "echo duk\u001b[D\u001b[Ce\n"), "\nduke\n");
            failures += check("serial home", () -> type(serial,
                    "duke\u001b[Hecho \n"), "\nduke\n");
            failures += check("serial end", () -> type(serial,
                    "echo duk\u001b[D\u001b[D\u001b[Fe\n"), "\nduke\n");
            failures += check("serial delete", () -> type(serial,
                    "echo dukxe\u001b[D\u001b[D\u001b[3~\n"), "\nduke\n");
            failures += check("serial split escape sequence", () -> {
                type(serial, "echo due\u001b");
                Thread.sleep(50);
                type(serial, "[");
                Thread.sleep(50);
                type(serial, "Dk\n");
            }, "\nduke\n");
            failures += check("unknown command", () -> type(serial, "frobnicate\n"), "unknown command: frobnicate (try help)");
            failures += check("ps/2 keyboard", () -> keys(monitor, "u p t i m e ret"), "up ");
            failures += check("ps/2 shift", () -> keys(monitor, "e c h o spc shift-d u k e shift-1 ret"), "Duke!");
            failures += check("mem", () -> type(serial, "mem\n"), "frames: ");
            failures += check("threads", () -> type(serial, "threads\n"), "main: running");
            failures += check("pci host bridge", () -> type(serial, "pci\n"), "00:00.0 8086:29c0 class 06.00.00");
            failures += check("pci ahci", () -> type(serial, "pci\n"), "00:1f.2 8086:2922 class 01.06.01");
            failures += check("ls", () -> type(serial, "ls\n"), "A Long File Name.md  16");
            failures += check("cat", () -> type(serial, "cat hello.txt\n"), "hello from FAT32");
            failures += check("cat nested", () -> type(serial, "cat docs/deeper/notes.txt\n"), "nested");
            failures += check("hexdump first line", () -> type(serial, "hexdump /big.bin\n"), "00000000: 03 0a 11 18 1f 26 2d 34 3b 42 49 50 57 5e 65 6c  .....&-4;BIPW^el");
            failures += check("hexdump last line", () -> type(serial, "hexdump /big.bin\n"), "00001380: 83 8a 91 98 9f a6 ad b4 " + " ".repeat(25) + "........");
            failures += check("help lists hexdump", () -> type(serial, "help\n"), "hexdump <path>");
            failures += check("run", () -> type(serial, "run /bin/hello\n"), "hello from ring 3, I am /bin/hello");
            failures += check("run a crash", () -> type(serial, "run bin/crash\n"), "bin/crash: #GP");
            failures += check("run a text file", () -> type(serial, "run hello.txt\n"), "run: hello.txt: not an ELF file");
            failures += check("run with arguments", () -> type(serial, "run /bin/cat /docs/deeper/notes.txt\n"), "nested");
            failures += check("a program reads a file", () -> type(serial, "run /bin/cat hello.txt\n"), "hello from FAT32");
            failures += check("a program reads the keyboard", () -> {
                type(serial, "run /bin/echoline\n");
                Thread.sleep(500);
                type(serial, "typed at duke\n");
            }, "you said: typed at duke");
            long framesBefore = freeFrames(serial);
            failures += check("ctrl-c stops a spinning program", () -> {
                type(serial, "run /bin/spin\n");
                Thread.sleep(500);
                type(serial, "\u0003");
            }, "^C\nduke> ");
            long framesAfter = freeFrames(serial);
            checks++;
            if (framesAfter == framesBefore) {
                System.out.println("ok   ctrl-c frees the program's memory");
            } else {
                System.out.println("FAIL ctrl-c frees the program's memory: " + framesBefore + " free frames before, " + framesAfter + " after");
                failures++;
            }
            failures += check("ctrl-c stops a program waiting for input", () -> {
                type(serial, "run /bin/echoline\n");
                Thread.sleep(500);
                type(serial, "\u0003");
            }, "^C\nduke> ");
            failures += checkAbsent("ctrl-c discards a partial command",
                    () -> type(serial, "echo abc\u0003"), "^C", "\nabc\n");
            failures += check("command after ctrl-c executes", () -> type(serial, "echo def\n"), "\ndef\n");
            failures += check("the prompt is back after ctrl-c", () -> type(serial, "echo prompt is back\n"), "\nprompt is back");
            failures += check("ps/2 insert", () -> keys(monitor, "e c h o spc d u e left k ret"), "duke");
            failures += check("javascript command", () -> type(serial, "hello\n"),
                    "hello from JavaScript running in the Duke kernel");
            failures += check("javascript arguments", () -> type(serial, "fib 20\n"), "fib(20) = 6765");
            failures += check("javascript objects", () -> type(serial, "meminfo\n"), "freeFrames:");
            failures += check("javascript under gc", () -> type(serial, "churn\n"),
                    "churn: kept 2000, total 20000100000, ids 19900000"
                            .replace("19900000", String.valueOf(sumIds())) + ", collections ran");
            failures += check("javascript exceptions", () -> type(serial, "catches\n"),
                    "catches: RangeError: thrown | TypeError | Maximum call stack size exceeded | try | finally");
            failures += check("javascript collections", () -> type(serial, "collections\n"),
                    "collections: 9 distinct, the=3, object key, the/quick/brown");
            failures += check("javascript generators", () -> type(serial, "sequence\n"),
                    "sequence: 0,1,1,2,3,5,8,13,21,34 cleaned=true");
            failures += check("help lists registered commands", () -> type(serial, "help\n"), "also: hello, fib, meminfo, churn, catches, collections, sequence");
            failures += framebuffer(serial, monitor);
            failures += check("panic", () -> type(serial, "panic\n"), "PANIC: requested from the shell");
        } finally {
            qemu.destroy();
            qemu.waitFor(10, TimeUnit.SECONDS);
            Files.writeString(OUT.resolve("serial.log"), output.toString());
        }
        System.out.println("shell-test: " + (checks - failures) + "/" + checks + " passed");
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * Clears the screen, echoes XYZ (which lands on text row 1, under the prompt) and checks the
     * screendump pixel for pixel against the kernel's own font. The dump stays in build/shell-test.
     */
    static int framebuffer(OutputStream serial, Path monitor) throws Exception {
        checks++;
        int from = output.length();
        type(serial, "clear\necho XYZ\n");
        if (!await("XYZ", from) || !await("duke> ", output.length() - 8)) {
            System.out.println("FAIL framebuffer: echo never came back");
            return 1;
        }
        Path ppm = OUT.resolve("screen.ppm");
        command(monitor, "screendump " + ppm);
        for (int i = 0; i < 100 && (!Files.exists(ppm) || Files.size(ppm) == 0); i++) {
            Thread.sleep(50);
        }
        Thread.sleep(200);
        byte[] image = Files.readAllBytes(ppm);
        // P6 header: magic, width, height, max value, each followed by whitespace.
        String[] header = new String(image, 0, 32, StandardCharsets.US_ASCII).split("\\s+");
        int width = Integer.parseInt(header[1]);
        int headerLength = String.join("\n", header[0], header[1] + " " + header[2], header[3]).length() + 1;
        String glyphs = font();
        int mismatches = 0;
        for (int i = 0; i < 3; i++) {
            int c = "XYZ".charAt(i);
            for (int y = 0; y < 16; y++) {
                int bits = glyphs.charAt(c * 16 + y);
                for (int x = 0; x < 8; x++) {
                    int px = i * 8 + x;
                    int py = 16 + y;
                    int red = image[headerLength + 3 * (py * width + px)] & 0xFF;
                    boolean lit = red > 0x80;
                    if (lit != ((bits & (0x80 >> x)) != 0)) {
                        mismatches++;
                    }
                }
            }
        }
        if (mismatches == 0) {
            System.out.println("ok   framebuffer");
            return 0;
        }
        System.out.println("FAIL framebuffer: " + mismatches + " of 384 pixels differ from the font (see " + ppm + ")");
        return 1;
    }

    /** The glyph table from the kernel's own Font class, compiled for the host. */
    static String font() throws Exception {
        Path classes = OUT.resolve("font");
        Harness.javac(List.of("-d", classes.toString()), List.of(Harness.KERNEL_SOURCES.resolve("duke/kernel/Font.java")));
        try (var loader = new java.net.URLClassLoader(new java.net.URL[] {classes.toUri().toURL()})) {
            var field = loader.loadClass("duke.kernel.Font").getDeclaredField("GLYPHS");
            field.setAccessible(true);
            return (String) field.get(null);
        }
    }

    static void command(Path monitor, String line) throws Exception {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(monitor));
            channel.write(ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.US_ASCII)));
            Thread.sleep(300);
        }
    }

    interface Action {
        void run() throws Exception;
    }

    /** Runs {@code action}, then waits for {@code expected} to appear after where output stood before. */
    static int check(String name, Action action, String expected) throws Exception {
        checks++;
        int from = output.length();
        action.run();
        if (await(expected, from)) {
            System.out.println("ok   " + name);
            return 0;
        }
        System.out.println("FAIL " + name + ": no \"" + expected + "\" in:\n" + clean(output.substring(from)).indent(4));
        return 1;
    }

    /** Runs an action and verifies expected output appears without a forbidden output fragment. */
    static int checkAbsent(String name, Action action, String expected, String forbidden) throws Exception {
        checks++;
        int from = output.length();
        action.run();
        if (!await(expected, from)) {
            System.out.println("FAIL " + name + ": no \"" + expected + "\" in:\n" + clean(output.substring(from)).indent(4));
            return 1;
        }
        String actual = clean(output.substring(from));
        if (actual.contains(forbidden)) {
            System.out.println("FAIL " + name + ": unexpected \"" + forbidden + "\" in:\n" + actual.indent(4));
            return 1;
        }
        System.out.println("ok   " + name);
        return 0;
    }

    static boolean await(String text, int from) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (clean(output.substring(Math.min(from, output.length()))).contains(text)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    /** The free frame count from the shell's mem command. */
    static long freeFrames(OutputStream serial) throws Exception {
        int from = output.length();
        type(serial, "mem\n");
        if (!await(" free (", from)) {
            throw new IllegalStateException("mem printed nothing");
        }
        Matcher m = Pattern.compile("frames: (\\d+) free").matcher(clean(output.substring(from)));
        if (!m.find()) {
            throw new IllegalStateException("no frame count in: " + clean(output.substring(from)));
        }
        return Long.parseLong(m.group(1));
    }

    static void type(OutputStream serial, String text) throws Exception {
        serial.write(text.getBytes(StandardCharsets.ISO_8859_1));
        serial.flush();
    }

    /** Sends each space-separated QEMU key name through the human monitor's sendkey. */
    static void keys(Path monitor, String names) throws Exception {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(monitor));
            for (String key : names.split(" ")) {
                channel.write(ByteBuffer.wrap(("sendkey " + key + "\n").getBytes(StandardCharsets.US_ASCII)));
                // Give each key's press and release time to reach the guest before the next.
                Thread.sleep(80);
            }
        }
    }

    static void pump(InputStream in) {
        byte[] buffer = new byte[4096];
        try {
            for (int n; (n = in.read(buffer)) > 0; ) {
                output.append(new String(buffer, 0, n, StandardCharsets.ISO_8859_1));
            }
        } catch (java.io.IOException e) {
            // QEMU exited.
        }
    }

    static String clean(String s) {
        return s.replaceAll("\\x1b\\[[0-9;=?]*[A-Za-z]", "").replace("\r", "");
    }
}
