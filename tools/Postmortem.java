import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a test boot was when it hung or died: each CPU's RIP as the Java method it's in, from
 * kernel.map. A boot that's still running is sampled a few times through the QEMU monitor, so a
 * slow kernel (the methods change) can be told from a stuck one (they don't). One that already
 * exited can't be asked; under -no-reboot QEMU logs a triple fault without the registers, and
 * -d int would have them but logs every timer interrupt too, so it says only what QEMU said.
 *
 * <p>{@code java tools/Postmortem.java MONITOR_SOCKET KERNEL_MAP QEMU_LOG}, or from a harness:
 * {@link #in}, {@link #qemuArguments} when starting QEMU, and {@link #report} before stopping it.
 */
final class Postmortem {

    static final int SAMPLES = 3;
    static final Pattern CPU = Pattern.compile("CPU#(\\d+)");
    static final Pattern RIP = Pattern.compile("RIP=([0-9a-f]{16})");

    final Path monitor;
    final Path map;
    final Path qemuLog;

    Postmortem(Path monitor, Path map, Path qemuLog) {
        this.monitor = monitor;
        this.map = map;
        this.qemuLog = qemuLog;
    }

    /** The monitor socket goes in a fresh temporary directory: Unix socket paths max out at 108 bytes. */
    static Postmortem in(Path dir, Path map) throws IOException {
        return new Postmortem(Files.createTempDirectory("duke").resolve("monitor.sock"), map, dir.resolve("qemu.log"));
    }

    List<String> qemuArguments() {
        return List.of("-monitor", "unix:" + monitor + ",server=on,wait=off",
                // QEMU's own account of guest errors and CPU resets, for boots that die rather than hang.
                "-d", "guest_errors,cpu_reset,unimp", "-D", qemuLog.toString());
    }

    public static void main(String[] args) throws Exception {
        System.out.print(new Postmortem(Path.of(args[0]), Path.of(args[1]), Path.of(args[2])).report());
    }

    String report() {
        StringBuilder out = new StringBuilder();
        try {
            Symbols symbols = Symbols.load(map);
            if (Files.exists(monitor) && sample() != null) {
                out.append("postmortem: where each CPU was, ").append(SAMPLES).append(" samples a second apart:\n");
                Map<Integer, List<String>> places = new TreeMap<>();
                for (int i = 0; i < SAMPLES; i++) {
                    if (i > 0) {
                        Thread.sleep(1000);
                    }
                    String registers = sample();
                    if (registers == null) {
                        break;
                    }
                    rips(registers).forEach((cpu, rip) ->
                            places.computeIfAbsent(cpu, c -> new ArrayList<>()).add(symbols.describe(rip)));
                }
                places.forEach((cpu, where) -> out.append("  CPU ").append(cpu).append(": ")
                        .append(String.join(" | ", where)).append('\n'));
            } else if (Files.exists(qemuLog) && Files.readString(qemuLog).contains("Triple fault")) {
                out.append("postmortem: QEMU exited on a triple fault (rerun with -d int to see the exceptions before it)\n");
            } else {
                out.append("postmortem: QEMU exited\n");
            }
        } catch (Exception e) {
            out.append("postmortem: failed: ").append(e).append('\n');
        }
        if (Files.exists(qemuLog)) {
            out.append("postmortem: QEMU's log is ").append(qemuLog).append('\n');
        }
        return out.toString();
    }

    /** {@code info registers -a}, or null once QEMU has gone. */
    String sample() {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(monitor));
            channel.write(ByteBuffer.wrap("info registers -a\n".getBytes(StandardCharsets.US_ASCII)));
            Thread.sleep(500);
            channel.configureBlocking(false);
            StringBuilder answer = new StringBuilder();
            ByteBuffer buffer = ByteBuffer.allocate(1 << 16);
            for (int n; (n = channel.read(buffer)) > 0; buffer.clear()) {
                answer.append(new String(buffer.array(), 0, n, StandardCharsets.US_ASCII));
            }
            return answer.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Each CPU's RIP in {@code info registers -a}. */
    static Map<Integer, Long> rips(String text) {
        Map<Integer, Long> rips = new TreeMap<>();
        Matcher h = CPU.matcher(text);
        if (!h.find()) {
            return rips;
        }
        while (true) {
            int cpu = Integer.parseInt(h.group(1));
            int start = h.end();
            boolean more = h.find();
            Matcher r = RIP.matcher(text).region(start, more ? h.start() : text.length());
            if (r.find()) {
                rips.put(cpu, Long.parseUnsignedLong(r.group(1), 16));
            }
            if (!more) {
                return rips;
            }
        }
    }

    /** kernel.map: an address, a size and a name per line (dukec --map, Main.mapLines). */
    record Symbols(long[] starts, long[] ends, String[] names, long textStart, long textEnd) {

        static Symbols load(Path map) throws IOException {
            List<String[]> rows = new ArrayList<>();
            long textStart = 0;
            long textEnd = 0;
            for (String line : Files.readAllLines(map)) {
                String[] f = line.trim().split("\\s+", 3);
                if (f.length < 3) {
                    continue;
                }
                long start = Long.parseUnsignedLong(f[0], 16);
                switch (f[2]) {
                    case "image.text.start" -> textStart = start;
                    case "image.text.end" -> textEnd = start;
                    default -> {
                        if (Long.parseLong(f[1]) > 0) {
                            rows.add(f);
                        }
                    }
                }
            }
            rows.sort((a, b) -> Long.compareUnsigned(Long.parseUnsignedLong(a[0], 16), Long.parseUnsignedLong(b[0], 16)));
            long[] starts = new long[rows.size()];
            long[] ends = new long[rows.size()];
            String[] names = new String[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                starts[i] = Long.parseUnsignedLong(rows.get(i)[0], 16);
                ends[i] = starts[i] + Long.parseLong(rows.get(i)[1]);
                names[i] = rows.get(i)[2];
            }
            return new Symbols(starts, ends, names, textStart, textEnd);
        }

        String describe(long rip) {
            String hex = String.format("%016x", rip);
            int lo = 0;
            int hi = starts.length - 1;
            int found = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (Long.compareUnsigned(starts[mid], rip) <= 0) {
                    found = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            if (found >= 0 && Long.compareUnsigned(rip, ends[found]) < 0) {
                return names[found] + "+0x" + Long.toHexString(rip - starts[found]);
            }
            boolean inText = Long.compareUnsigned(rip, textStart) >= 0 && Long.compareUnsigned(rip, textEnd) < 0;
            return hex + (inText ? " (kernel code, no symbol)" : " (outside the kernel: user code, Limine or firmware)");
        }
    }
}
