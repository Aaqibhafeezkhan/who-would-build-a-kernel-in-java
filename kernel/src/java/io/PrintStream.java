package java.io;

import duke.kernel.Console;

/** System.out and System.err: text straight to the console, unbuffered, so there's nothing to flush. */
public class PrintStream {

    private final boolean error;

    public PrintStream() {
        this(false);
    }

    /** Not the JDK's: System.err's stream, which a user program sends to fd 2. */
    public PrintStream(boolean error) {
        this.error = error;
    }

    public void print(String s) {
        write(String.valueOf(s), false);
    }

    public void print(Object o) {
        write(String.valueOf(o), false);
    }

    public void print(char c) {
        write(String.valueOf(c), false);
    }

    public void print(int i) {
        write(String.valueOf(i), false);
    }

    public void print(long l) {
        write(String.valueOf(l), false);
    }

    public void print(float f) {
        write(String.valueOf(f), false);
    }

    public void print(double d) {
        write(String.valueOf(d), false);
    }

    public void print(boolean b) {
        write(String.valueOf(b), false);
    }

    public void println() {
        write("", true);
    }

    public void println(String s) {
        write(String.valueOf(s), true);
    }

    public void println(Object o) {
        write(String.valueOf(o), true);
    }

    public void println(char c) {
        write(String.valueOf(c), true);
    }

    public void println(int i) {
        write(String.valueOf(i), true);
    }

    public void println(long l) {
        write(String.valueOf(l), true);
    }

    public void println(float f) {
        write(String.valueOf(f), true);
    }

    public void println(double d) {
        write(String.valueOf(d), true);
    }

    public void println(boolean b) {
        write(String.valueOf(b), true);
    }

    public void flush() {
    }

    private void write(String s, boolean newline) {
        if (error) {
            Console.printError(newline ? s + "\n" : s);
        } else if (newline) {
            Console.println(s);
        } else {
            Console.print(s);
        }
    }
}
