import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a FAT32 disk image (no partition table) from a map of paths to contents, for the test
 * harnesses. QEMU's fat:32: directories can't stand in: they get a FAT16 boot sector. Names that
 * aren't already uppercase 8.3 get long-name entries. Clusters are one sector, so the image is
 * just big enough for the FAT32 minimum of 65525 clusters, and files named in {@code scattered}
 * get every other cluster so their chains aren't contiguous.
 */
public class FatImage {

    static final int SECTOR = 512;
    static final int TOTAL_SECTORS = 70000;
    static final int RESERVED = 32;
    static final int FAT_SECTORS = 547;
    static final int DATA_START = RESERVED + 2 * FAT_SECTORS;
    static final int CLUSTERS = TOTAL_SECTORS - DATA_START;
    static final int END_OF_CHAIN = 0x0FFFFFFF;

    static final class Node {
        final String name;
        final byte[] contents;
        final Map<String, Node> children = new LinkedHashMap<>();
        final List<Integer> clusters = new ArrayList<>();

        Node(String name, byte[] contents) {
            this.name = name;
            this.contents = contents;
        }

        boolean directory() {
            return contents == null;
        }
    }

    /** What ShellTest and the kernel's FatTest expect to find. */
    public static Map<String, byte[]> sample() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("hello.txt", "hello from FAT32\n".getBytes(StandardCharsets.US_ASCII));
        files.put("README.TXT", "short names only\n".getBytes(StandardCharsets.US_ASCII));
        files.put("A Long File Name.md", "long names work\n".getBytes(StandardCharsets.US_ASCII));
        files.put("exactly-twenty-six-chars.x", "no terminator\n".getBytes(StandardCharsets.US_ASCII));
        files.put("empty.txt", new byte[0]);
        files.put("docs/deeper/NOTES.TXT", "nested\n".getBytes(StandardCharsets.US_ASCII));
        byte[] big = new byte[5000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 7 + 3);
        }
        files.put("big.bin", big);
        files.put("bin/hello", helloElf());
        files.put("bin/crash", crashElf());
        return files;
    }

    static final long ELF_BASE = 0x400000;
    static final int ELF_HEADERS = 64 + 3 * 56;

    /**
     * Prints "hello from ring 3, I am " and its argv[0], then checks its bss: a word in the data
     * page past the file's bytes and one in a page with none must start at zero and take a write.
     * Exits 0 if they did. Code and data are separate segments, plus a PT_GNU_STACK to skip.
     */
    static byte[] helloElf() {
        long data = ELF_BASE + 0x1000;
        long bss1 = data + 0x800;
        long bss2 = data + 0x1800;
        byte[] message = "hello from ring 3, I am \n".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer code = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        code.put((byte) 0xB8).putInt(1).put((byte) 0xBF).putInt(1);                  // write(1,
        code.put((byte) 0xBE).putInt((int) data).put((byte) 0xBA).putInt(message.length - 1);  // message)
        code.put(new byte[] {0x0F, 0x05});
        code.put(new byte[] {0x48, (byte) 0x8B, 0x74, 0x24, 0x08});                  // mov rsi, argv[0]
        code.put(new byte[] {0x31, (byte) 0xD2});                                    // xor edx, edx
        code.put(new byte[] {(byte) 0x80, 0x3C, 0x16, 0x00, 0x74, 0x05});            // cmp byte [rsi+rdx], 0; je
        code.put(new byte[] {0x48, (byte) 0xFF, (byte) 0xC2, (byte) 0xEB, (byte) 0xF5});  // inc rdx; jmp back
        code.put((byte) 0xB8).putInt(1).put((byte) 0xBF).putInt(1).put(new byte[] {0x0F, 0x05});  // write(1, argv[0])
        code.put((byte) 0xB8).putInt(1).put((byte) 0xBF).putInt(1);                  // write(1, "\n")
        code.put((byte) 0xBE).putInt((int) data + message.length - 1).put((byte) 0xBA).putInt(1);
        code.put(new byte[] {0x0F, 0x05});
        code.put(new byte[] {(byte) 0xC7, 0x04, 0x25}).putInt((int) bss2).putInt(7);  // mov dword [bss2], 7
        code.put(new byte[] {(byte) 0x8B, 0x3C, 0x25}).putInt((int) bss1);           // mov edi, [bss1]
        code.put(new byte[] {0x03, 0x3C, 0x25}).putInt((int) bss2);                  // add edi, [bss2]
        code.put(new byte[] {(byte) 0x81, (byte) 0xEF}).putInt(7);                   // sub edi, 7
        code.put((byte) 0xB8).putInt(60).put(new byte[] {0x0F, 0x05});               // exit(edi)
        int codeBytes = code.position();
        ByteBuffer elf = elfHeader(0x1000 + message.length, 3);
        elf.put(Arrays.copyOf(code.array(), codeBytes));
        programHeader(elf, 0, 1, 5, 0, ELF_BASE, ELF_HEADERS + codeBytes, ELF_HEADERS + codeBytes);
        programHeader(elf, 1, 1, 6, 0x1000, data, message.length, 0x2000);
        programHeader(elf, 2, 0x6474E551, 6, 0, 0, 0, 0);
        elf.put(0x1000, message);
        return elf.array();
    }

    /** hlt, which ring 3 isn't allowed. */
    static byte[] crashElf() {
        ByteBuffer elf = elfHeader(ELF_HEADERS + 1, 1);
        elf.put((byte) 0xF4);
        programHeader(elf, 0, 1, 5, 0, ELF_BASE, ELF_HEADERS + 1, ELF_HEADERS + 1);
        return elf.array();
    }

    /** An executable's header, with its entry point right after the program headers, and the buffer positioned there. */
    private static ByteBuffer elfHeader(int size, int programHeaders) {
        ByteBuffer elf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        elf.put(new byte[] {0x7F, 'E', 'L', 'F', 2, 1, 1});
        elf.putShort(16, (short) 2).putShort(18, (short) 62).putInt(20, 1).putLong(24, ELF_BASE + ELF_HEADERS);
        elf.putLong(32, 64).putShort(52, (short) 64).putShort(54, (short) 56).putShort(56, (short) programHeaders);
        elf.position(ELF_HEADERS);
        return elf;
    }

    private static void programHeader(ByteBuffer elf, int index, int type, int flags, long offset, long vaddr,
            long filesz, long memsz) {
        int at = 64 + 56 * index;
        elf.putInt(at, type).putInt(at + 4, flags).putLong(at + 8, offset).putLong(at + 16, vaddr)
                .putLong(at + 24, vaddr).putLong(at + 32, filesz).putLong(at + 40, memsz).putLong(at + 48, 0x1000);
    }

    public static void writeSample(Path image) throws Exception {
        write(image, sample(), List.of("big.bin"));
    }

    /** The sample as a directory, for QEMU to serve as a FAT16 disk with fat:16:. */
    public static void writeSampleDirectory(Path dir) throws Exception {
        for (Map.Entry<String, byte[]> e : sample().entrySet()) {
            Path file = dir.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, e.getValue());
        }
    }

    private final int[] fat = new int[CLUSTERS + 2];
    private int nextCluster = 2;
    private final RandomAccessFile out;

    private FatImage(RandomAccessFile out) {
        this.out = out;
    }

    public static void write(Path image, Map<String, byte[]> files, List<String> scattered) throws Exception {
        Node root = new Node("", null);
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Node dir = root;
            String[] parts = e.getKey().split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                dir = dir.children.computeIfAbsent(parts[i], n -> new Node(n, null));
            }
            dir.children.put(parts[parts.length - 1], new Node(parts[parts.length - 1], e.getValue()));
        }
        try (RandomAccessFile file = new RandomAccessFile(image.toFile(), "rw")) {
            file.setLength(0);
            file.setLength((long) TOTAL_SECTORS * SECTOR);
            FatImage builder = new FatImage(file);
            builder.allocate(root, "", scattered);
            builder.writeTree(root, root);
            builder.writeBootSectors();
        }
    }

    private void allocate(Node node, String path, List<String> scattered) {
        int bytes = node.directory() ? directoryBytes(node) : node.contents.length;
        boolean spread = scattered.contains(path);
        for (int i = 0; i < (bytes + SECTOR - 1) / SECTOR; i++) {
            int cluster = nextCluster++;
            if (spread) {
                nextCluster++;
            }
            if (!node.clusters.isEmpty()) {
                fat[node.clusters.get(node.clusters.size() - 1)] = cluster;
            }
            node.clusters.add(cluster);
            fat[cluster] = END_OF_CHAIN;
        }
        for (Node child : node.children.values()) {
            allocate(child, path.isEmpty() ? child.name : path + "/" + child.name, scattered);
        }
    }

    private static int directoryBytes(Node dir) {
        int entries = 2;
        for (Node child : dir.children.values()) {
            entries += 1 + (shortOnly(child.name) ? 0 : longEntries(child.name));
        }
        // At least one entry stays zero, so the directory ends with an end marker.
        return (entries + 1) * 32;
    }

    private void writeTree(Node node, Node parent) throws Exception {
        if (!node.directory()) {
            writeChain(node, node.contents);
            return;
        }
        ByteBuffer dir = ByteBuffer.allocate(node.clusters.size() * SECTOR).order(ByteOrder.LITTLE_ENDIAN);
        if (!node.name.isEmpty()) {
            writeEntry(dir, ".          ".getBytes(StandardCharsets.US_ASCII), true, firstCluster(node), 0);
            // ".." names cluster 0 when the parent is the root.
            writeEntry(dir, "..         ".getBytes(StandardCharsets.US_ASCII), true,
                    parent.name.isEmpty() ? 0 : firstCluster(parent), 0);
        }
        int tilde = 1;
        for (Node child : node.children.values()) {
            byte[] shortName = shortOnly(child.name) ? pad83(child.name) : alias(child.name, tilde++);
            if (!shortOnly(child.name)) {
                writeLongName(dir, child.name, checksum(shortName));
            }
            writeEntry(dir, shortName, child.directory(), firstCluster(child),
                    child.directory() ? 0 : child.contents.length);
        }
        writeChain(node, dir.array());
        for (Node child : node.children.values()) {
            writeTree(child, node);
        }
    }

    private static int firstCluster(Node node) {
        return node.clusters.isEmpty() ? 0 : node.clusters.get(0);
    }

    private void writeChain(Node node, byte[] bytes) throws Exception {
        for (int i = 0; i < node.clusters.size(); i++) {
            int length = Math.min(SECTOR, bytes.length - i * SECTOR);
            out.seek((long) (DATA_START + node.clusters.get(i) - 2) * SECTOR);
            out.write(bytes, i * SECTOR, length);
        }
    }

    private static void writeEntry(ByteBuffer dir, byte[] name, boolean directory, int cluster, int size) {
        int at = dir.position();
        dir.put(name);
        dir.put(at + 11, (byte) (directory ? 0x10 : 0x20));
        dir.putShort(at + 20, (short) (cluster >>> 16));
        dir.putShort(at + 26, (short) cluster);
        dir.putInt(at + 28, size);
        dir.position(at + 32);
    }

    /** Long-name entries go last part first, the first of them flagged 0x40. */
    private static void writeLongName(ByteBuffer dir, String name, int checksum) {
        int count = longEntries(name);
        int[] offsets = {1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30};
        for (int seq = count; seq >= 1; seq--) {
            int at = dir.position();
            dir.put(at, (byte) (seq | (seq == count ? 0x40 : 0)));
            dir.put(at + 11, (byte) 0x0F);
            dir.put(at + 13, (byte) checksum);
            for (int i = 0; i < 13; i++) {
                int index = (seq - 1) * 13 + i;
                char c = index < name.length() ? name.charAt(index) : index == name.length() ? 0 : (char) 0xFFFF;
                dir.putShort(at + offsets[i], (short) c);
            }
            dir.position(at + 32);
        }
    }

    private static int longEntries(String name) {
        return (name.length() + 12) / 13;
    }

    private static boolean shortOnly(String name) {
        int dot = name.indexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        return name.matches("[A-Z0-9_]+(\\.[A-Z0-9_]+)?") && base.length() <= 8 && ext.length() <= 3;
    }

    private static byte[] pad83(String name) {
        int dot = name.indexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        return String.format("%-8s%-3s", base, ext).getBytes(StandardCharsets.US_ASCII);
    }

    /** HELLO~1.TXT style: up to six letters or digits of the base, then ~n, then the extension's first three. */
    private static byte[] alias(String name, int n) {
        int dot = name.lastIndexOf('.');
        String base = (dot < 0 ? name : name.substring(0, dot)).toUpperCase().replaceAll("[^A-Z0-9]", "");
        String ext = dot < 0 ? "" : name.substring(dot + 1).toUpperCase().replaceAll("[^A-Z0-9]", "");
        String tail = "~" + n;
        base = base.substring(0, Math.min(base.length(), 8 - tail.length())) + tail;
        return pad83(base + (ext.isEmpty() ? "" : "." + ext.substring(0, Math.min(3, ext.length()))));
    }

    private static int checksum(byte[] shortName) {
        int sum = 0;
        for (int i = 0; i < 11; i++) {
            sum = (((sum & 1) << 7) + (sum >> 1) + (shortName[i] & 0xFF)) & 0xFF;
        }
        return sum;
    }

    private void writeBootSectors() throws Exception {
        ByteBuffer boot = ByteBuffer.allocate(SECTOR).order(ByteOrder.LITTLE_ENDIAN);
        boot.put(new byte[] {(byte) 0xEB, 0x58, (byte) 0x90}).put("DUKE    ".getBytes(StandardCharsets.US_ASCII));
        boot.putShort(11, (short) SECTOR).put(13, (byte) 1).putShort(14, (short) RESERVED).put(16, (byte) 2);
        boot.put(21, (byte) 0xF8).putShort(24, (short) 32).putShort(26, (short) 64).putInt(32, TOTAL_SECTORS);
        boot.putInt(36, FAT_SECTORS).putInt(44, 2).putShort(48, (short) 1).putShort(50, (short) 6);
        boot.put(64, (byte) 0x80).put(66, (byte) 0x29).putInt(67, 0x0D0CE000);
        boot.position(71);
        boot.put("DUKE       FAT32   ".getBytes(StandardCharsets.US_ASCII));
        boot.putShort(510, (short) 0xAA55);
        ByteBuffer info = ByteBuffer.allocate(SECTOR).order(ByteOrder.LITTLE_ENDIAN);
        info.putInt(0, 0x41615252).putInt(484, 0x61417272).putInt(488, -1).putInt(492, -1).putInt(508, 0xAA550000);
        for (int copy : new int[] {0, 6}) {
            out.seek((long) copy * SECTOR);
            out.write(boot.array());
            out.write(info.array());
        }
        fat[0] = 0x0FFFFFF8;
        fat[1] = END_OF_CHAIN;
        ByteBuffer table = ByteBuffer.allocate(FAT_SECTORS * SECTOR).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < Math.min(fat.length, nextCluster); i++) {
            table.putInt(4 * i, fat[i]);
        }
        for (int copy = 0; copy < 2; copy++) {
            out.seek((long) (RESERVED + copy * FAT_SECTORS) * SECTOR);
            out.write(table.array());
        }
    }
}
