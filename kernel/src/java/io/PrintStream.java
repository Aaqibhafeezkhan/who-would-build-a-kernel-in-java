package java.io;

import duke.kernel.Console;

/** System.out: text straight to the console, unbuffered, so there's nothing to flush. */
public class PrintStream {

    public PrintStream() {
    }

    public void print(String s) {
        Console.print(s);
    }

    public void print(Object o) {
        Console.print(String.valueOf(o));
    }

    public void print(char c) {
        Console.print(String.valueOf(c));
    }

    public void print(int i) {
        Console.print(String.valueOf(i));
    }

    public void print(long l) {
        Console.print(String.valueOf(l));
    }

    public void print(boolean b) {
        Console.print(String.valueOf(b));
    }

    public void println() {
        Console.println("");
    }

    public void println(String s) {
        Console.println(s);
    }

    public void println(Object o) {
        Console.println(String.valueOf(o));
    }

    public void println(char c) {
        Console.println(String.valueOf(c));
    }

    public void println(int i) {
        Console.println(String.valueOf(i));
    }

    public void println(long l) {
        Console.println(String.valueOf(l));
    }

    public void println(boolean b) {
        Console.println(String.valueOf(b));
    }

    public void flush() {
    }
}
