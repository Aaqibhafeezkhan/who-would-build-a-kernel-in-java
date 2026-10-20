package duke.user;

class Crash {
    static void main() {
        System.out.println("to stdout");
        System.err.println("to stderr");
        System.err.print('!');
        System.err.println();
        fail(2);
    }

    static void fail(int depth) {
        if (depth == 0) {
            throw new IllegalStateException("deep enough");
        }
        fail(depth - 1);
    }
}
