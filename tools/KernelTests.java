import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs kernel tests: every {@code static void test*()} in tests/kernel, executed inside a booted
 * kernel. For code only the kernel can run (physical memory, page tables, interrupts), where the
 * conformance suite's HotSpot comparison doesn't apply. The kernel exits QEMU through the
 * isa-debug-exit device: writing v to port 0xf4 makes QEMU exit with status (v << 1) | 1.
 *
 * <p>Run from the repository root with {@code make ktest}.
 */
public class KernelTests {

    static final Path TESTS = Harness.ROOT.resolve("tests/kernel");
    static final Path OUT = Harness.ROOT.resolve("build/ktest");
    static final int PASS_STATUS = 1;
    static final int TIMEOUT_SECONDS = 300;

    public static void main(String[] args) throws Exception {
        Harness.deleteRecursively(OUT);
        Path gen = OUT.resolve("gen");
        writeMain(gen, List.of());
        // First pass only to find the tests in the compiled classes.
        Path scan = OUT.resolve("scan");
        Harness.compileKernel(scan, List.of(TESTS, gen));
        List<String> tests = discover(scan.resolve("java.base/duke/ktest"));
        writeMain(gen, tests);

        Path classes = OUT.resolve("classes");
        Harness.compileKernel(classes, List.of(TESTS, gen));
        Path esp = Harness.buildImage(classes, "duke/ktest/Main.main", OUT);
        Path serial = OUT.resolve("serial.log");
        Path disk = writeDisk(OUT.resolve("disk.img"));
        Path fat32 = OUT.resolve("fat32.img");
        FatImage.writeSample(fat32);
        Path fat16 = OUT.resolve("fat16");
        FatImage.writeSampleDirectory(fat16);
        // The disk sits behind a PCIe root port, so the PCI scan has a bridge to follow.
        Postmortem postmortem = Postmortem.in(OUT, OUT.resolve("kernel.map"));
        List<String> command = new ArrayList<>(List.of("tools/qemu.sh", esp.toString(), "-serial", "file:" + serial,
                "-device", "isa-debug-exit,iobase=0xf4,iosize=0x04",
                "-device", "pcie-root-port,id=rp0,chassis=1",
                "-drive", "if=none,id=disk0,format=raw,file=" + disk,
                "-device", "virtio-blk-pci,drive=disk0,bus=rp0",
                "-drive", "if=none,id=fat32,format=raw,file=" + fat32,
                "-device", "virtio-blk-pci,drive=fat32",
                "-drive", "if=none,id=fat16,format=raw,snapshot=on,file=fat:16:" + fat16,
                "-device", "virtio-blk-pci,drive=fat16"));
        command.addAll(postmortem.qemuArguments());
        Process qemu = new ProcessBuilder(command)
                .directory(Harness.ROOT.toFile())
                .redirectErrorStream(true)
                .redirectOutput(OUT.resolve("qemu.txt").toFile())
                .start();
        boolean exited = qemu.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String where = exited && qemu.exitValue() == PASS_STATUS ? "" : postmortem.report();
        if (!exited) {
            qemu.destroy();
        }
        String output = Harness.readSerial(serial);
        int start = output.indexOf("ktest ");
        System.out.print(start < 0 ? output : output.substring(start));
        int status = exited ? qemu.exitValue() : -1;
        if (status != PASS_STATUS) {
            System.out.print(where);
            System.out.println("ktest failed: QEMU exit status " + status + (exited ? "" : " (timed out)"));
            System.exit(1);
        }
    }

    static final int DISK_SECTORS = 64;

    /**
     * What VirtioBlockTest expects: sector n starts with "DUKEDISK" and n as a little-endian long,
     * and byte i after that is (n + i) & 0xFF.
     */
    static Path writeDisk(Path file) throws Exception {
        byte[] image = new byte[DISK_SECTORS * 512];
        for (int n = 0; n < DISK_SECTORS; n++) {
            int base = n * 512;
            byte[] magic = "DUKEDISK".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(magic, 0, image, base, 8);
            for (int b = 0; b < 8; b++) {
                image[base + 8 + b] = (byte) ((long) n >>> (8 * b));
            }
            for (int i = 16; i < 512; i++) {
                image[base + i] = (byte) (n + i);
            }
        }
        Files.write(file, image);
        return file;
    }

    static List<String> discover(Path dir) throws Exception {
        List<String> tests = new ArrayList<>();
        for (Path file : Files.list(dir).sorted().toList()) {
            if (!file.toString().endsWith(".class")) {
                continue;
            }
            ClassModel model = ClassFile.of().parse(file);
            String owner = model.thisClass().asInternalName().replace('/', '.');
            for (MethodModel m : model.methods()) {
                String name = m.methodName().stringValue();
                if (name.startsWith("test") && m.flags().has(AccessFlag.STATIC)
                        && m.methodType().equalsString("()V") && !m.flags().has(AccessFlag.PRIVATE)) {
                    tests.add(owner.substring("duke.ktest.".length()) + "." + name);
                }
            }
        }
        return tests;
    }

    static void writeMain(Path gen, List<String> tests) throws Exception {
        StringBuilder src = new StringBuilder("""
                package duke.ktest;

                import duke.kernel.Console;
                import duke.kernel.Kernel;
                import duke.rt.Magic;

                final class Main {

                    interface Test {
                        void run() throws Exception;
                    }

                    static int passed;
                    static int failed;

                    static void run(String name, Test test) {
                        Console.print("ktest " + name + " ... ");
                        try {
                            test.run();
                            passed++;
                            Console.println("ok");
                        } catch (Throwable t) {
                            failed++;
                            Console.println("FAIL");
                            t.printStackTrace();
                        }
                    }

                    static void main() {
                        Kernel.init();
                """);
        for (String test : tests) {
            src.append("        run(\"").append(test).append("\", ").append(test.replace('.', ':').replaceFirst(":", "::"))
                    .append(");\n");
        }
        src.append("""
                        Console.println("ktest: " + passed + "/" + (passed + failed) + " passed");
                        Magic.outb(0xf4, failed == 0 ? 0 : 1);
                    }
                }
                """);
        Path file = gen.resolve("duke/ktest/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, src);
    }
}
