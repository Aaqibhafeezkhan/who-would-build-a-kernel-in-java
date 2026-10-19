package duke.user;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A little of everything the runtime does, now outside the kernel: strings, collections, exceptions, statics. */
class Basics {

    private static final List<String> NAMES = names("duke", "kernel", "ring 3");
    private static int initialized = 1;

    interface Shape {
        long area();
    }

    static final class Square implements Shape {
        private final long side;

        Square(long side) {
            this.side = side;
        }

        public long area() {
            return side * side;
        }

        @Override
        public String toString() {
            return "Square[side=" + side + "]";
        }
    }

    private static List<String> names(String... names) {
        List<String> list = new ArrayList<>();
        for (String name : names) {
            list.add(name);
        }
        return list;
    }

    static void main() {
        StringBuilder sb = new StringBuilder();
        for (String name : NAMES) {
            sb.append(name.length()).append(name.charAt(0)).append(',');
        }
        System.out.println(sb);

        Map<String, Integer> lengths = new HashMap<>();
        for (String name : NAMES) {
            lengths.put(name, name.length());
        }
        System.out.println(lengths.get("kernel") + " " + lengths.containsKey("hotspot"));

        Shape shape = new Square(12);
        System.out.println("area " + shape.area() + " of " + shape);

        try {
            Object o = NAMES.get(7);
            System.out.println("no exception: " + o);
        } catch (IndexOutOfBoundsException e) {
            System.out.println("caught " + e.getClass().getName());
        }

        long fib = 0;
        long next = 1;
        for (int i = 0; i < 80; i++) {
            long t = fib + next;
            fib = next;
            next = t;
        }
        System.out.println("fib(80) = " + fib + ", initialized " + initialized);
        System.out.println('é' + " and " + Long.MIN_VALUE);
    }
}
