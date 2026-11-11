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

    static String indexOfString() {
        return "ababa".indexOf("ba") + " " + "ababa".indexOf("z") + " " + "ababa".indexOf("")
                + " " + "".indexOf("") + " " + "abc".indexOf("c");
    }

    static String indexOfStringFromIndex() {
        return "ababa".indexOf("ba", 2) + " " + "ababa".indexOf("ba", -1) + " "
                + "ababa".indexOf("ba", 5) + " " + "ababa".indexOf("ba", 8) + " "
                + "ababa".indexOf("", -1) + " "
                + "ababa".indexOf("", 3) + " " + "ababa".indexOf("", 5) + " "
                + "ababa".indexOf("", 8);
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

    static String lastIndexOfString() {
        return "ababa".lastIndexOf("ba") + " " + "ababa".lastIndexOf("z") + " "
                + "ababa".lastIndexOf("") + " " + "".lastIndexOf("") + " "
                + "ababa".lastIndexOf("ababa") + " " + "ababa".lastIndexOf("ababaa");
    }

    static String containsString() {
        return "ababa".contains("bab") + " " + "ababa".contains("z") + " "
                + "ababa".contains("") + " " + "".contains("");
    }

    static String startsWithAtIndex() {
        return "ababa".startsWith("ab", 0) + " " + "ababa".startsWith("ba", 1) + " "
                + "ababa".startsWith("ba", 3) + " " + "ababa".startsWith("aa", 1) + " "
                + "ababa".startsWith("ba", -1) + " " + "ababa".startsWith("ba", 5) + " "
                + "ababa".startsWith("", 5) + " " + "ababa".startsWith("", 8) + " "
                + "ababa".startsWith("", -1);
    }
}
