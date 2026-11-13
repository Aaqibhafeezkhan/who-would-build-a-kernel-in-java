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

    /**
     * A line typed at the console, edited and echoed as it's typed, without its newline. Null if
     * the calling process is killed while it waits.
     */
    public static String readLine() {
        StringBuilder line = new StringBuilder();
        int pos = 0;

        while (true) {
            int c = Input.take();
            if (c == Input.KILLED) {
                return null;
            }
            if (c == '\n') {
                Console.println("");
                return line.toString();
            }
            if (c == Input.CTRL_C) {
                Console.println("^C");
                return "";
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
        List<Boolean> quotedWords = new ArrayList<>();
        List<String> words = split(line, quotedWords);
        if (words.isEmpty()) {
            return;
        }
        switch (words.get(0)) {
            case "help" -> {
                Console.println("commands: help, uptime, mem, gc, cpus, threads, pci, ls [path], cat <path>, hexdump <path>, run <path> [args] [| <path> [args]], echo <text>, clear, panic");
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
            case "hexdump" -> {
                if (words.size() < 2) {
                    Console.println("usage: hexdump <path>");
                } else {
                    hexdump(words.get(1));
                }
            }
            case "run" -> {
                List<String[]> stages = pipeline(words, quotedWords);
                if (stages == null) {
                    Console.println("usage: run <path> [args] [| <path> [args]]...");
                } else {
                    run(stages);
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

    /** The argv of each program in {@code run a | b}, or null if one of them is missing. */
    private static List<String[]> pipeline(List<String> words, List<Boolean> quotedWords) {
        List<String[]> stages = new ArrayList<>();
        int from = 1;
        for (int i = 1; i <= words.size(); i++) {
            if (i < words.size() && (!words.get(i).equals("|") || quotedWords.get(i))) {
                continue;
            }
            if (i == from) {
                return null;
            }
            String[] argv = new String[i - from];
            for (int j = 0; j < argv.length; j++) {
                argv[j] = words.get(from + j);
            }
            stages.add(argv);
            from = i + 1;
        }
        return stages;
    }

    /**
     * Runs static executables from the disk as processes, each one's output piped into the next,
     * and says how each ended unless it exited 0.
     */
    private static void run(List<String[]> stages) {
        Fat fs = Fat.mounted();
        if (fs == null) {
            Console.println("run: no filesystem mounted");
            return;
        }
        String[] paths = new String[stages.size()];
        Elf.Image[] images = new Elf.Image[stages.size()];
        for (int i = 0; i < images.length; i++) {
            String[] argv = stages.get(i);
            paths[i] = argv[0];
            try {
                images[i] = Elf.load(fs.read(paths[i]), argv);
            } catch (IllegalArgumentException e) {
                Console.println("run: " + paths[i] + ": " + e.getMessage());
                for (int j = 0; j < i; j++) {
                    images[j].space.free();
                }
                return;
            }
        }
        Process[] processes = Process.startPipeline(paths, images);
        waitInForeground(processes);
        for (int i = 0; i < processes.length; i++) {
            try {
                long status = processes[i].waitFor();
                if (status != 0) {
                    Console.println(paths[i] + ": exit status " + status);
                }
            } catch (UserMode.Fault f) {
                Console.println(paths[i] + ": " + f.getMessage());
            } catch (InterruptedException e) {
                Console.println(paths[i] + ": interrupted");
            }
        }
    }

    /** Waits for every one of {@code processes} to end, killing them all if Ctrl-C is typed meanwhile. */
    private static void waitInForeground(Process[] processes) {
        Input.setForeground(true);
        try {
            for (Process p : processes) {
                while (!p.ended()) {
                    if (Input.takeInterrupt()) {
                        Console.println("^C");
                        for (Process q : processes) {
                            q.kill();
                        }
                    }
                    Thread.sleep(10);
                }
            }
        } catch (InterruptedException e) {
            for (Process q : processes) {
                q.kill();
            }
        } finally {
            Input.setForeground(false);
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

    private static void hexdump(String path) {
        Fat fs = Fat.mounted();
        if (fs == null) {
            Console.println("hexdump: no filesystem mounted");
            return;
        }

        byte[] contents;
        try {
            contents = fs.read(path);
        } catch (IllegalArgumentException e) {
            Console.println("hexdump: " + e.getMessage());
            return;
        }

        String hex = "0123456789abcdef";

        for (int offset = 0; offset < contents.length; offset += 16) {
            StringBuilder line = new StringBuilder();

            for (int shift = 28; shift >= 0; shift -= 4) {
                line.append(hex.charAt((offset >>> shift) & 0xF));
            }
            line.append(": ");

            StringBuilder ascii = new StringBuilder();

            for (int i = 0; i < 16; i++) {
                int index = offset + i;

                if (index < contents.length) {
                    int value = contents[index] & 0xFF;

                    line.append(hex.charAt(value >>> 4));
                    line.append(hex.charAt(value & 0xF));
                    ascii.append(value >= 0x20 && value <= 0x7E
                        ? (char) value : '.');
                } else {
                    line.append("  ");
                }

                if (i < 15) {
                    line.append(' ');
                }
            }

            line.append("  ").append(ascii.toString());
            Console.println(line.toString());
        }
    }

    /** Words split on spaces, except inside double quotes, which make one word without the quotes. */
    private static List<String> split(String line, List<Boolean> quotedWords) {
        List<String> words = new ArrayList<>();
        StringBuilder word = null;
        boolean quoted = false;
        boolean wordWasQuoted = false;
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
                    quotedWords.add(wordWasQuoted);
                    word = null;
                    wordWasQuoted = false;
                }
            } else {
                if (word == null) {
                    word = new StringBuilder();
                }
                if (quoted) {
                    wordWasQuoted = true;
                }
                word.append(c);
            }
        }
        if (word != null) {
            words.add(word.toString());
            quotedWords.add(wordWasQuoted);
        }
        return words;
    }
}
