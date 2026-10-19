package duke.compiler;

import duke.compiler.image.ElfWriter;
import duke.compiler.image.Image;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code dukec --classes DIR --entry pkg/Class.method --output kernel.elf [--map kernel.map] [--include pkg/Class|pkg/]... [--user]}
 *
 * <p>{@code --include} adds classes (or whole packages, with a trailing slash) that are compiled in and
 * initialized at boot though nothing refers to them. {@link Compiler#DEFAULT_INCLUDES} are always on.
 * {@code --user} builds a static Linux executable for ring 3 instead of a kernel.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Path classes = null;
        String entry = null;
        Path output = null;
        Path map = null;
        List<String> includes = new ArrayList<>(Compiler.DEFAULT_INCLUDES);
        boolean user = false;
        for (int i = 0; i < args.length; i++) {
            String value = i + 1 < args.length ? args[i + 1] : null;
            switch (args[i]) {
                case "--classes" -> classes = Path.of(require(value, args[i]));
                case "--entry" -> entry = require(value, args[i]);
                case "--output" -> output = Path.of(require(value, args[i]));
                case "--map" -> map = Path.of(require(value, args[i]));
                case "--include" -> includes.add(require(value, args[i]));
                case "--user" -> {
                    user = true;
                    continue;
                }
                default -> usage("unknown argument " + args[i]);
            }
            i++;
        }
        if (classes == null || entry == null || output == null) {
            usage("--classes, --entry and --output are required");
        }
        int dot = entry.lastIndexOf('.');
        if (dot < 0) {
            usage("--entry must look like pkg/Class.method");
        }

        Image.Linked linked;
        try {
            Compiler compiler = new Compiler(ClassPool.load(classes));
            if (user) {
                compiler.userMode();
            }
            includes.forEach(compiler::include);
            Image image = compiler.compile(entry.substring(0, dot), entry.substring(dot + 1));
            linked = image.link(user ? Compiler.USER_BASE : Compiler.KERNEL_BASE);
        } catch (CompileException e) {
            System.err.println("dukec: error: " + e.getMessage());
            System.exit(1);
            return;
        }
        Files.write(output, ElfWriter.write(linked, linked.address(Compiler.ENTRY_SYMBOL)));
        if (user && !output.toFile().setExecutable(true)) {
            System.err.println("dukec: warning: could not mark " + output + " executable");
        }
        if (map != null) {
            Files.write(map, mapLines(linked));
        }
    }

    private static List<String> mapLines(Image.Linked linked) {
        return linked.symbols().stream()
                .sorted(Comparator.comparingLong(s -> linked.address(s.name())))
                .map(s -> String.format("%016x %8d %s", linked.address(s.name()), s.size(), s.name()))
                .toList();
    }

    private static String require(String value, String flag) {
        if (value == null) {
            usage(flag + " needs a value");
        }
        return value;
    }

    private static void usage(String problem) {
        System.err.println("dukec: " + problem);
        System.err.println("usage: dukec --classes DIR --entry pkg/Class.method --output kernel.elf [--map kernel.map] [--include pkg/Class|pkg/]...");
        System.exit(2);
    }
}
