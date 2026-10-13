package duke.kernel;

import duke.kernel.acpi.Madt;
import duke.kernel.fs.Fat;
import duke.kernel.mm.PhysicalMemory;
import duke.kernel.pci.Pci;
import duke.kernel.time.Timer;
import duke.kernel.user.Elf;
import duke.kernel.user.Process;
import duke.kernel.user.UserMode;
import duke.rt.Heap;
import java.util.ArrayList;
import java.util.List;

/** A line-editing shell over every input device. */
public final class Shell {

    public static final String PROMPT = "duke> ";

    private Shell() {
    }

    public static void run() {
        while (true) {
            Console.print(PROMPT);
            String line = readLine();
            try {
                execute(line.strip());
            } catch (RuntimeException e) {
                Console.println("error: " + e);
            }
        }
    }

    /** A line typed at the console, edited and echoed as it's typed, without its newline. */
    public static String readLine() {
        StringBuilder line = new StringBuilder();
        int pos = 0;

        while (true) {
            int c = Input.take();
            if (c == '\n') {
                Console.println("");
                return line.toString();
            }

            switch (c) {
                case Input.KEY_LEFT -> {
                    if (pos > 0) {
                        pos--;
                        Console.print("\b");
                    }
                }
                case Input.KEY_RIGHT -> {
                    if (pos < line.length()) {
                        Console.write(line.charAt(pos));
                        pos++;
                    }
                }
                case Input.KEY_HOME -> {
                    while (pos > 0) {
                        pos--;
                        Console.print("\b");
                    }
                }
                case Input.KEY_END -> {
                    while (pos < line.length()) {
                        Console.write(line.charAt(pos));
                        pos++;
                    }
                }
                case Input.KEY_DELETE -> {
                    if (pos < line.length()) {
                        line.deleteCharAt(pos);
                        redrawFrom(line, pos);
                    }
                }
                case '\b', 0x7F -> {
                    if (pos > 0) {
                        line.deleteCharAt(pos - 1);
                        pos--;
                        Console.print("\b");
                        redrawFrom(line, pos);
                    }
                }
                default -> {
                    if (c >= ' ' && c < 0x7F) {
                        line.insert(pos, (char) c);
                        pos++;
                        Console.write(c);
                        redrawFrom(line, pos);
                    }
                }
            }
        }
    }

    private static void redrawFrom(StringBuilder line, int pos) {
        for (int i = pos; i < line.length(); i++) {
            Console.write(line.charAt(i));
        }
        Console.print(" ");
        int backSteps = line.length() - pos + 1;
        for (int i = 0; i < backSteps; i++) {
            Console.print("\b");
        }
    }

    static void execute(String line) {
        List<String> words = split(line);
        if (words.isEmpty()) {
            return;
        }
        switch (words.get(0)) {
            case "help" -> {
                Console.println("commands: help, uptime, mem, gc, cpus, threads, pci, ls [path], cat <path>, run <path> [args], echo <text>, clear, panic");
                if (!Commands.names().isEmpty()) {
                    StringBuilder more = new StringBuilder("also:");
                    for (int i = 0; i < Commands.names().size(); i++) {
                        more.append(i == 0 ? " " : ", ").append(Commands.names().get(i));
                    }
                    Console.println(more.toString());
                }
            }
            case "clear" -> FramebufferConsole.clear();
            case "uptime" -> Console.println("up " + Timer.uptimeMillis() + " ms (" + Timer.ticks() + " ticks)");
            case "mem" -> Console.println("frames: " + PhysicalMemory.freeFrames() + " free ("
                    + (PhysicalMemory.freeFrames() * PhysicalMemory.PAGE_SIZE >> 20) + " MiB); heap: "
                    + (Heap.used() >> 10) + " KiB used, " + (Heap.committed() >> 10) + " KiB committed");
            case "gc" -> {
                System.gc();
                Console.println("collections: " + Heap.collections() + ", live after last: " + (Heap.lastLive() >> 10) + " KiB");
            }
            case "cpus" -> {
                for (Madt.LocalApic cpu : Madt.cpus()) {
                    Console.println("cpu " + cpu.processorId + ": apic " + cpu.apicId + (cpu.enabled ? "" : " (disabled)"));
                }
            }
            case "threads" -> Scheduler.list();
            case "pci" -> {
                for (Pci.Function f : Pci.functions()) {
                    Console.println(f.describe());
                }
            }
            case "ls" -> ls(words.size() > 1 ? words.get(1) : "/");
            case "cat" -> {
                if (words.size() < 2) {
                    Console.println("usage: cat <path>");
                } else {
                    cat(words.get(1));
                }
            }
            case "run" -> {
                if (words.size() < 2) {
                    Console.println("usage: run <path> [args]");
                } else {
                    String[] argv = new String[words.size() - 1];
                    for (int i = 0; i < argv.length; i++) {
                        argv[i] = words.get(i + 1);
                    }
                    run(argv);
                }
            }
            case "echo" -> Console.println(line.substring(4).strip());
            case "panic" -> Panic.panic("requested from the shell");
            default -> {
                if (!Commands.run(words)) {
                    Console.println("unknown command: " + words.get(0) + " (try help)");
                }
            }
        }
    }

    private static void ls(String path) {
        Fat fs = Fat.mounted();
        if (fs == null) {
            Console.println("ls: no filesystem mounted");
            return;
        }
        try {
            for (Fat.Entry e : fs.list(path)) {
                if (!e.name.equals(".") && !e.name.equals("..")) {
                    Console.println(e.directory ? e.name + "/" : e.name + "  " + e.size);
                }
            }
        } catch (IllegalArgumentException e) {
            Console.println("ls: " + e.getMessage());
        }
    }

    /** Runs a static executable from the disk as a process, and says how it ended unless it exited 0. */
    private static void run(String[] argv) {
        String path = argv[0];
        Fat fs = Fat.mounted();
        if (fs == null) {
            Console.println("run: no filesystem mounted");
            return;
        }
        Elf.Image image;
        try {
            image = Elf.load(fs.read(path), argv);
        } catch (IllegalArgumentException e) {
            Console.println("run: " + path + ": " + e.getMessage());
            return;
        }
        try {
            long status = Process.start(path, null, image).waitFor();
            if (status != 0) {
                Console.println(path + ": exit status " + status);
            }
        } catch (UserMode.Fault f) {
            Console.println(path + ": " + f.getMessage());
        } catch (InterruptedException e) {
            Console.println(path + ": interrupted");
        }
    }

    private static void cat(String path) {
        Fat fs = Fat.mounted();
        if (fs == null) {
            Console.println("cat: no filesystem mounted");
            return;
        }
        byte[] contents;
        try {
            contents = fs.read(path);
        } catch (IllegalArgumentException e) {
            Console.println("cat: " + e.getMessage());
            return;
        }
        StringBuilder text = new StringBuilder();
        for (byte b : contents) {
            text.append((char) (b & 0xFF));
        }
        Console.print(text.toString());
        if (contents.length > 0 && contents[contents.length - 1] != '\n') {
            Console.println("");
        }
    }

    /** Words split on spaces, except inside double quotes, which make one word without the quotes. */
    private static List<String> split(String line) {
        List<String> words = new ArrayList<>();
        StringBuilder word = null;
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                if (word == null) {
                    word = new StringBuilder();
                }
            } else if (c == ' ' && !quoted) {
                if (word != null) {
                    words.add(word.toString());
                    word = null;
                }
            } else {
                if (word == null) {
                    word = new StringBuilder();
                }
                word.append(c);
            }
        }
        if (word != null) {
            words.add(word.toString());
        }
        return words;
    }
}
