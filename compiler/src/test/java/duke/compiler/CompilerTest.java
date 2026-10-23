package duke.compiler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import duke.compiler.image.ElfWriter;
import duke.compiler.image.Image;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompilerTest {

    private static final Path KERNEL_SOURCES = Path.of(System.getProperty("duke.kernelSources"));

    @TempDir
    Path tmp;

    @Test
    void kernelCompilesToLoadableElf() throws IOException {
        Image.Linked linked = new Compiler(ClassPool.load(compile())).compile("duke/kernel/Kernel", "main")
                .link(Compiler.KERNEL_BASE);
        ByteBuffer elf = ByteBuffer.wrap(ElfWriter.write(linked, linked.address(Compiler.ENTRY_SYMBOL)))
                .order(ByteOrder.LITTLE_ENDIAN);

        assertArrayEquals(new byte[] {0x7F, 'E', 'L', 'F', 2, 1, 1}, slice(elf, 0, 7));
        assertEquals(62, elf.getShort(18), "e_machine");
        assertEquals(linked.address(Compiler.ENTRY_SYMBOL), elf.getLong(24), "e_entry");
        int phnum = elf.getShort(56);
        assertEquals(3, phnum);
        for (int i = 0; i < phnum; i++) {
            int ph = 64 + i * 56;
            long offset = elf.getLong(ph + 8);
            long vaddr = elf.getLong(ph + 16);
            assertEquals(1, elf.getInt(ph), "PT_LOAD");
            assertTrue(Long.compareUnsigned(vaddr, Compiler.KERNEL_BASE) >= 0, "Limine requires higher-half segments");
            assertEquals(0, (vaddr - offset) % Image.PAGE, "offset and vaddr must agree modulo the page size");
        }
    }

    @Test
    void imageCarriesLimineBaseRevisionTag() throws IOException {
        Image image = new Compiler(ClassPool.load(compile())).compile("duke/kernel/Kernel", "main");
        Image.Linked linked = image.link(Compiler.KERNEL_BASE);
        Image.Placed data = linked.sections().stream().filter(p -> p.section() == image.data).findFirst().orElseThrow();
        ByteBuffer b = ByteBuffer.wrap(data.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        // The static field holds a pointer to a build-time array; its elements start 16 bytes in.
        long field = linked.address("duke/boot/Limine::BASE_REVISION");
        long array = b.getLong((int) (field - data.address()));
        int elements = (int) (array - data.address()) + 16;
        assertEquals(0, elements % 8, "Limine only scans 8-byte aligned slots");
        assertEquals(0xf9562b2d5c95a6c8L, b.getLong(elements));
        assertEquals(0x6a7b384944536bdcL, b.getLong(elements + 8));
        assertEquals(6, b.getLong(elements + 16));
    }

    @Test
    void unsupportedBytecodeErrorNamesMethodAndLine() throws IOException {
        Path classes = compile("""
                package duke.test;
                public final class Entry {
                    static int half(int x) {
                        float f = x;
                        return (int) (f % 2);
                    }
                    public static void main() {
                        half(0);
                    }
                }
                """);
        CompileException e = assertThrows(CompileException.class,
                () -> new Compiler(ClassPool.load(classes)).compile("duke/test/Entry", "main"));
        assertEquals("duke/test/Entry.half(I)I (line 5): % on floats and doubles is not supported yet",
                e.getMessage());
    }

    @Test
    void dispatchCompilesOnlyOverridesThatCanBeCalled() throws IOException {
        Path classes = compile("""
                package duke.test;
                public final class Entry {
                    static class A { int f() { return 1; } int g() { return 1; } }
                    static class B extends A { int f() { return 2; } int g() { return 2; } }
                    static int call(A a) { return a.f(); }
                    public static void main() {
                        call(new B());
                    }
                }
                """);
        Image image = new Compiler(ClassPool.load(classes)).compile("duke/test/Entry", "main");
        assertTrue(image.isDefined("duke/test/Entry$A.f()I"));
        assertTrue(image.isDefined("duke/test/Entry$B.f()I"));
        assertTrue(!image.isDefined("duke/test/Entry$B.g()I"), "nothing dispatches g, so no override of it is compiled");
    }

    @Test
    void unreachableCodeIsNeverCompiled() throws IOException {
        Path classes = compile("""
                package duke.test;
                public final class Entry {
                    static Object unused() {
                        return new Object();
                    }
                    public static void main() {
                    }
                }
                """);
        Image image = new Compiler(ClassPool.load(classes)).compile("duke/test/Entry", "main");
        assertTrue(image.isDefined("duke/test/Entry.main()V"));
        assertTrue(!image.isDefined("duke/test/Entry.unused()Ljava/lang/Object;"));
    }

    @Test
    void constantOnlyInitializersRunAtBuildTime() throws IOException {
        Path classes = compile("""
                package duke.test;
                public final class Entry {
                    static final class Table { static final long[] VALUES = {1, 2, 3}; }
                    static final class Effect { static long seen = Table.VALUES[0]; }
                    public static void main() {
                        long x = Table.VALUES[1] + Effect.seen;
                    }
                }
                """);
        Image image = new Compiler(ClassPool.load(classes)).compile("duke/test/Entry", "main");
        assertTrue(!image.isDefined("initialize:duke/test/Entry$Table"), "constant-only initializer needs no stub");
        assertTrue(!image.isDefined("duke/test/Entry$Table.<clinit>()V"), "nor compiled code");
        assertTrue(image.isDefined("initialize:duke/test/Entry$Effect"), "reading another class's static runs at boot");
    }

    @Test
    void stackMapsListExactlyTheLiveReferenceSlots() throws IOException {
        Path classes = compile("""
                package duke.test;
                public final class Entry {
                    static int g(int x) { return x; }
                    static int f(Object a, long b, Object c) {
                        Object d = a;
                        int unused = 3;
                        return g(unused);
                    }
                    public static void main() {
                        f(null, 0, null);
                    }
                }
                """);
        Compiler compiler = new Compiler(ClassPool.load(classes));
        compiler.compile("duke/test/Entry", "main");
        List<Compiler.Safepoint> sites = compiler.safepoints("duke/test/Entry.f(Ljava/lang/Object;JLjava/lang/Object;)I");
        // Args a (slot 0), b (1-2), c (3) sit above rbp, last argument lowest: a at +16+8*3, c at +16.
        // Locals d (4) and unused (5) sit below it: d at -8. The int arguments to g aren't references.
        Compiler.Safepoint call = sites.get(sites.size() - 1);
        assertEquals(List.of(40, 16, -8), call.refSlots());
        Compiler.Safepoint prologue = sites.get(0);
        assertEquals(List.of(40, 16), prologue.refSlots(), "before any local is assigned only arguments are live");
    }

    @Test
    void nonLatin1StringLiteralIsRejected() throws IOException {
        Path classes = compile("""
                package duke.test;
                public final class Entry {
                    public static void main() {
                        "\\u2603".length();
                    }
                }
                """);
        CompileException e = assertThrows(CompileException.class,
                () -> new Compiler(ClassPool.load(classes)).compile("duke/test/Entry", "main"));
        assertTrue(e.getMessage().contains("non-Latin-1"), e.getMessage());
    }

    private static final String ENTRY_WITH_HITS = """
            package duke.test;
            public final class Entry {
                static int hits;
                public static void main() {
                }
            }
            """;

    private static final String UNREFERENCED_HOOK = """
            package duke.test;
            final class Hook {
                static {
                    Entry.hits++;
                }
            }
            """;

    @Test
    void includedClassesAreCompiledInAndInitializedAtBoot() throws IOException {
        Path classes = compile(ENTRY_WITH_HITS, UNREFERENCED_HOOK);
        String hook = "initialize:duke/test/Hook";
        assertFalse(new Compiler(ClassPool.load(classes)).compile("duke/test/Entry", "main").isDefined(hook),
                "nothing refers to Hook, so it is left out unless included");
        assertTrue(new Compiler(ClassPool.load(classes)).include("duke/test/Hook")
                .compile("duke/test/Entry", "main").isDefined(hook));
        assertTrue(new Compiler(ClassPool.load(classes)).include("duke/test/")
                .compile("duke/test/Entry", "main").isDefined(hook), "a trailing slash includes a whole package");
        assertFalse(new Compiler(ClassPool.load(classes)).include("duke/other/")
                .compile("duke/test/Entry", "main").isDefined(hook), "a package with no classes includes nothing");
    }

    @Test
    void includingAMissingClassIsAnError() throws IOException {
        Path classes = compile(ENTRY_WITH_HITS);
        CompileException e = assertThrows(CompileException.class,
                () -> new Compiler(ClassPool.load(classes)).include("duke/test/Nope").compile("duke/test/Entry", "main"));
        assertTrue(e.getMessage().contains("duke/test/Nope"), e.getMessage());
    }

    @Test
    void generatedJavaScriptIsIncludedByDefault() {
        assertTrue(Compiler.DEFAULT_INCLUDES.contains("duke/js/gen/"));
    }

    /** Compiles the kernel sources plus {@code extra} (each a full compilation unit in package duke.test). */
    private Path compile(String... extra) throws IOException {
        Path out = tmp.resolve("classes");
        Path extraDir = tmp.resolve("extra");
        // javac rejects module source path entries that do not exist.
        String sourcePath = "java.base=" + KERNEL_SOURCES + (extra.length == 0 ? "" : java.io.File.pathSeparator + extraDir);
        // Same flags as KERNEL_JAVAC in the Makefile.
        List<String> args = new ArrayList<>(List.of("--system", "none", "-XDstringConcat=inline",
                "--module-source-path", sourcePath, "-d", out.toString()));
        try (Stream<Path> files = Files.walk(KERNEL_SOURCES)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
        }
        for (int i = 0; i < extra.length; i++) {
            Path file = extraDir.resolve("duke/test/Entry" + (i == 0 ? "" : i) + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, extra[i]);
            args.add(file.toString());
        }
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler()
                .run(null, diagnostics, diagnostics, args.toArray(String[]::new));
        assertEquals(0, status, diagnostics.toString());
        return out;
    }

    private static byte[] slice(ByteBuffer b, int from, int length) {
        byte[] out = new byte[length];
        b.get(from, out);
        return out;
    }
}
