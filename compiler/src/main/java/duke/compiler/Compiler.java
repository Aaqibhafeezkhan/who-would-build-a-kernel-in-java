package duke.compiler;

import duke.compiler.asm.Cond;
import duke.compiler.asm.Mem;
import duke.compiler.asm.Reg;
import duke.compiler.asm.X64;
import duke.compiler.image.Image;
import duke.compiler.image.Reloc;
import duke.compiler.image.Section;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.constant.ConstantDesc;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Whole-program compilation: starting from the entry point, compiles every reachable method into
 * one image, then emits class data, string literals, the boot stub and Limine requests.
 */
public final class Compiler {

    public static final String ENTRY_SYMBOL = "_start";
    public static final long KERNEL_BASE = 0xffff_ffff_8000_0000L;
    /** Where a user program links: Linux's traditional ET_EXEC base, and what Duke's loader expects. */
    public static final long USER_BASE = 0x400000;

    static final String STRING_CLASS = "java/lang/String";
    static final String CLASS_CLASS = "java/lang/Class";
    /** Holds the Limine requests, built at build time; see BuildTimeInit. */
    static final String LIMINE = "duke/boot/Limine";
    static final String BYTE_ARRAY = "[B";

    static final String INTERRUPTS = "duke/kernel/x86/Interrupts";
    static final String RUNTIME = "duke/rt/Runtime";
    static final String INTERRUPT_STUBS = "interrupt.stubs";
    static final String SYSTEM_CALLS = "duke/kernel/user/SystemCalls";
    static final String SYSCALL_ENTRY = "syscall.entry";
    /** A user program's runtime setup, run by _start before any class initializer: user/src's. */
    static final String USER_RUNTIME = "duke/kernel/UserRuntime";
    /** Vectors where the CPU pushes an error code; every other stub pushes a 0 in its place. */
    private static final int DOUBLE_FAULT = 8;
    private static final Set<Integer> ERROR_CODE_VECTORS = Set.of(8, 10, 11, 12, 13, 14, 17, 21, 29, 30);
    private static final Reg[] SAVED_REGISTERS = {
            Reg.RAX, Reg.RCX, Reg.RDX, Reg.RBX, Reg.RBP, Reg.RSI, Reg.RDI,
            Reg.R8, Reg.R9, Reg.R10, Reg.R11, Reg.R12, Reg.R13, Reg.R14, Reg.R15};

    /**
     * Each CPU's own block, which GS points at (IA32_GS_BASE). Layout: the block's own address, the
     * stack limit every prologue checks, the real limit while a preemption request replaces it, the
     * running thread's stack base (the limits are offsets from it), the CPU's index, then for a
     * starting application processor its stack top, CR3 and GDT descriptor address, then the
     * address of the CPU's TSS, the kernel stack top for leaving user mode, and a scratch slot for
     * the user's rsp on the way in.
     */
    static final int CPU_BLOCK_SIZE = 128;
    static final Mem CPU_SELF = Mem.gs(0);
    static final Mem STACK_LIMIT = Mem.gs(8);
    /** The real limit while STACK_LIMIT holds {@link #PREEMPT}. */
    static final Mem STACK_LIMIT_SAVED = Mem.gs(16);
    static final Mem STACK_BASE = Mem.gs(24);
    static final Mem CPU_INDEX = Mem.gs(32);
    static final Mem CPU_TSS = Mem.gs(64);
    static final Mem CPU_KERNEL_STACK = Mem.gs(72);
    static final Mem CPU_USER_STACK = Mem.gs(80);
    static final int TSS_RSP0 = 4;
    /** Ring 3 selectors, RPL 3; must match duke.kernel.x86.Gdt. */
    static final int USER_CODE = 0x3B;
    static final int USER_DATA = 0x33;
    static final int KERNEL_CODE = 0x08;
    static final String BSP_CPU_BLOCK = "cpu.bsp";
    private static final int IA32_GS_BASE = 0xC0000101;
    /**
     * A stack limit no rsp can satisfy. The timer stores it to make the next prologue or loop
     * back-edge take the slow path, which yields instead of throwing.
     */
    static final int PREEMPT = -1;
    static final String STACK_OVERFLOW = "stack.overflow";
    /** Below the normal limit: room for constructing and throwing StackOverflowError. */
    static final int STACK_RESERVE = 16 * 1024;
    /** Below the emergency limit there's only room to panic. */
    static final int STACK_EMERGENCY = 4 * 1024;

    static final String HEAP_ARENA = "heap.arena";
    static final String METHOD_TABLE = "method.table";
    static final String IMAGE_LAYOUT = "image.layout";
    static final String GC_STATIC_ROOTS = "gc.static.roots";
    static final String GC_IMAGE_ROOTS = "gc.image.roots";
    /** Early-boot allocation only; KernelHeap takes over once paging is up. */
    static final int HEAP_ARENA_SIZE = 4 * 1024 * 1024;

    private static final int BOOT_STACK_SIZE = 64 * 1024;
    /** A user program runs on a stack in its own image rather than the one exec gave it, like the kernel. */
    private static final int USER_STACK_SIZE = 1024 * 1024;
    private static final int SYS_ARCH_PRCTL = 158;
    private static final int SYS_EXIT_GROUP = 231;
    private static final int ARCH_SET_GS = 0x1001;

    private final ClassPool pool;
    private final Layouts layouts;
    private final Image image = new Image();

    private final Deque<ClassPool.ResolvedMethod> worklist = new ArrayDeque<>();
    private final Set<String> queuedMethods = new LinkedHashSet<>();
    /** Reachable classes, every class after its superclass. */
    private final Set<String> classes = new LinkedHashSet<>();
    /** Classes with an initializer stub, in the order something first required one. */
    private final Set<String> initializers = new LinkedHashSet<>();
    /** BuildTimeInit results by class; a null value means the initializer has to run at boot. */
    private final Map<String, Map<String, BuildTimeInit.Value>> buildTimeStatics = new LinkedHashMap<>();
    private final Map<String, String> strings = new LinkedHashMap<>();
    private final Set<String> tibs = new LinkedHashSet<>();
    private final Set<VirtualCall> virtualCalls = new LinkedHashSet<>();
    private final Set<VirtualCall> interfaceCalls = new LinkedHashSet<>();
    /** Interface method selectors in itable order. */
    private final Map<Vtables.Key, Integer> selectors = new LinkedHashMap<>();
    private final Vtables vtables;
    private final LambdaCompiler lambdas;
    private int multiArraySites;
    private boolean interruptStubs;
    private boolean syscallEntry;
    private boolean stackOverflowStub;
    private boolean apEntry;
    private final Map<String, LineInfo> lineInfo = new LinkedHashMap<>();

    private record LineInfo(String sourceFile, List<int[]> lines) {}

    /** One exception table row; offsets from the method start, catchTib null for catch-all. */
    record Handler(int start, int end, int handler, String catchTib) {}

    private record ExceptionTable(int frameBytes, List<Handler> handlers) {}

    private final Map<String, ExceptionTable> exceptionTables = new LinkedHashMap<>();
    /** Synchronized methods: the code offset from which each holds its monitor. */
    private final Map<String, Integer> monitorHeld = new LinkedHashMap<>();

    /** A call site's return offset in its method, and the rbp-relative slots holding references there. */
    record Safepoint(int offset, List<Integer> refSlots) {}

    private final Map<String, List<Safepoint>> safepoints = new LinkedHashMap<>();

    /** Packages every build includes without being asked. Generated code lives here, so no flag is needed for it. */
    public static final List<String> DEFAULT_INCLUDES = List.of("duke/js/gen/");

    private final List<String> includes = new ArrayList<>();
    private final List<String> bootInitializers = new ArrayList<>();

    /** A static Linux executable for ring 3 rather than a kernel: no Limine, no privileged boot code. */
    private boolean user;
    private String userRuntimeInit;
    private String userRuntimeArguments;

    public Compiler(ClassPool pool) {
        this.pool = pool;
        this.layouts = new Layouts(pool);
        this.vtables = new Vtables(pool);
        this.lambdas = new LambdaCompiler(pool);
    }

    private record VirtualCall(String owner, String name, String descriptor) {}

    /**
     * Adds extra roots besides the entry point: a class name ({@code pkg/Class}) or, ending in a slash, every
     * class under a package prefix ({@code pkg/}). Each is compiled into the image and its static
     * initializer runs at boot, before the entry point, so code nothing refers to (generated modules,
     * say) can register itself. Initializers that early must not need anything the entry point sets up.
     */
    public Compiler include(String spec) {
        includes.add(spec);
        return this;
    }

    private Set<String> includedClasses() {
        Set<String> found = new TreeSet<>();
        for (String spec : includes) {
            if (spec.endsWith("/")) {
                for (ClassModel model : pool.all()) {
                    String name = model.thisClass().asInternalName();
                    if (name.startsWith(spec)) {
                        found.add(name);
                    }
                }
            } else if (pool.find(spec) == null) {
                throw new CompileException("included class " + spec + " is not in the class pool");
            } else {
                found.add(spec);
            }
        }
        return found;
    }

    public void userMode() {
        user = true;
    }

    public Image compile(String entryClass, String entryMethod) {
        // A program's main may take its arguments or not, as on HotSpot since Java 25; the kernel's takes none.
        ClassPool.ResolvedMethod main = user ? pool.findMethod(entryClass, entryMethod, "([Ljava/lang/String;)V") : null;
        boolean mainTakesArguments = main != null;
        if (main == null) {
            main = pool.resolveMethod(entryClass, entryMethod, "()V");
        }
        if (!main.is(AccessFlag.STATIC)) {
            throw new CompileException("entry point " + entryClass + "." + entryMethod + " must be static");
        }
        // Every TIB is a Class object carrying its name as a String, so these are always in the image.
        requireClass(CLASS_CLASS);
        requireClass(STRING_CLASS);
        if (!user) {
            requireClass(LIMINE);
            if (buildTimeStatics(LIMINE) == null) {
                throw new CompileException(LIMINE + "'s static initializer must be constant-only: Limine reads the"
                        + " requests from the image before any code runs");
            }
        }
        tibs.add(BYTE_ARRAY);
        requireClass(entryClass);
        if (needsInit(entryClass)) {
            requireInitializer(entryClass);
        }
        for (String type : includedClasses()) {
            requireClass(type);
            if (needsInit(type)) {
                requireInitializer(type);
                bootInitializers.add(type);
            }
        }
        String mainSymbol = requireMethod(main);
        if (user) {
            requireClass(USER_RUNTIME);
            userRuntimeInit = requireMethod(USER_RUNTIME, "init", "(J)V");
            if (mainTakesArguments) {
                userRuntimeArguments = requireMethod(USER_RUNTIME, "arguments", "()[Ljava/lang/String;");
            }
        }

        do {
            while (!worklist.isEmpty()) {
                new MethodCompiler(this, worklist.removeFirst()).compile();
            }
            resolveDispatchTargets();
        } while (!worklist.isEmpty());

        closeTibs();
        // All code first: the method table describes every function in .text.
        emitInitializers();
        if (interruptStubs) {
            emitInterruptStubs();
        }
        if (syscallEntry) {
            emitSyscallEntry();
        }
        if (stackOverflowStub) {
            emitStackOverflowStub();
        }
        if (apEntry) {
            emitApEntry();
        }
        if (user) {
            emitUserStartStub(entryClass, mainSymbol);
        } else {
            emitBootStub(entryClass, mainSymbol);
        }
        emitMethodTable();
        emitStatics();
        emitTibs();
        emitStrings();
        emitHeapArena();
        // Only the kernel maps its own image.
        if (!user) {
            emitImageLayout();
        }
        return image;
    }

    ClassPool pool() {
        return pool;
    }

    Layouts layouts() {
        return layouts;
    }

    Image image() {
        return image;
    }

    Vtables vtables() {
        return vtables;
    }

    LambdaCompiler lambdas() {
        return lambdas;
    }

    static String methodSymbol(String owner, String name, String descriptor) {
        return owner + "." + name + descriptor;
    }

    static String staticSymbol(String owner, String name) {
        return owner + "::" + name;
    }

    static String tibSymbol(String type) {
        return "tib:" + type;
    }

    private static String interfacesSymbol(String type) {
        return "interfaces:" + type;
    }

    private static String referencesSymbol(String type) {
        return "references:" + type;
    }

    private static String itableSymbol(String type) {
        return "itable:" + type;
    }

    /** Marks a class reachable: its TIB and statics go into the image. */
    void requireClass(String name) {
        if (classes.contains(name)) {
            return;
        }
        ClassModel model = pool.get(name);
        String superName = pool.superName(model);
        if (superName != null) {
            requireClass(superName);
        }
        classes.add(name);
        tibs.add(name);
        // Intern String constants now so String itself is reachable before the worklist drains.
        for (FieldModel f : model.fields()) {
            if (f.flags().has(AccessFlag.STATIC) && constantValue(f) instanceof String s) {
                requireString(s);
            }
        }
    }

    /** True if initializing {@code type} runs any code: it or a superclass has a static initializer. */
    boolean needsInit(String type) {
        for (String c = type; c != null; ) {
            ClassModel model = pool.get(c);
            if (hasRuntimeClinit(c)) {
                return true;
            }
            if (model.flags().has(AccessFlag.INTERFACE)) {
                return false;
            }
            c = pool.superName(model);
        }
        return false;
    }

    /** A static initializer that has to run at boot because it couldn't run at build time. */
    private boolean hasRuntimeClinit(String type) {
        return pool.declaredMethod(pool.get(type), "<clinit>", "()V") != null && buildTimeStatics(type) == null;
    }

    /**
     * The static field values {@code type}'s initializer produces when BuildTimeInit can run it,
     * else null. Interns the strings and TIBs those values need, so it must run before emission.
     */
    private Map<String, BuildTimeInit.Value> buildTimeStatics(String type) {
        if (!buildTimeStatics.containsKey(type)) {
            Map<String, BuildTimeInit.Value> statics = BuildTimeInit.evaluate(pool.get(type));
            buildTimeStatics.put(type, statics);
            if (statics != null) {
                statics.values().forEach(this::requireImageData);
            }
        }
        return buildTimeStatics.get(type);
    }

    private void requireImageData(BuildTimeInit.Value value) {
        switch (value) {
            case BuildTimeInit.Str s -> requireString(s.value());
            case BuildTimeInit.Arr a -> {
                requireArrayTib(a.type);
                for (BuildTimeInit.Value e : a.elements) {
                    requireImageData(e);
                }
            }
            default -> { }
        }
    }

    /** Class initialization states, in each class's one-byte flag. */
    static final int INITIALIZED = 1;
    static final int INITIALIZING = 2;

    /**
     * The stub that initializes {@code type} on first use (JVMS 5.5): superclass first, then its
     * {@code <clinit>}. The flag goes from 0 to INITIALIZING with a lock cmpxchg before anything
     * runs, and the initializing thread's id is recorded beside it.
     * Meeting INITIALIZING means Runtime.awaitInitialization: the initializing thread itself
     * carries on (the JVM's same-thread rule), any other waits until the class is done.
     */
    String requireInitializer(String type) {
        requireClass(type);
        if (initializers.add(type)) {
            if (type.equals(RUNTIME) || type.equals("duke/kernel/Scheduler")) {
                // Every initializer stub calls into these, so their own stub would recurse forever.
                throw new CompileException(type + " must not need class initialization at run time");
            }
            requireMethod(RUNTIME, "currentThreadId", "()J");
            requireMethod(RUNTIME, "awaitInitialization", "(JJ)V");
            ClassModel model = pool.get(type);
            String superName = pool.superName(model);
            if (superName != null && !model.flags().has(AccessFlag.INTERFACE) && needsInit(superName)) {
                requireInitializer(superName);
            }
            if (hasRuntimeClinit(type)) {
                requireMethod(new ClassPool.ResolvedMethod(model, pool.declaredMethod(model, "<clinit>", "()V")));
            }
        }
        return initializerSymbol(type);
    }

    static String initializerSymbol(String type) {
        return "initialize:" + type;
    }

    private void emitInitializers() {
        for (String type : initializers) {
            image.bss.align(1);
            int flag = image.bss.size();
            image.bss.reserve(1);
            String flagSymbol = "initialized:" + type;
            image.define(flagSymbol, image.bss, flag, 1, Image.SymbolType.OBJECT);
            image.bss.align(8);
            int owner = image.bss.size();
            image.bss.reserve(8);
            String ownerSymbol = "initializer:" + type;
            image.define(ownerSymbol, image.bss, owner, 8, Image.SymbolType.OBJECT);

            Section text = image.text;
            text.align(16);
            int start = text.size();
            X64 a = new X64(text);
            X64.Label done = new X64.Label();
            X64.Label busy = new X64.Label();
            // A real frame, so stack walks (GC, exceptions) step through the stub to its caller.
            a.push(Reg.RBP);
            a.mov(Reg.RBP, Reg.RSP);
            a.cmpByte(Mem.rip(flagSymbol), INITIALIZED);
            a.jcc(Cond.E, done);
            // 0 to INITIALIZING atomically: on another CPU, another thread may be testing it too.
            a.alu(X64.Alu.XOR, false, Reg.RAX, Reg.RAX);
            a.movImm32(Reg.RCX, INITIALIZING);
            a.lockCmpxchgByte(Mem.rip(flagSymbol), Reg.RCX);
            a.jcc(Cond.NE, busy);
            a.call(methodSymbol(RUNTIME, "currentThreadId", "()J"));
            a.store(8, Mem.rip(ownerSymbol), Reg.RAX);
            ClassModel model = pool.get(type);
            String superName = pool.superName(model);
            if (superName != null && initializers.contains(superName) && !model.flags().has(AccessFlag.INTERFACE)) {
                a.call(initializerSymbol(superName));
            }
            if (hasRuntimeClinit(type)) {
                a.call(methodSymbol(type, "<clinit>", "()V"));
            }
            a.movByte(Mem.rip(flagSymbol), INITIALIZED);
            a.jmp(done);
            a.bind(busy);
            // awaitInitialization(long flag, long owner): a long is two slots, value in the lower.
            a.lea(Reg.RAX, Mem.rip(flagSymbol));
            a.push(Reg.RAX);
            a.push(Reg.RAX);
            a.lea(Reg.RAX, Mem.rip(ownerSymbol));
            a.push(Reg.RAX);
            a.push(Reg.RAX);
            a.call(methodSymbol(RUNTIME, "awaitInitialization", "(JJ)V"));
            a.bind(done);
            a.leave();
            a.ret();
            image.define(initializerSymbol(type), text, start, text.size() - start, Image.SymbolType.FUNC);
        }
    }

    String requireMethod(ClassPool.ResolvedMethod m) {
        String symbol = methodSymbol(m.ownerName(), m.name(), m.descriptor());
        if (queuedMethods.add(symbol)) {
            worklist.addLast(m);
        }
        return symbol;
    }

    /** Records a dispatched call; every reachable override becomes reachable in turn. */
    void requireVirtual(String owner, String name, String descriptor) {
        virtualCalls.add(new VirtualCall(owner, name, descriptor));
    }

    /** Records a call through an interface and returns its itable index. */
    int requireInterfaceCall(String iface, String name, String descriptor) {
        interfaceCalls.add(new VirtualCall(iface, name, descriptor));
        return selectors.computeIfAbsent(new Vtables.Key(name, descriptor), k -> selectors.size());
    }

    /**
     * Rapid-type-analysis-style closure: for each dispatched call, compile the implementation each
     * reachable subclass would select. New methods can reach new classes, so the caller iterates.
     */
    private void resolveDispatchTargets() {
        for (VirtualCall call : List.copyOf(virtualCalls)) {
            for (String c : List.copyOf(classes)) {
                if (pool.get(c).flags().has(AccessFlag.INTERFACE) || !pool.isSubclass(c, call.owner())) {
                    continue;
                }
                ClassPool.ResolvedMethod impl = pool.findMethod(c, call.name(), call.descriptor());
                if (impl != null && !impl.is(AccessFlag.ABSTRACT) && !impl.is(AccessFlag.STATIC)) {
                    requireMethod(impl);
                }
            }
        }
        for (VirtualCall call : List.copyOf(interfaceCalls)) {
            for (String c : List.copyOf(classes)) {
                if (pool.get(c).flags().has(AccessFlag.INTERFACE) || !pool.allInterfaces(c).contains(call.owner())) {
                    continue;
                }
                ClassPool.ResolvedMethod impl = pool.selectMethod(c, call.name(), call.descriptor());
                if (impl != null && !impl.is(AccessFlag.ABSTRACT)) {
                    requireMethod(impl);
                }
            }
        }
    }

    String requireMethod(String owner, String name, String descriptor) {
        return requireMethod(pool.resolveMethod(owner, name, descriptor));
    }

    String requireStatic(ClassPool.ResolvedField f) {
        requireClass(f.ownerName());
        return staticSymbol(f.ownerName(), f.name());
    }

    List<Safepoint> safepoints(String methodSymbol) {
        return safepoints.get(methodSymbol);
    }

    void recordSafepoints(String methodSymbol, List<Safepoint> sites) {
        safepoints.put(methodSymbol, sites);
    }

    void recordExceptionTable(String methodSymbol, int frameBytes, List<Handler> handlers) {
        exceptionTables.put(methodSymbol, new ExceptionTable(frameBytes, handlers));
    }

    void recordMonitorHeld(String methodSymbol, int offset) {
        monitorHeld.put(methodSymbol, offset);
    }

    void recordLines(String methodSymbol, String sourceFile, List<int[]> lines) {
        lineInfo.put(methodSymbol, new LineInfo(sourceFile, lines));
    }

    /**
     * For backtraces and exception dispatch (duke.rt.Backtrace, duke.rt.Exceptions). Header: entry
     * count (u64). Each 64-byte entry, sorted by address: start, size (u32), line count (u32), name
     * String, source file String or 0, line table or 0, exception table or 0, flags (u32, see
     * methodFlags), the offset a synchronized method holds its monitor from (u32), GC map or 0.
     * A GC map is a safepoint count (u32), then per call site its return offset (u32), a slot count
     * (u32) and that many rbp-relative offsets (i32) of slots holding references, in code order. A line table is
     * (code offset u32, line u32) pairs in code order. An exception table is a row count (u32) and
     * the frame's local-variable bytes below rbp (u32), then 24-byte rows: start, end, handler
     * (u32 code offsets, plus u32 padding) and the catch type's TIB, 0 meaning any.
     */
    private void emitMethodTable() {
        List<Image.Symbol> functions = new ArrayList<>();
        for (Image.Symbol sym : image.symbols()) {
            if (sym.section() == image.text && sym.type() == Image.SymbolType.FUNC) {
                functions.add(sym);
            }
        }
        functions.sort((x, y) -> Integer.compare(x.offset(), y.offset()));

        Section rodata = image.rodata;
        rodata.align(8);
        for (Image.Symbol f : functions) {
            LineInfo info = lineInfo.get(f.name());
            if (info != null && !info.lines().isEmpty()) {
                int start = rodata.size();
                for (int[] pair : info.lines()) {
                    rodata.emit32(pair[0]);
                    rodata.emit32(pair[1]);
                }
                image.define("lines:" + f.name(), rodata, start, rodata.size() - start, Image.SymbolType.OBJECT);
            }
            List<Safepoint> sites = safepoints.get(f.name());
            if (sites != null) {
                rodata.align(8);
                int start = rodata.size();
                rodata.emit32(sites.size());
                for (Safepoint site : sites) {
                    rodata.emit32(site.offset());
                    rodata.emit32(site.refSlots().size());
                    for (int slot : site.refSlots()) {
                        rodata.emit32(slot);
                    }
                }
                image.define("gcmap:" + f.name(), rodata, start, rodata.size() - start, Image.SymbolType.OBJECT);
            }
            ExceptionTable exceptions = exceptionTables.get(f.name());
            if (exceptions != null) {
                rodata.align(8);
                int start = rodata.size();
                rodata.emit32(exceptions.handlers().size());
                rodata.emit32(exceptions.frameBytes());
                for (Handler h : exceptions.handlers()) {
                    rodata.emit32(h.start());
                    rodata.emit32(h.end());
                    rodata.emit32(h.handler());
                    rodata.emit32(0);
                    emitPointer(rodata, h.catchTib());
                }
                image.define("exceptions:" + f.name(), rodata, start, rodata.size() - start, Image.SymbolType.OBJECT);
            }
        }
        rodata.align(8);
        int table = rodata.size();
        rodata.emit64(functions.size());
        for (Image.Symbol f : functions) {
            LineInfo info = lineInfo.get(f.name());
            boolean hasLines = info != null && !info.lines().isEmpty();
            rodata.emitReloc(Reloc.Kind.ABS64, f.name(), 0);
            rodata.emit32(f.size());
            rodata.emit32(hasLines ? info.lines().size() : 0);
            // Like Java stack traces: no descriptor, the line number disambiguates overloads.
            int paren = f.name().indexOf('(');
            emitPointer(rodata, requireString(javaName(paren < 0 ? f.name() : f.name().substring(0, paren))));
            emitPointer(rodata, info == null || info.sourceFile() == null ? null : requireString(info.sourceFile()));
            emitPointer(rodata, hasLines ? "lines:" + f.name() : null);
            emitPointer(rodata, exceptionTables.containsKey(f.name()) ? "exceptions:" + f.name() : null);
            Integer heldFrom = monitorHeld.get(f.name());
            rodata.emit32(methodFlags(f.name()) | (heldFrom != null ? METHOD_SYNCHRONIZED : 0));
            rodata.emit32(heldFrom != null ? heldFrom : 0);
            emitPointer(rodata, safepoints.containsKey(f.name()) ? "gcmap:" + f.name() : null);
        }
        image.define(METHOD_TABLE, rodata, table, rodata.size() - table, Image.SymbolType.OBJECT);
    }

    /** Hidden from stack traces: runtime plumbing and the constructors of the exception being built. */
    static final int METHOD_HIDDEN = 1;
    /** Unwinding stops here: exceptions don't propagate out of interrupt handlers. */
    static final int METHOD_INTERRUPT_ENTRY = 2;
    /**
     * A synchronized method: the unwinder releases the monitor in its [rbp - 8] when an exception
     * leaves it from at or past the code offset in the entry's next word.
     */
    static final int METHOD_SYNCHRONIZED = 4;

    private int methodFlags(String symbol) {
        if (symbol.equals("interrupt.common") || symbol.equals(SYSCALL_ENTRY)) {
            return METHOD_INTERRUPT_ENTRY;
        }
        if (symbol.equals(STACK_OVERFLOW)) {
            return METHOD_HIDDEN;
        }
        int dot = symbol.indexOf('.');
        int paren = symbol.indexOf('(');
        if (dot < 0 || paren < 0 || pool.find(symbol.substring(0, dot)) == null) {
            return 0;
        }
        String owner = symbol.substring(0, dot);
        String name = symbol.substring(dot + 1, paren);
        boolean throwableSetup = pool.isSubclass(owner, "java/lang/Throwable")
                && (name.equals("<init>") || name.equals("fillInStackTrace"));
        boolean plumbing = symbol.equals(STACK_OVERFLOW) || owner.equals("duke/rt/Runtime") || owner.equals("duke/rt/Types")
                || owner.equals("duke/rt/Backtrace") || owner.equals("duke/rt/Exceptions");
        return throwableSetup || plumbing ? METHOD_HIDDEN : 0;
    }

    void requireStackOverflowStub() {
        if (!stackOverflowStub) {
            stackOverflowStub = true;
            requireMethod("duke/rt/Runtime", "stackOverflow", "()V");
            requireMethod("duke/rt/Runtime", "stackExhausted", "()V");
            requireMethod("duke/rt/Runtime", "preempt", "()V");
        }
    }

    /** Where a new thread's first switch returns to (Magic.threadEntry). */
    static final String AP_ENTRY = "ap.entry";
    /** Where a starting CPU finds its stack top, CR3 and GDT descriptor, set up by the boot CPU. */
    static final int CPU_STACK_TOP = 40;
    static final int CPU_CR3 = 48;
    private static final int MP_INFO_EXTRA_ARGUMENT = 24;

    /** Where Limine sends each application processor (Magic.apEntry). */
    String requireApEntry() {
        if (!apEntry) {
            apEntry = true;
            requireMethod("duke/kernel/Smp", "apMain", "()V");
        }
        return AP_ENTRY;
    }

    /**
     * Limine jumps here on each application processor with its limine_mp_info in rdi, on Limine's
     * page tables and stack, in bootloader-reclaimable memory. The info's extra argument is this
     * CPU's block, which is image data and so mapped either way. Off Limine's memory first (our
     * CR3, our stack), then GS at the block, then Java.
     */
    private void emitApEntry() {
        Section text = image.text;
        text.align(16);
        int start = text.size();
        X64 a = new X64(text);
        a.load(8, false, Reg.RBX, Mem.at(Reg.RDI, MP_INFO_EXTRA_ARGUMENT));
        a.load(8, false, Reg.RAX, Mem.at(Reg.RBX, CPU_CR3));
        a.writeCr(3, Reg.RAX);
        a.load(8, false, Reg.RSP, Mem.at(Reg.RBX, CPU_STACK_TOP));
        a.alu(X64.Alu.XOR, false, Reg.RBP, Reg.RBP);
        a.mov(Reg.RAX, Reg.RBX);
        a.mov(Reg.RDX, Reg.RBX);
        a.shiftImm(X64.Shift.SHR, true, Reg.RDX, 32);
        a.movImm32(Reg.RCX, IA32_GS_BASE);
        a.wrmsr();
        a.call(methodSymbol("duke/kernel/Smp", "apMain", "()V"));
        X64.Label hang = new X64.Label();
        a.bind(hang);
        a.cli();
        a.hlt();
        a.jmp(hang);
        image.define(AP_ENTRY, text, start, text.size() - start, Image.SymbolType.FUNC);
    }

    String requireThreadEntry() {
        return requireMethod("duke/kernel/Scheduler", "threadMain", "()V");
    }

    /**
     * Called from a prologue that found rsp below the limit, or from a loop back-edge that found a
     * preemption request. A request ({@link #PREEMPT} in the limit) puts the real limit back and
     * tail-jumps to Runtime.preempt, which returns to the caller once the thread runs again.
     * Otherwise it's an overflow, unless the limit has changed since the caller looked: an interrupt
     * between the caller's check and this one runs Java code whose own prologue takes the pending
     * request, and then the real limit is back and rsp is above it, so the stub just returns. The
     * first time, it lowers the limit to the emergency mark and
     * tail-jumps to Runtime.stackOverflow, which throws from inside the reserve. Overflowing again
     * before the unwinder resets the limit means even the reserve is gone: checks are disabled and
     * Runtime.stackExhausted panics.
     */
    private void emitStackOverflowStub() {
        Section text = image.text;
        text.align(16);
        int start = text.size();
        X64 a = new X64(text);
        X64.Label overflow = new X64.Label();
        X64.Label exhausted = new X64.Label();
        X64.Label spurious = new X64.Label();
        a.aluImm(X64.Alu.CMP, true, STACK_LIMIT, PREEMPT);
        a.jcc(Cond.NE, overflow);
        a.load(8, false, Reg.RAX, STACK_LIMIT_SAVED);
        a.store(8, STACK_LIMIT, Reg.RAX);
        a.jmp(methodSymbol("duke/rt/Runtime", "preempt", "()V"));
        a.bind(overflow);
        a.alu(X64.Alu.CMP, true, STACK_LIMIT, Reg.RSP);
        a.jcc(Cond.BE, spurious);
        a.load(8, false, Reg.RAX, STACK_BASE);
        a.lea(Reg.RAX, Mem.at(Reg.RAX, STACK_EMERGENCY));
        a.alu(X64.Alu.CMP, true, STACK_LIMIT, Reg.RAX);
        a.jcc(Cond.E, exhausted);
        a.store(8, STACK_LIMIT, Reg.RAX);
        a.jmp(methodSymbol("duke/rt/Runtime", "stackOverflow", "()V"));
        a.bind(exhausted);
        a.alu(X64.Alu.XOR, false, Reg.RAX, Reg.RAX);
        a.store(8, STACK_LIMIT, Reg.RAX);
        a.jmp(methodSymbol("duke/rt/Runtime", "stackExhausted", "()V"));
        a.bind(spurious);
        a.ret();
        image.define(STACK_OVERFLOW, text, start, text.size() - start, Image.SymbolType.FUNC);
    }

    String requireInterruptStubs() {
        if (!interruptStubs) {
            interruptStubs = true;
            requireMethod(INTERRUPTS, "dispatch", "(J)V");
        }
        return INTERRUPT_STUBS;
    }

    /**
     * One entry stub per vector, then a common path that saves every general-purpose register,
     * calls the Java dispatcher with the frame address, restores and returns with iretq. From
     * ring 3 it swaps GS in and out around that, so the kernel's per-CPU block is GS throughout.
     * Frame layout from the address passed: r15 ... rax (15 slots, r15 lowest), vector, error code,
     * then the CPU's rip, cs, rflags, rsp, ss.
     */
    private void emitInterruptStubs() {
        Section text = image.text;
        X64 a = new X64(text);
        text.align(16);
        int common = text.size();
        X64.Label enteredFromKernel = new X64.Label();
        a.cmpByte(Mem.at(Reg.RSP, 24), KERNEL_CODE);
        a.jcc(Cond.E, enteredFromKernel);
        a.swapgs();
        a.bind(enteredFromKernel);
        a.cld();
        for (Reg r : SAVED_REGISTERS) {
            a.push(r);
        }
        // From ring 3, rbp is the user's. Point it at the frame record Magic.enterUser left at the
        // kernel stack top instead, so stack walks go from the handler to the code that entered.
        X64.Label fromKernel = new X64.Label();
        a.cmpByte(Mem.at(Reg.RSP, 8 * SAVED_REGISTERS.length + 24), KERNEL_CODE);
        a.jcc(Cond.E, fromKernel);
        a.lea(Reg.RBP, Mem.at(Reg.RSP, 8 * SAVED_REGISTERS.length + 56));
        a.bind(fromKernel);
        a.mov(Reg.RAX, Reg.RSP);
        a.push(Reg.RAX);
        a.push(Reg.RAX);
        a.call(methodSymbol(INTERRUPTS, "dispatch", "(J)V"));
        a.aluImm(X64.Alu.ADD, true, Reg.RSP, 16);
        for (int i = SAVED_REGISTERS.length - 1; i >= 0; i--) {
            a.pop(SAVED_REGISTERS[i]);
        }
        a.aluImm(X64.Alu.ADD, true, Reg.RSP, 16);
        X64.Label returnToKernel = new X64.Label();
        a.cmpByte(Mem.at(Reg.RSP, 8), KERNEL_CODE);
        a.jcc(Cond.E, returnToKernel);
        a.swapgs();
        a.bind(returnToKernel);
        a.iretq();
        image.define("interrupt.common", text, common, text.size() - common, Image.SymbolType.FUNC);

        for (int vector = 0; vector < 256; vector++) {
            text.align(16);
            int start = text.size();
            if (!ERROR_CODE_VECTORS.contains(vector)) {
                a.pushImm(0);
            }
            if (vector == DOUBLE_FAULT) {
                // Runs on its own IST stack, below the boot stack's limit, and only ever panics:
                // switch the prologue stack checks off rather than have them fire right away.
                a.push(Reg.RAX);
                a.alu(X64.Alu.XOR, false, Reg.RAX, Reg.RAX);
                a.store(8, STACK_LIMIT, Reg.RAX);
                a.pop(Reg.RAX);
            }
            a.pushImm(vector);
            a.jmp("interrupt.common");
            image.define("interrupt." + vector, text, start, text.size() - start, Image.SymbolType.FUNC);
        }

        Section rodata = image.rodata;
        rodata.align(8);
        int table = rodata.size();
        for (int vector = 0; vector < 256; vector++) {
            rodata.emitReloc(Reloc.Kind.ABS64, "interrupt." + vector, 0);
        }
        image.define(INTERRUPT_STUBS, rodata, table, rodata.size() - table, Image.SymbolType.OBJECT);
    }

    String requireSyscallEntry() {
        if (!syscallEntry) {
            syscallEntry = true;
            requireMethod(SYSTEM_CALLS, "dispatch", "(J)V");
        }
        return SYSCALL_ENTRY;
    }

    /**
     * Where the syscall instruction lands (IA32_LSTAR), still on the user's stack and GS with
     * interrupts masked. Swaps in the kernel's GS, moves to the kernel stack top Magic.enterUser recorded, saves the user's rsp and every
     * general-purpose register in the interrupt frame's order, and calls
     * duke.kernel.user.SystemCalls.dispatch with the frame address. rcx holds the user's rip and r11
     * its rflags, which sysretq restores. sysretq trusts rcx: it's the address after a syscall
     * instruction, which is canonical as long as nothing maps the lower half's last page.
     */
    private void emitSyscallEntry() {
        Section text = image.text;
        text.align(16);
        int start = text.size();
        X64 a = new X64(text);
        a.swapgs();
        a.store(8, CPU_USER_STACK, Reg.RSP);
        a.load(8, false, Reg.RSP, CPU_KERNEL_STACK);
        a.push(CPU_USER_STACK);
        for (Reg r : SAVED_REGISTERS) {
            a.push(r);
        }
        a.lea(Reg.RBP, Mem.at(Reg.RSP, 8 * SAVED_REGISTERS.length + 8));
        a.mov(Reg.RAX, Reg.RSP);
        a.push(Reg.RAX);
        a.push(Reg.RAX);
        a.call(methodSymbol(SYSTEM_CALLS, "dispatch", "(J)V"));
        a.aluImm(X64.Alu.ADD, true, Reg.RSP, 16);
        for (int i = SAVED_REGISTERS.length - 1; i >= 0; i--) {
            a.pop(SAVED_REGISTERS[i]);
        }
        a.pop(Reg.RSP);
        a.swapgs();
        a.sysretq();
        image.define(SYSCALL_ENTRY, text, start, text.size() - start, Image.SymbolType.FUNC);
    }

    /** A TIB for a type named the way class entries name it: internal name, or descriptor for arrays. */
    String requireTib(String type) {
        if (type.startsWith("[")) {
            return requireArrayTib(type);
        }
        pool.get(type);
        tibs.add(type);
        return tibSymbol(type);
    }

    /** {@code type} is an array descriptor such as {@code [I} or {@code [Ljava/lang/String;}. */
    String requireArrayTib(String type) {
        tibs.add(type);
        return tibSymbol(type);
    }

    /** Emits the per-site table {@code Heap.allocateMultiArray} walks; layout documented there. */
    String requireMultiArrayDescriptor(String type, int dimensions) {
        Section rodata = image.rodata;
        rodata.align(8);
        int start = rodata.size();
        rodata.emit32(dimensions);
        rodata.emit32(0);
        for (int level = 0; level < dimensions; level++) {
            String levelType = type.substring(level);
            rodata.emitReloc(Reloc.Kind.ABS64, requireArrayTib(levelType), 0);
            rodata.emit64(Layouts.width(levelType.substring(1)));
        }
        String symbol = "multianewarray:" + multiArraySites++;
        image.define(symbol, rodata, start, rodata.size() - start, Image.SymbolType.OBJECT);
        return symbol;
    }

    String requireString(String value) {
        String symbol = strings.get(value);
        if (symbol == null) {
            for (int i = 0; i < value.length(); i++) {
                if (value.charAt(i) > 0xFF) {
                    throw new CompileException("string literal has non-Latin-1 characters: \"" + value + "\"");
                }
            }
            requireClass(STRING_CLASS);
            tibs.add(BYTE_ARRAY);
            symbol = "str:" + strings.size();
            strings.put(value, symbol);
        }
        return symbol;
    }

    private void emitStatics() {
        Section data = image.data;
        List<Map<String, BuildTimeInit.Value>> evaluated = new ArrayList<>();
        for (String name : classes) {
            Map<String, BuildTimeInit.Value> statics = buildTimeStatics.get(name);
            if (statics != null) {
                evaluated.add(statics);
            }
        }
        Map<BuildTimeInit.Arr, String> arrays = BuildTimeInit.symbolsFor(evaluated);
        for (Map.Entry<BuildTimeInit.Arr, String> e : arrays.entrySet()) {
            emitImageArray(e.getKey(), e.getValue(), arrays);
        }
        List<String> staticRefs = new ArrayList<>();
        for (String name : classes) {
            Map<String, BuildTimeInit.Value> statics = buildTimeStatics.get(name);
            for (FieldModel f : pool.get(name).fields()) {
                if (!f.flags().has(AccessFlag.STATIC)) {
                    continue;
                }
                data.align(8);
                int offset = data.size();
                String descriptor = f.fieldType().stringValue();
                if (descriptor.startsWith("L") || descriptor.startsWith("[")) {
                    staticRefs.add(staticSymbol(name, f.fieldName().stringValue()));
                }
                BuildTimeInit.Value computed = statics == null ? null : statics.get(f.fieldName().stringValue());
                if (computed != null) {
                    emitImageValue(data, computed, 8, arrays);
                    image.define(staticSymbol(name, f.fieldName().stringValue()), data, offset, 8, Image.SymbolType.OBJECT);
                    continue;
                }
                ConstantDesc initial = constantValue(f);
                switch (initial) {
                    case null -> data.emit64(0);
                    case Integer i -> data.emit64(i);
                    case Long l -> data.emit64(l);
                    case String s -> data.emitReloc(Reloc.Kind.ABS64, requireString(s), 0);
                    default -> throw new CompileException("unsupported constant " + initial + " for " + name + "." + f.fieldName());
                }
                image.define(staticSymbol(name, f.fieldName().stringValue()), data, offset, 8, Image.SymbolType.OBJECT);
            }
        }
        emitRootTable(GC_STATIC_ROOTS, staticRefs);
        List<String> imageArrays = new ArrayList<>();
        for (Map.Entry<BuildTimeInit.Arr, String> e : arrays.entrySet()) {
            char element = e.getKey().type.charAt(1);
            if (element == 'L' || element == '[') {
                imageArrays.add(e.getValue());
            }
        }
        emitRootTable(GC_IMAGE_ROOTS, imageArrays);
    }

    /**
     * A count (u64) and that many addresses. Static roots point at reference fields; image roots
     * at build-time reference arrays, whose elements can point into the heap once code stores to
     * them.
     */
    private void emitRootTable(String symbol, List<String> addresses) {
        Section rodata = image.rodata;
        rodata.align(8);
        int start = rodata.size();
        rodata.emit64(addresses.size());
        for (String address : addresses) {
            rodata.emitReloc(Reloc.Kind.ABS64, address, 0);
        }
        image.define(symbol, rodata, start, rodata.size() - start, Image.SymbolType.OBJECT);
    }

    /** Same layout as a heap array: TIB, length, padding, elements. In .data, so it stays mutable. */
    private void emitImageArray(BuildTimeInit.Arr array, String symbol, Map<BuildTimeInit.Arr, String> arrays) {
        Section data = image.data;
        data.align(8);
        int start = data.size();
        data.emitReloc(Reloc.Kind.ABS64, tibSymbol(array.type), 0);
        data.emit32(array.elements.length);
        data.emit32(0);
        int width = Layouts.width(array.type.substring(1));
        for (BuildTimeInit.Value e : array.elements) {
            emitImageValue(data, e, width, arrays);
        }
        data.align(8);
        image.define(symbol, data, start, data.size() - start, Image.SymbolType.OBJECT);
    }

    private void emitImageValue(Section data, BuildTimeInit.Value value, int width,
            Map<BuildTimeInit.Arr, String> arrays) {
        switch (value) {
            case BuildTimeInit.Prim p -> {
                switch (width) {
                    case 1 -> data.emit8((int) p.bits());
                    case 2 -> data.emit16((int) p.bits());
                    case 4 -> data.emit32((int) p.bits());
                    default -> data.emit64(p.bits());
                }
            }
            case BuildTimeInit.Str s -> data.emitReloc(Reloc.Kind.ABS64, requireString(s.value()), 0);
            case BuildTimeInit.Arr a -> data.emitReloc(Reloc.Kind.ABS64, arrays.get(a), 0);
            case BuildTimeInit.Null n -> data.emit64(0);
        }
    }

    private static ConstantDesc constantValue(FieldModel f) {
        return f.findAttribute(Attributes.constantValue())
                .map(ConstantValueAttribute::constant)
                .map(c -> c.constantValue())
                .orElse(null);
    }

    /** String literals live in .data, not .rodata: String may cache things in its own fields later. */
    private void emitStrings() {
        Section data = image.data;
        Layouts.ClassLayout layout = layouts.of(STRING_CLASS);
        int valueOffset = layout.offsetOf("value");
        for (Map.Entry<String, String> e : strings.entrySet()) {
            String symbol = e.getValue();
            byte[] bytes = e.getKey().getBytes(StandardCharsets.ISO_8859_1);

            data.align(8);
            int start = data.size();
            data.emitReloc(Reloc.Kind.ABS64, tibSymbol(STRING_CLASS), 0);
            for (int pos = Layouts.HEADER_SIZE; pos < layout.size(); ) {
                if (pos == valueOffset) {
                    data.emitReloc(Reloc.Kind.ABS64, symbol + ".value", 0);
                    pos += 8;
                } else {
                    data.emit8(0);
                    pos++;
                }
            }
            image.define(symbol, data, start, layout.size(), Image.SymbolType.OBJECT);

            data.align(8);
            start = data.size();
            data.emitReloc(Reloc.Kind.ABS64, tibSymbol(BYTE_ARRAY), 0);
            data.emit32(bytes.length);
            data.emit32(0);
            data.emitBytes(bytes);
            image.define(symbol + ".value", data, start, Layouts.ARRAY_DATA_OFFSET + bytes.length, Image.SymbolType.OBJECT);
        }
    }

    /** Adds every TIB the emitted ones point at: superclasses, Object for arrays, element types. */
    private void closeTibs() {
        Deque<String> pending = new ArrayDeque<>(tibs);
        while (!pending.isEmpty()) {
            String type = pending.removeFirst();
            List<String> deps = new ArrayList<>();
            if (type.startsWith("[")) {
                deps.add(ClassPool.OBJECT);
                String element = elementType(type);
                if (element != null) {
                    deps.add(element);
                }
            } else {
                String superName = pool.superName(pool.get(type));
                if (superName != null) {
                    deps.add(superName);
                }
                deps.addAll(pool.allInterfaces(type));
            }
            for (String dep : deps) {
                if (tibs.add(dep)) {
                    pending.addLast(dep);
                }
            }
        }
        for (String type : tibs) {
            requireString(javaName(type));
        }
    }

    /** The element type of a reference array, or null for a primitive array. */
    static String elementType(String arrayType) {
        String element = arrayType.substring(1);
        if (element.startsWith("[")) {
            return element;
        }
        if (element.startsWith("L")) {
            return element.substring(1, element.length() - 1);
        }
        return null;
    }

    /** The name Class.getName() would report: dots for classes, descriptors for arrays. */
    static String javaName(String type) {
        return type.replace('/', '.');
    }

    /**
     * TIB layout (offsets in Layouts). A TIB is also the type's java.lang.Class instance: word 0 is
     * an ordinary object header. They live in .rodata, so Class can never have instance fields.
     * <pre>
     * [0]  object header: the TIB of java.lang.Class
     * [8]  super TIB, or 0 for Object; Object for arrays
     * [16] instance size, or element size for arrays (u32)
     * [20] flags: array, interface, reference array (u32)
     * [24] element TIB for reference arrays, else 0
     * [32] name, a String
     * [40] 0-terminated list of every interface the type implements, or 0 if none
     * [48] itable: one code pointer per interface selector, or 0 for classes without interfaces
     * [56] reference field offsets for the GC: a count (u32) then offsets (u32), or 0 if none
     * [64] vtable: one code pointer per Vtables slot, 0 where nothing dispatches to it
     * </pre>
     */
    private void emitTibs() {
        Section rodata = image.rodata;
        for (String type : tibs) {
            if (!type.startsWith("[") && !pool.get(type).flags().has(AccessFlag.INTERFACE)) {
                List<Integer> references = layouts.of(type).referenceOffsets();
                if (!references.isEmpty()) {
                    rodata.align(8);
                    int start = rodata.size();
                    rodata.emit32(references.size());
                    for (int offset : references) {
                        rodata.emit32(offset);
                    }
                    image.define(referencesSymbol(type), rodata, start, rodata.size() - start, Image.SymbolType.OBJECT);
                }
            }
            if (type.startsWith("[") || pool.allInterfaces(type).isEmpty()) {
                continue;
            }
            rodata.align(8);
            int start = rodata.size();
            for (String iface : pool.allInterfaces(type)) {
                rodata.emitReloc(Reloc.Kind.ABS64, tibSymbol(iface), 0);
            }
            rodata.emit64(0);
            image.define(interfacesSymbol(type), rodata, start, rodata.size() - start, Image.SymbolType.OBJECT);
            if (!selectors.isEmpty() && !pool.get(type).flags().has(AccessFlag.INTERFACE)) {
                emitItable(type);
            }
        }
        for (String type : tibs) {
            boolean array = type.startsWith("[");
            String element = array ? elementType(type) : null;
            boolean isInterface = !array && pool.get(type).flags().has(AccessFlag.INTERFACE);
            String superName = array ? ClassPool.OBJECT : pool.superName(pool.get(type));
            int flags = (array ? Layouts.TIB_FLAG_ARRAY : 0)
                    | (isInterface ? Layouts.TIB_FLAG_INTERFACE : 0)
                    | (element != null ? Layouts.TIB_FLAG_REFERENCE_ARRAY : 0);

            rodata.align(8);
            int start = rodata.size();
            emitPointer(rodata, tibSymbol(CLASS_CLASS));
            emitPointer(rodata, superName == null ? null : tibSymbol(superName));
            rodata.emit32(array ? Layouts.width(type.substring(1)) : layouts.of(type).size());
            rodata.emit32(flags);
            emitPointer(rodata, element == null ? null : tibSymbol(element));
            emitPointer(rodata, strings.get(javaName(type)));
            emitPointer(rodata, image.isDefined(interfacesSymbol(type)) ? interfacesSymbol(type) : null);
            emitPointer(rodata, image.isDefined(itableSymbol(type)) ? itableSymbol(type) : null);
            emitPointer(rodata, image.isDefined(referencesSymbol(type)) ? referencesSymbol(type) : null);
            String dispatchType = array || isInterface ? ClassPool.OBJECT : type;
            for (Vtables.Key key : vtables.layout(dispatchType)) {
                ClassPool.ResolvedMethod impl = pool.findMethod(dispatchType, key.name(), key.descriptor());
                String symbol = impl == null ? null : methodSymbol(impl.ownerName(), impl.name(), impl.descriptor());
                boolean compiled = symbol != null && queuedMethods.contains(symbol) && !impl.is(AccessFlag.ABSTRACT);
                emitPointer(rodata, compiled ? symbol : null);
            }
            image.define(tibSymbol(type), rodata, start, rodata.size() - start, Image.SymbolType.OBJECT);
        }
    }

    /** Entries stay 0 where the class doesn't implement the selector or nothing calls it. */
    private void emitItable(String type) {
        Section rodata = image.rodata;
        rodata.align(8);
        int start = rodata.size();
        for (Vtables.Key key : selectors.keySet()) {
            ClassPool.ResolvedMethod impl = pool.selectMethod(type, key.name(), key.descriptor());
            String symbol = impl == null ? null : methodSymbol(impl.ownerName(), impl.name(), impl.descriptor());
            boolean compiled = symbol != null && queuedMethods.contains(symbol) && !impl.is(AccessFlag.ABSTRACT);
            emitPointer(rodata, compiled ? symbol : null);
        }
        image.define(itableSymbol(type), rodata, start, rodata.size() - start, Image.SymbolType.OBJECT);
    }

    private static void emitPointer(Section section, String symbol) {
        if (symbol == null) {
            section.emit64(0);
        } else {
            section.emitReloc(Reloc.Kind.ABS64, symbol, 0);
        }
    }

    /**
     * _start for a user program: the same steps as the kernel's boot stub, with arch_prctl in place
     * of the GS MSR, and exit_group(0) once main returns. Linux and Duke both start it with nothing
     * but rsp set.
     */
    private void emitUserStartStub(String entryClass, String mainSymbol) {
        image.bss.align(16);
        int stackOffset = image.bss.size();
        image.bss.reserve(USER_STACK_SIZE);
        image.define("boot.stack", image.bss, stackOffset, USER_STACK_SIZE, Image.SymbolType.OBJECT);

        image.bss.align(CPU_BLOCK_SIZE);
        int cpuBlock = image.bss.size();
        image.bss.reserve(CPU_BLOCK_SIZE);
        image.define(BSP_CPU_BLOCK, image.bss, cpuBlock, CPU_BLOCK_SIZE, Image.SymbolType.OBJECT);

        Section text = image.text;
        text.align(16);
        int start = text.size();
        X64 a = new X64(text);
        // Linux's initial stack (argc, argv, envp, auxv): the syscall below leaves r12 alone.
        a.mov(Reg.R12, Reg.RSP);
        a.lea(Reg.RSP, Mem.rip("boot.stack", USER_STACK_SIZE));
        a.alu(X64.Alu.XOR, false, Reg.RBP, Reg.RBP);
        a.movImm32(Reg.RAX, SYS_ARCH_PRCTL);
        a.movImm32(Reg.RDI, ARCH_SET_GS);
        a.lea(Reg.RSI, Mem.rip(BSP_CPU_BLOCK));
        a.syscall();
        a.lea(Reg.RAX, Mem.rip(BSP_CPU_BLOCK));
        a.store(8, CPU_SELF, Reg.RAX);
        if (stackOverflowStub) {
            a.lea(Reg.RAX, Mem.rip("boot.stack", 0));
            a.store(8, STACK_BASE, Reg.RAX);
            a.lea(Reg.RAX, Mem.rip("boot.stack", STACK_RESERVE));
            a.store(8, STACK_LIMIT, Reg.RAX);
        }
        // init(long initialStack): a long is two slots.
        a.push(Reg.R12);
        a.push(Reg.R12);
        a.call(userRuntimeInit);
        a.aluImm(X64.Alu.ADD, true, Reg.RSP, 16);
        if (initializers.contains(entryClass)) {
            a.call(initializerSymbol(entryClass));
        }
        for (String type : bootInitializers) {
            a.call(initializerSymbol(type));
        }
        if (userRuntimeArguments != null) {
            // Built only now, so String and its helpers are initialized; nothing allocates before main has it.
            a.call(userRuntimeArguments);
            a.push(Reg.RAX);
            a.call(mainSymbol);
            a.aluImm(X64.Alu.ADD, true, Reg.RSP, 8);
        } else {
            a.call(mainSymbol);
        }
        a.movImm32(Reg.RAX, SYS_EXIT_GROUP);
        a.alu(X64.Alu.XOR, false, Reg.RDI, Reg.RDI);
        a.syscall();
        image.define(ENTRY_SYMBOL, text, start, text.size() - start, Image.SymbolType.FUNC);
    }

    /** Switches to our own stack, initializes the entry class, then calls the entry point. */
    private void emitBootStub(String entryClass, String mainSymbol) {
        // A page of nothing below the stack, which the kernel unmaps: running off the stack faults.
        image.bss.align(4096);
        int guard = image.bss.size();
        image.bss.reserve(4096);
        image.define("boot.stack.guard", image.bss, guard, 4096, Image.SymbolType.OBJECT);
        int stackOffset = image.bss.size();
        image.bss.reserve(BOOT_STACK_SIZE);
        image.define("boot.stack", image.bss, stackOffset, BOOT_STACK_SIZE, Image.SymbolType.OBJECT);

        image.bss.align(CPU_BLOCK_SIZE);
        int cpuBlock = image.bss.size();
        image.bss.reserve(CPU_BLOCK_SIZE);
        image.define(BSP_CPU_BLOCK, image.bss, cpuBlock, CPU_BLOCK_SIZE, Image.SymbolType.OBJECT);

        Section text = image.text;
        text.align(16);
        int start = text.size();
        X64 a = new X64(text);
        a.lea(Reg.RSP, Mem.rip("boot.stack", BOOT_STACK_SIZE));
        a.alu(X64.Alu.XOR, false, Reg.RBP, Reg.RBP);
        // GS before any Java runs: every prologue reads the stack limit through it.
        a.lea(Reg.RAX, Mem.rip(BSP_CPU_BLOCK));
        a.mov(Reg.RDX, Reg.RAX);
        a.shiftImm(X64.Shift.SHR, true, Reg.RDX, 32);
        a.movImm32(Reg.RCX, IA32_GS_BASE);
        a.wrmsr();
        a.lea(Reg.RAX, Mem.rip(BSP_CPU_BLOCK));
        a.store(8, CPU_SELF, Reg.RAX);
        if (stackOverflowStub) {
            a.lea(Reg.RAX, Mem.rip("boot.stack", 0));
            a.store(8, STACK_BASE, Reg.RAX);
            a.lea(Reg.RAX, Mem.rip("boot.stack", STACK_RESERVE));
            a.store(8, STACK_LIMIT, Reg.RAX);
        }
        if (initializers.contains(entryClass)) {
            a.call(initializerSymbol(entryClass));
        }
        for (String type : bootInitializers) {
            a.call(initializerSymbol(type));
        }
        a.call(mainSymbol);
        X64.Label hang = new X64.Label();
        a.bind(hang);
        a.cli();
        a.hlt();
        a.jmp(hang);
        image.define(ENTRY_SYMBOL, text, start, text.size() - start, Image.SymbolType.FUNC);
    }

    /**
     * Section boundaries for Magic.imageLayout(), so the kernel can map its own image: text
     * start/end, rodata start/end, data start, bss end, then the boot stack's guard page. Emitted
     * last, so the end symbols see the final sizes.
     */
    private void emitImageLayout() {
        Section rodata = image.rodata;
        rodata.align(8);
        int table = rodata.size();
        for (String bound : List.of("text.start", "text.end", "rodata.start", "rodata.end", "data.start", "bss.end")) {
            rodata.emitReloc(Reloc.Kind.ABS64, "image." + bound, 0);
        }
        rodata.emitReloc(Reloc.Kind.ABS64, "boot.stack.guard", 0);
        image.define(IMAGE_LAYOUT, rodata, table, rodata.size() - table, Image.SymbolType.OBJECT);
        image.define("image.text.start", image.text, 0, 0, Image.SymbolType.OBJECT);
        image.define("image.text.end", image.text, image.text.size(), 0, Image.SymbolType.OBJECT);
        image.define("image.rodata.start", image.rodata, 0, 0, Image.SymbolType.OBJECT);
        image.define("image.rodata.end", image.rodata, image.rodata.size(), 0, Image.SymbolType.OBJECT);
        image.define("image.data.start", image.data, 0, 0, Image.SymbolType.OBJECT);
        image.define("image.bss.end", image.bss, image.bss.size(), 0, Image.SymbolType.OBJECT);
    }

    /** Costs nothing in the file: .bss is only memsz. */
    private void emitHeapArena() {
        image.bss.align(4096);
        int offset = image.bss.size();
        image.bss.reserve(HEAP_ARENA_SIZE);
        image.define(HEAP_ARENA, image.bss, offset, HEAP_ARENA_SIZE, Image.SymbolType.OBJECT);
    }
}
