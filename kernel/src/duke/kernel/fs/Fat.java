package duke.kernel.fs;

import duke.kernel.Console;
import duke.kernel.virtio.VirtioBlock;
import java.util.ArrayList;
import java.util.List;

/**
 * A read-only FAT32 (and FAT16) filesystem on a virtio disk, either the whole disk or the first
 * FAT partition in its MBR. Paths are {@code /}-separated and match long or short names, ignoring
 * case the way FAT does.
 */
public final class Fat {

    public static final class Entry {
        public final String name;
        public final boolean directory;
        public final long size;
        final long cluster;

        Entry(String name, boolean directory, long size, long cluster) {
            this.name = name;
            this.directory = directory;
            this.size = size;
            this.cluster = cluster;
        }
    }

    private static final int SECTOR = VirtioBlock.SECTOR_SIZE;
    private static final int ENTRY_LENGTH = 32;
    private static final int ATTR_VOLUME_LABEL = 0x08;
    private static final int ATTR_DIRECTORY = 0x10;
    private static final int ATTR_LONG_NAME = 0x0F;
    private static final int DELETED = 0xE5;
    private static final int LOWERCASE_BASE = 0x08;
    private static final int LOWERCASE_EXTENSION = 0x10;

    private static Fat mounted;

    private final VirtioBlock disk;
    private final boolean fat32;
    private final int sectorsPerCluster;
    private final long fatStart;
    private final long rootStart;
    private final int rootSectors;
    private final long dataStart;
    private final long rootCluster;
    private final long clusters;
    private final byte[] fatSector = new byte[SECTOR];
    private long fatSectorNumber = -1;

    private Fat(VirtioBlock disk, long volume, byte[] boot) {
        this.disk = disk;
        sectorsPerCluster = boot[13] & 0xFF;
        int reserved = u16(boot, 14);
        int fats = boot[16] & 0xFF;
        int rootEntries = u16(boot, 17);
        long totalSectors = u16(boot, 19) != 0 ? u16(boot, 19) : u32(boot, 32);
        long fatSize = u16(boot, 22) != 0 ? u16(boot, 22) : u32(boot, 36);
        rootSectors = (rootEntries * ENTRY_LENGTH + SECTOR - 1) / SECTOR;
        fatStart = volume + reserved;
        rootStart = fatStart + fats * fatSize;
        dataStart = rootStart + rootSectors;
        clusters = (totalSectors - (dataStart - volume)) / sectorsPerCluster;
        // A 16-bit FAT size of 0 means the FAT32 layout. The spec goes by cluster count alone, but
        // QEMU's fat:32: disks are FAT32 with too few clusters for that, and Linux checks this field too.
        fat32 = u16(boot, 22) == 0;
        if (!fat32 && clusters < 4085) {
            throw new IllegalStateException("FAT12 isn't supported");
        }
        rootCluster = fat32 ? u32(boot, 44) : 0;
    }

    /** Mounts the first virtio disk that holds a FAT filesystem. */
    public static void init() {
        for (VirtioBlock disk : VirtioBlock.devices()) {
            try {
                mounted = mount(disk);
            } catch (IllegalStateException e) {
                Console.println("fat: " + disk.function().address() + ": " + e.getMessage());
            }
            if (mounted != null) {
                return;
            }
        }
    }

    /** The mounted filesystem, or null if no disk had one. */
    public static Fat mounted() {
        return mounted;
    }

    /** The filesystem on {@code disk}, or null if there isn't one. */
    public static Fat mount(VirtioBlock disk) {
        if (disk.capacity() == 0) {
            return null;
        }
        byte[] sector = new byte[SECTOR];
        disk.read(0, sector);
        if (bootSector(sector)) {
            return new Fat(disk, 0, sector);
        }
        if (u16(sector, 510) != 0xAA55) {
            return null;
        }
        for (int i = 0; i < 4; i++) {
            int entry = 446 + 16 * i;
            if (fatPartition(sector[entry + 4] & 0xFF)) {
                long start = u32(sector, entry + 8);
                byte[] boot = new byte[SECTOR];
                disk.read(start, boot);
                return bootSector(boot) ? new Fat(disk, start, boot) : null;
            }
        }
        return null;
    }

    public boolean fat32() {
        return fat32;
    }

    public VirtioBlock disk() {
        return disk;
    }

    /**
     * A number for stat's st_ino and getdents' d_ino: the first cluster, which no two files or
     * directories share. Empty files have none, so they share one that's never a cluster, since a
     * d_ino of 0 reads as a deleted entry.
     */
    public long inode(Entry e) {
        if (e.cluster != 0) {
            return e.cluster;
        }
        return e.directory ? Math.max(rootCluster, 1) : 0x0FFFFFFF;
    }

    /** What getdents64 lists for the directory at {@code path}: its entries, with "." and ".." first in the root too. */
    public List<Entry> listing(String path) {
        Entry dir = lookup(path);
        if (!dir.directory) {
            throw new IllegalArgumentException(path + " is not a directory");
        }
        List<Entry> out = new ArrayList<>();
        if (dir.cluster == rootCluster) {
            // The root has no "." or ".." of its own on disk.
            out.add(new Entry(".", true, 0, rootCluster));
            out.add(new Entry("..", true, 0, rootCluster));
        }
        out.addAll(entries(dir.cluster));
        return out;
    }

    /** The entries of the directory at {@code path}. */
    public List<Entry> list(String path) {
        Entry dir = lookup(path);
        if (!dir.directory) {
            throw new IllegalArgumentException(path + " is not a directory");
        }
        return entries(dir.cluster);
    }

    public byte[] read(String path) {
        Entry file = lookup(path);
        if (file.directory) {
            throw new IllegalArgumentException(path + " is a directory");
        }
        byte[] contents = new byte[(int) file.size];
        byte[] buffer = new byte[sectorsPerCluster * SECTOR];
        long cluster = file.cluster;
        for (int done = 0; done < contents.length; done += buffer.length) {
            if (!valid(cluster)) {
                throw new IllegalStateException(path + ": cluster chain ends early");
            }
            disk.read(clusterSector(cluster), buffer);
            System.arraycopy(buffer, 0, contents, done, Math.min(buffer.length, contents.length - done));
            cluster = next(cluster);
        }
        return contents;
    }

    public Entry lookup(String path) {
        Entry current = new Entry("/", true, 0, rootCluster);
        for (String part : split(path)) {
            if (part.equals(".")) {
                continue;
            }
            if (!current.directory) {
                throw new IllegalArgumentException(current.name + " is not a directory");
            }
            Entry found = null;
            for (Entry e : entries(current.cluster)) {
                if (sameName(e.name, part)) {
                    found = e;
                    break;
                }
            }
            if (found == null) {
                throw new IllegalArgumentException(path + ": no such file or directory");
            }
            // ".." stores cluster 0 when it points at the root. An empty file has cluster 0 too.
            current = found.directory && found.cluster == 0 ? new Entry("/", true, 0, rootCluster) : found;
        }
        return current;
    }

    private static List<String> split(String path) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= path.length(); i++) {
            if (i == path.length() || path.charAt(i) == '/') {
                if (i > start) {
                    parts.add(path.substring(start, i));
                }
                start = i + 1;
            }
        }
        return parts;
    }

    private static boolean sameName(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        for (int i = 0; i < a.length(); i++) {
            if (Character.toLowerCase(a.charAt(i)) != Character.toLowerCase(b.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Cluster 0 means the root directory, which FAT16 keeps in a fixed region before the data. */
    private List<Entry> entries(long cluster) {
        List<Entry> out = new ArrayList<>();
        Names names = new Names();
        if (cluster == 0 && !fat32) {
            byte[] root = new byte[rootSectors * SECTOR];
            disk.read(rootStart, root);
            parse(root, names, out);
            return out;
        }
        byte[] buffer = new byte[sectorsPerCluster * SECTOR];
        for (long c = cluster == 0 ? rootCluster : cluster; valid(c); c = next(c)) {
            disk.read(clusterSector(c), buffer);
            if (!parse(buffer, names, out)) {
                break;
            }
        }
        return out;
    }

    /** False once the end-of-directory marker turns up. */
    private static boolean parse(byte[] block, Names names, List<Entry> out) {
        for (int at = 0; at < block.length; at += ENTRY_LENGTH) {
            int first = block[at] & 0xFF;
            if (first == 0) {
                return false;
            }
            int attributes = block[at + 11] & 0xFF;
            if (first == DELETED) {
                names.clear();
            } else if ((attributes & 0x3F) == ATTR_LONG_NAME) {
                names.add(block, at);
            } else if ((attributes & ATTR_VOLUME_LABEL) != 0) {
                names.clear();
            } else {
                String name = names.take(block, at);
                if (name == null) {
                    name = shortName(block, at);
                }
                long cluster = (long) u16(block, at + 20) << 16 | u16(block, at + 26);
                out.add(new Entry(name, (attributes & ATTR_DIRECTORY) != 0, u32(block, at + 28), cluster));
            }
        }
        return true;
    }

    /** "README  TXT" as README.TXT, lowercased where Windows NT's case flags say so. */
    private static String shortName(byte[] block, int at) {
        int flags = block[at + 12] & 0xFF;
        StringBuilder sb = new StringBuilder();
        appendTrimmed(sb, block, at, 8, (flags & LOWERCASE_BASE) != 0);
        if (block[at + 8] != ' ') {
            sb.append('.');
            appendTrimmed(sb, block, at + 8, 3, (flags & LOWERCASE_EXTENSION) != 0);
        }
        return sb.toString();
    }

    private static void appendTrimmed(StringBuilder sb, byte[] block, int at, int length, boolean lower) {
        int end = length;
        while (end > 0 && block[at + end - 1] == ' ') {
            end--;
        }
        for (int i = 0; i < end; i++) {
            char c = (char) (i == 0 && (block[at] & 0xFF) == 0x05 ? 0xE5 : block[at + i] & 0xFF);
            sb.append(lower ? Character.toLowerCase(c) : c);
        }
    }

    /**
     * Long-name entries come before their short entry, last part first. Each holds 13 UTF-16
     * characters and a checksum of the short name, so a stale long name can't attach itself to a
     * file that was renamed by something that only knew short names.
     */
    private static final class Names {
        private static final int[] NAME_OFFSETS = {1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30};
        private final char[] chars = new char[20 * 13];
        private int length = -1;
        private int checksum;

        void clear() {
            length = -1;
        }

        void add(byte[] block, int at) {
            int sequence = block[at] & 0x1F;
            if ((block[at] & 0x40) != 0) {
                length = 13 * sequence;
                checksum = block[at + 13] & 0xFF;
            }
            if (length < 0 || sequence == 0 || 13 * sequence > chars.length || (block[at + 13] & 0xFF) != checksum) {
                length = -1;
                return;
            }
            int base = 13 * (sequence - 1);
            for (int i = 0; i < 13; i++) {
                char c = (char) u16(block, at + NAME_OFFSETS[i]);
                chars[base + i] = c;
                if (c == 0 && base + i < length) {
                    length = base + i;
                }
            }
        }

        /** The long name for the short entry at {@code at}, or null if there isn't a valid one. */
        String take(byte[] block, int at) {
            int sum = 0;
            for (int i = 0; i < 11; i++) {
                sum = ((sum & 1) << 7) + (sum >> 1) + (block[at + i] & 0xFF) & 0xFF;
            }
            if (length <= 0 || sum != checksum) {
                length = -1;
                return null;
            }
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < length; i++) {
                name.append(chars[i]);
            }
            length = -1;
            return name.toString();
        }
    }

    private long clusterSector(long cluster) {
        return dataStart + (cluster - 2) * sectorsPerCluster;
    }

    private boolean valid(long cluster) {
        return cluster >= 2 && cluster < clusters + 2;
    }

    // Synchronized for fatSector, which every caller shares.
    private synchronized long next(long cluster) {
        long offset = cluster * (fat32 ? 4 : 2);
        long sector = fatStart + offset / SECTOR;
        if (sector != fatSectorNumber) {
            disk.read(sector, fatSector);
            fatSectorNumber = sector;
        }
        int at = (int) (offset % SECTOR);
        return fat32 ? u32(fatSector, at) & 0x0FFF_FFFF : u16(fatSector, at);
    }

    private static boolean bootSector(byte[] s) {
        int jump = s[0] & 0xFF;
        int perCluster = s[13] & 0xFF;
        return (jump == 0xEB || jump == 0xE9) && u16(s, 11) == SECTOR && perCluster != 0
                && (perCluster & (perCluster - 1)) == 0 && s[16] != 0 && u16(s, 510) == 0xAA55;
    }

    /** FAT12/16 types 0x01, 0x04, 0x06, 0x0E and FAT32 types 0x0B, 0x0C. */
    private static boolean fatPartition(int type) {
        return type == 0x01 || type == 0x04 || type == 0x06 || type == 0x0B || type == 0x0C || type == 0x0E;
    }

    static int u16(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
    }

    static long u32(byte[] b, int at) {
        return (u16(b, at) | (long) u16(b, at + 2) << 16) & 0xFFFF_FFFFL;
    }
}
