package duke.conformance;

final class StringConcat {

    static final class Thing {
        @Override
        public String toString() {
            return "thing";
        }
    }

    static String everyPrimitiveType() {
        int i = -7;
        long l = 1L << 40;
        char c = 'q';
        boolean b = true;
        byte y = (byte) 200;
        short s = (short) 40000;
        return "i=" + i + " l=" + l + " c=" + c + " b=" + b + " y=" + y + " s=" + s;
    }

    static String objectsAndNulls() {
        Object thing = new Thing();
        String none = null;
        Object nothing = null;
        return thing + "/" + none + "/" + nothing;
    }

    static String compoundAssignmentInLoop() {
        String s = "";
        for (int i = 0; i < 10; i++) {
            s += i;
        }
        return s;
    }

    static String arithmeticVersusConcatenation() {
        return 1 + 2 + "x" + 1 + 2;
    }

    static String charsAreNotNumbers() {
        char a = 'a';
        return "" + a + 'b' + (char) (a + 2);
    }

    static String builderOperations() {
        StringBuilder sb = new StringBuilder("duke");
        sb.append(' ').append(42).append(' ').append(false);
        sb.setCharAt(0, 'D');
        String forward = sb.toString();
        sb.reverse();
        String backward = sb.toString();
        sb.setLength(3);
        return forward + "|" + backward + "|" + sb + "|" + sb.length() + sb.charAt(1);
    }

    static String builderInsertAndDelete() {
        StringBuilder sb = new StringBuilder("duke 42");
        sb.insert(4, '!').insert(0, '>').insert(sb.length(), '?');
        String inserted = sb.toString();
        sb.deleteCharAt(0).deleteCharAt(5).deleteCharAt(sb.length() - 1);
        return inserted + "|" + sb + "|" + sb.length();
    }

    static String builderInsertString() {
        StringBuilder sb = new StringBuilder(2).append("duke");
        sb.insert(2, "-kernel").insert(0, (String) null).insert(sb.length(), "!");
        return sb + "|" + sb.length();
    }

    static String builderInsertStringIndexMessages() {
        StringBuilder sb = new StringBuilder();
        try {
            new StringBuilder("abc").insert(5, "x");
        } catch (StringIndexOutOfBoundsException e) {
            sb.append(e.getMessage()).append(';');
        }
        try {
            new StringBuilder("abc").insert(-1, "x");
        } catch (StringIndexOutOfBoundsException e) {
            sb.append(e.getMessage());
        }
        return sb.toString();
    }

    static String builderDelete() {
        StringBuilder sb = new StringBuilder("0123456789");
        sb.delete(2, 5).delete(3, 99).delete(1, 1);
        return sb + "|" + sb.length();
    }

    static String builderDeleteIndexMessages() {
        StringBuilder sb = new StringBuilder();
        try {
            new StringBuilder("abc").delete(-1, 2);
        } catch (StringIndexOutOfBoundsException e) {
            sb.append(e.getMessage()).append(';');
        }
        try {
            new StringBuilder("abc").delete(4, 4);
        } catch (StringIndexOutOfBoundsException e) {
            sb.append(e.getMessage()).append(';');
        }
        try {
            new StringBuilder("abc").delete(2, 1);
        } catch (StringIndexOutOfBoundsException e) {
            sb.append(e.getMessage());
        }
        return sb.toString();
    }

    static String builderIndexOf() {
        StringBuilder sb = new StringBuilder("ababa");
        return sb.indexOf("ba") + " " + sb.indexOf("z") + " " + sb.indexOf("") + " "
                + new StringBuilder().indexOf("") + " " + sb.indexOf("aba");
    }

    static String builderIndexMessages() {
        StringBuilder sb = new StringBuilder();
        try {
            new StringBuilder("abc").insert(5, 'x');
        } catch (StringIndexOutOfBoundsException e) {
            sb.append(e.getMessage()).append(';');
        }
        try {
            new StringBuilder("abc").insert(-1, 'x');
        } catch (StringIndexOutOfBoundsException e) {
            sb.append(e.getMessage()).append(';');
        }
        try {
            new StringBuilder("abc").deleteCharAt(3);
        } catch (StringIndexOutOfBoundsException e) {
            sb.append(e.getMessage()).append(';');
        }
        try {
            new StringBuilder("abc").deleteCharAt(-1);
        } catch (StringIndexOutOfBoundsException e) {
            sb.append(e.getMessage());
        }
        return sb.toString();
    }

    static int builderGrowsPastCapacity() {
        StringBuilder sb = new StringBuilder(1);
        for (int i = 0; i < 500; i++) {
            sb.append((char) ('a' + i % 26));
        }
        return sb.length() * 1000 + sb.toString().hashCode() % 1000;
    }

    static String nestedConcatenation() {
        String inner = "[" + 1 + "]";
        return "<" + inner + inner + ">";
    }
}
