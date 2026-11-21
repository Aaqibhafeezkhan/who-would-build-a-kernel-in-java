package duke.kernel.net;

import duke.kernel.fs.Fat;
import java.util.List;

/**
 * Serves the mounted FAT filesystem over HTTP/1.1, GET and HEAD, one request per connection. A
 * directory gets a page of links to what's in it.
 */
public final class Httpd {

    // Browsers send a few hundred bytes of headers; anything near this is something else.
    private static final int MAX_HEAD = 8192;
    // One thread answers everyone, so a client that goes quiet mustn't keep it.
    private static final long IDLE_NANOS = 5_000_000_000L;
    private static final int TOO_LONG = -1;
    private static final int INCOMPLETE = -2;

    private Httpd() {
    }

    /** Starts a thread serving on {@code port}: false if something listens there already. */
    public static boolean start(int port) {
        Tcp.Listener listener = Tcp.listen(port);
        if (listener == null) {
            return false;
        }
        new Thread(() -> {
            Tcp.Connection connection;
            while ((connection = listener.accept()) != null) {
                connection.setReadTimeout(IDLE_NANOS);
                byte[] head = new byte[MAX_HEAD];
                int length = readHead(connection, head);
                if (length == TOO_LONG) {
                    error(connection, "431 Request Header Fields Too Large");
                } else if (length != INCOMPLETE) {
                    respond(connection, requestLine(head, length));
                }
                connection.close();
            }
        }, "httpd:" + port).start();
        return true;
    }

    /** Reads up to the blank line that ends the headers: the bytes read, or TOO_LONG or INCOMPLETE. */
    private static int readHead(Tcp.Connection connection, byte[] head) {
        int length = 0;
        while (length < head.length) {
            int n = connection.read(head, length, head.length - length);
            if (n <= 0) {
                return INCOMPLETE;
            }
            for (int i = Math.max(0, length - 2); i < length + n; i++) {
                // RFC 9112 lets a server take a bare LF as the end of a line.
                if (head[i] == '\n' && (i + 1 < length + n && head[i + 1] == '\n'
                        || i + 2 < length + n && head[i + 1] == '\r' && head[i + 2] == '\n')) {
                    return length + n;
                }
            }
            length += n;
        }
        return TOO_LONG;
    }

    private static String requestLine(byte[] head, int length) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < length && head[i] != '\n'; i++) {
            if (head[i] != '\r') {
                line.append((char) (head[i] & 0xFF));
            }
        }
        return line.toString();
    }

    private static void respond(Tcp.Connection connection, String line) {
        int firstSpace = line.indexOf(' ');
        int lastSpace = line.lastIndexOf(' ');
        if (firstSpace <= 0 || lastSpace == firstSpace || !line.startsWith("HTTP/1.", lastSpace + 1)) {
            error(connection, "400 Bad Request");
            return;
        }
        String method = line.substring(0, firstSpace);
        String target = line.substring(firstSpace + 1, lastSpace);
        boolean head = method.equals("HEAD");
        if (!head && !method.equals("GET")) {
            send(connection, "405 Method Not Allowed", "Allow: GET, HEAD\r\n", "text/plain", utf8("405 Method Not Allowed\n"), false);
            return;
        }
        int end = target.length();
        for (int i = 0; i < target.length(); i++) {
            if (target.charAt(i) == '?' || target.charAt(i) == '#') {
                end = i;
                break;
            }
        }
        String path = target.startsWith("/") ? decode(target.substring(0, end)) : null;
        if (path == null) {
            error(connection, "400 Bad Request");
            return;
        }
        Fat fs = Fat.mounted();
        if (fs == null) {
            error(connection, "503 Service Unavailable");
            return;
        }
        Fat.Entry entry;
        byte[] contents;
        try {
            entry = fs.lookup(path);
            if (entry.directory && !path.endsWith("/")) {
                // Without the slash, the page's relative links would resolve against the parent.
                send(connection, "301 Moved Permanently", "Location: " + target.substring(0, end) + "/\r\n", "text/plain",
                        utf8("301 Moved Permanently\n"), head);
                return;
            }
            contents = entry.directory ? listing(path, fs.list(path)) : fs.read(path);
        } catch (IllegalArgumentException e) {
            error(connection, "404 Not Found");
            return;
        } catch (IllegalStateException e) {
            error(connection, "500 Internal Server Error");
            return;
        }
        send(connection, "200 OK", "", entry.directory ? "text/html; charset=utf-8" : type(path), contents, head);
    }

    /** {@code path} with its %XX escapes decoded as UTF-8, or null if one is malformed or decodes to NUL. */
    private static String decode(String path) {
        byte[] bytes = new byte[path.length()];
        int n = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '%') {
                int high = i + 2 < path.length() ? Character.digit(path.charAt(i + 1), 16) : -1;
                int low = high >= 0 ? Character.digit(path.charAt(i + 2), 16) : -1;
                if (low < 0 || high == 0 && low == 0) {
                    return null;
                }
                bytes[n++] = (byte) (high << 4 | low);
                i += 2;
            } else if (c >= 0x80) {
                return null;
            } else {
                bytes[n++] = (byte) c;
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; ) {
            int b = bytes[i] & 0xFF;
            int length = b < 0x80 ? 1 : b >= 0xC2 && b < 0xE0 ? 2 : b >= 0xE0 && b < 0xF0 ? 3 : b >= 0xF0 && b < 0xF5 ? 4 : 0;
            if (length == 0 || i + length > n) {
                return null;
            }
            int codePoint = length == 1 ? b : b & (0x7F >> length);
            for (int k = 1; k < length; k++) {
                int next = bytes[i + k] & 0xFF;
                if ((next & 0xC0) != 0x80) {
                    return null;
                }
                codePoint = codePoint << 6 | next & 0x3F;
            }
            i += length;
            if (codePoint >= 0x10000) {
                out.append((char) (0xD800 + (codePoint - 0x10000 >> 10))).append((char) (0xDC00 + (codePoint & 0x3FF)));
            } else {
                out.append((char) codePoint);
            }
        }
        return out.toString();
    }

    private static byte[] listing(String path, List<Fat.Entry> entries) {
        StringBuilder page = new StringBuilder("<!doctype html>\n<title>Index of ");
        escape(page, path);
        page.append("</title>\n<h1>Index of ");
        escape(page, path);
        page.append("</h1>\n<ul>\n");
        for (Fat.Entry e : entries) {
            if (e.name.equals(".")) {
                continue;
            }
            String name = e.directory ? e.name + "/" : e.name;
            page.append("<li><a href=\"");
            for (byte b : utf8(name)) {
                int c = b & 0xFF;
                if (c <= ' ' || c == '"' || c == '#' || c == '%' || c == '?' || c == '&' || c == '<' || c == '>' || c >= 0x7F) {
                    page.append('%').append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 0xF, 16));
                } else {
                    page.append((char) c);
                }
            }
            page.append("\">");
            escape(page, name);
            page.append("</a>\n");
        }
        return utf8(page.append("</ul>\n").toString());
    }

    private static void escape(StringBuilder out, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                default -> out.append(c);
            }
        }
    }

    private static String type(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot > path.lastIndexOf('/') ? path.substring(dot + 1).toLowerCase() : "";
        return switch (extension) {
            case "txt", "md" -> "text/plain; charset=utf-8";
            case "html", "htm" -> "text/html; charset=utf-8";
            case "css" -> "text/css";
            case "js" -> "text/javascript";
            case "json" -> "application/json";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "svg" -> "image/svg+xml";
            default -> "application/octet-stream";
        };
    }

    private static void error(Tcp.Connection connection, String status) {
        send(connection, status, "", "text/plain", utf8(status + "\n"), false);
    }

    private static void send(Tcp.Connection connection, String status, String headers, String type, byte[] contents, boolean head) {
        byte[] header = utf8("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + contents.length
                + "\r\n" + headers + "Connection: close\r\n\r\n");
        if (connection.write(header, 0, header.length) == header.length && !head) {
            connection.write(contents, 0, contents.length);
        }
    }

    /** {@code text} in UTF-8: file names come from FAT's long names, which are UTF-16. */
    private static byte[] utf8(String text) {
        byte[] bytes = new byte[text.length() * 3];
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            int c = text.charAt(i);
            if (c >= 0xD800 && c < 0xDC00 && i + 1 < text.length() && text.charAt(i + 1) >= 0xDC00 && text.charAt(i + 1) < 0xE000) {
                c = 0x10000 + ((c - 0xD800) << 10) + (text.charAt(++i) - 0xDC00);
            }
            if (c < 0x80) {
                bytes[n++] = (byte) c;
            } else if (c < 0x800) {
                bytes[n++] = (byte) (0xC0 | c >> 6);
                bytes[n++] = (byte) (0x80 | c & 0x3F);
            } else if (c < 0x10000) {
                bytes[n++] = (byte) (0xE0 | c >> 12);
                bytes[n++] = (byte) (0x80 | c >> 6 & 0x3F);
                bytes[n++] = (byte) (0x80 | c & 0x3F);
            } else {
                bytes[n++] = (byte) (0xF0 | c >> 18);
                bytes[n++] = (byte) (0x80 | c >> 12 & 0x3F);
                bytes[n++] = (byte) (0x80 | c >> 6 & 0x3F);
                bytes[n++] = (byte) (0x80 | c & 0x3F);
            }
        }
        byte[] out = new byte[n];
        System.arraycopy(bytes, 0, out, 0, n);
        return out;
    }
}
