package duke.conformance;

final class Strings {

    static int length() {
        return "hello, world".length();
    }

    static int emptyLength() {
        return "".length();
    }

    static int charAt() {
        return "Duke".charAt(2);
    }

    static int latin1CharIsUnsigned() {
        return "\u00ff".charAt(0);
    }

    static int checksum() {
        String s = "The quick brown fox jumps over the lazy dog";
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return h;
    }

    static String isBlank() {
        return "".isBlank() + " " + " \t\n".isBlank() + " " + "\t  \t".isBlank() + " " + " a ".isBlank()
                + " " + " ".isBlank();
    }

    static String equalsIgnoreCase() {
        return "Duke".equalsIgnoreCase("dUKE") + " " + "Duke".equalsIgnoreCase("Dukes") + " "
                + "abc".equalsIgnoreCase("abd") + " " + "".equalsIgnoreCase("") + " "
                + "é".equalsIgnoreCase("É") + " " + "Duke".equalsIgnoreCase(null);
    }

    static String join(String[] parts) {
        String out = parts.length + ":";
        for (String part : parts) {
            out += "[" + part + "]";
        }
        return out;
    }

    static String split() {
        return join("a,,b,,".split(",")) + " " + join(",a".split(",")) + " " + join("a b  c".split(" ")) + " "
                + join("a::b::c".split("::")) + " " + join("abc".split(",")) + " " + join("".split(",")) + " "
                + join(",".split(","));
    }

    static String splitLimit() {
        return join("a,b,c".split(",", 2)) + " " + join("a,b,c".split(",", 1)) + " " + join("a,b,c".split(",", 10))
                + " " + join("a,,".split(",", -1)) + " " + join("a,,".split(",", 0)) + " " + join("a,,".split(",", 3));
    }
}
