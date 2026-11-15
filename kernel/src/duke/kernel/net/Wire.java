package duke.kernel.net;

/** Fields in packets, big-endian as they go on the wire, and the Internet checksum. */
final class Wire {

    private Wire() {
    }

    static int get16(byte[] b, int at) {
        return (b[at] & 0xFF) << 8 | b[at + 1] & 0xFF;
    }

    static int get32(byte[] b, int at) {
        return get16(b, at) << 16 | get16(b, at + 2);
    }

    static long get48(byte[] b, int at) {
        return (long) get16(b, at) << 32 | get32(b, at + 2) & 0xFFFF_FFFFL;
    }

    static void put16(byte[] b, int at, int value) {
        b[at] = (byte) (value >>> 8);
        b[at + 1] = (byte) value;
    }

    static void put32(byte[] b, int at, int value) {
        put16(b, at, value >>> 16);
        put16(b, at + 2, value);
    }

    static void put48(byte[] b, int at, long value) {
        put16(b, at, (int) (value >>> 32));
        put32(b, at + 2, (int) value);
    }

    /** RFC 1071: the ones' complement of the ones' complement sum of 16-bit words. */
    static int checksum(byte[] b, int at, int length) {
        return checksum(b, at, length, 0);
    }

    /** The same, with {@code sum} already added in, as for a pseudo-header. */
    static int checksum(byte[] b, int at, int length, long sum) {
        for (int i = 0; i + 1 < length; i += 2) {
            sum += get16(b, at + i);
        }
        if ((length & 1) != 0) {
            sum += (b[at + length - 1] & 0xFF) << 8;
        }
        while (sum >>> 16 != 0) {
            sum = (sum & 0xFFFF) + (sum >>> 16);
        }
        return (int) ~sum & 0xFFFF;
    }

    static String mac(long mac) {
        StringBuilder sb = new StringBuilder();
        for (int shift = 40; shift >= 0; shift -= 8) {
            int octet = (int) (mac >>> shift) & 0xFF;
            sb.append(Character.forDigit(octet >>> 4, 16)).append(Character.forDigit(octet & 0xF, 16));
            if (shift > 0) {
                sb.append(':');
            }
        }
        return sb.toString();
    }
}
