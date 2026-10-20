package duke.kernel;

import duke.rt.Magic;
import duke.rt.Tib;

/** Standard output through write(2), each call one write so lines don't interleave. UTF-8, as HotSpot prints. */
public final class Console {

    private static final int WRITE = 1;
    static final int STDOUT = 1;
    static final int STDERR = 2;

    private Console() {
    }

    public static void write(int c) {
        print(String.valueOf((char) c));
    }

    public static void print(String s) {
        writeTo(STDOUT, s == null ? "null" : s, false);
    }

    public static void println(String s) {
        writeTo(STDOUT, s == null ? "null" : s, true);
    }

    public static void printError(String s) {
        writeTo(STDERR, s, false);
    }

    public static void print(long value) {
        print(String.valueOf(value));
    }

    static void writeTo(int fd, String s, boolean newline) {
        byte[] bytes = new byte[s.length() * 3 + 1];
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                bytes[n++] = (byte) c;
            } else if (c < 0x800) {
                bytes[n++] = (byte) (0xC0 | c >> 6);
                bytes[n++] = (byte) (0x80 | c & 0x3F);
            } else {
                bytes[n++] = (byte) (0xE0 | c >> 12);
                bytes[n++] = (byte) (0x80 | c >> 6 & 0x3F);
                bytes[n++] = (byte) (0x80 | c & 0x3F);
            }
        }
        if (newline) {
            bytes[n++] = '\n';
        }
        long data = Magic.addressOf(bytes) + Tib.ARRAY_DATA;
        for (int done = 0; done < n; ) {
            long written = Magic.syscall(WRITE, fd, data + done, n - done, 0, 0, 0);
            if (written <= 0) {
                return;
            }
            done += (int) written;
        }
    }
}
