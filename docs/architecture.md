# Architecture

## Boot

1. UEFI firmware runs Limine from `EFI/BOOT/BOOTX64.EFI`. Limine reads `boot/limine/limine.conf`.
2. Limine loads `kernel.elf`. Its segments sit in the higher half at `0xffffffff80000000`.
   Limine scans the image for its requests: base revision 6, HHDM, memory map, executable
   address, framebuffer and RSDP. These are declared in Java as `static final long[]` in
   `duke.boot.Limine`. Build-time initialization turns them into data, and the compiler puts the
   class in every image and fails if that ever stops being possible. Limine writes response
   pointers into the arrays, switches to long mode with paging on, and jumps to `_start`.
3. `_start` comes from `Compiler.emitBootStub`. It switches to a 64 KiB stack in `.bss`,
   initializes `Kernel`, then calls `Kernel.main()`. If `main` returns, the CPU halts.

Everything after step 2 is compiled Java except the dozen instructions of `_start`.

At entry, interrupts are disabled and there is no IDT, so a fault before `Idt.load()` triple-faults
(QEMU, run with `-no-reboot`, then exits). SSE is disabled (Limine clears CR4 bits it doesn't
need), so the compiler emits no SSE instructions.

## Interrupts

`Magic.interruptStubs()` makes the compiler emit 256 entry stubs and a table of their addresses.
Each stub pushes a dummy error code where the CPU doesn't push one, then its vector, and jumps to a
common path. That path clears DF, saves every general-purpose register, and calls
`duke.kernel.x86.Interrupts.dispatch(frame)` with the address of the saved state. It restores
after, and returns with `iretq`. `Idt` writes the gates in Java and loads them with `lidt`.

We load our own GDT (`Gdt`: kernel code 0x08, data 0x10, a TSS, user data 0x33 and code 0x3B) before the IDT. Limine's lives in
bootloader-reclaimable memory, and the CPU reads the GDT on every interrupt. The TSS provides IST 1,
a separate stack for double faults. A page below the boot stack is left unmapped, so running off the
stack faults. That fault can't push its frame, so it escalates to a double fault, which lands on
the IST stack and gets reported. The double-fault entry stub also switches the prologue stack
checks off (a stack limit of 0), since the IST stack sits below the boot stack's limit.

`Interrupts.dispatch` runs a registered `Handler` if there is one. Otherwise it panics with the
exception name, error code, CR2 for page faults, and a register dump. Handlers must not allocate
for now: the bump allocator isn't reentrant.

## Memory

`PhysicalMemory` hands out 4 KiB frames from the "usable" regions of Limine's memory map,
tracked in a `FrameBitmap` (one bit per page, next-fit search). Frame 0 is never handed out.
Frames are named by physical address and touched through the higher-half direct map. Zeroed frames
are cleared as 512 eight-byte words. `tryAllocateZeroed` returns -1 on exhaustion, so heap backing and
thread stacks can share that path without allocating an exception while handling memory pressure.

Bulk copies use `Magic.copyMemory`: whole words with `rep movsq`, then the exact byte remainder.
Unaligned addresses work, and neither end is rounded beyond the requested span. Only a destination
inside the source range needs the backward path; it copies the high byte tail first, then words,
and clears DF before returning. This is for RAM and linear framebuffers, not device registers.
`System.arraycopy` checks array types and ranges before reaching the intrinsic.

`KernelAddressSpace.activate()` then builds our own 4-level tables (`PageTable`) and switches
CR3. The kernel image is mapped from the compiler's section boundaries (`Magic.imageLayout()`):
text read-only and executable, rodata read-only, data and bss writable, and both NX when the CPU
has it. The direct map covers every region type Limine maps, with 2 MiB pages where alignment
allows, and uncached for the framebuffer. The virtual layout is unchanged, so execution simply
continues. Every higher-half PML4 entry gets a PDPT up front (1 MiB), so the slots never change
and process tables can copy them once and still see everything the kernel maps later.

Last in `Kernel.init()`, the bootloader-reclaimable regions (Limine's page tables, GDT, stack
and responses) go to the frame allocator: 45 MiB under QEMU's 256M. `Limine.snapshot()` copies
every response into Java objects first, and every `Limine` accessor reads the copies.

`KernelHeap.init()` then moves Java allocation off the 4 MiB early arena in `.bss` into a 512 GiB
virtual region of its own. `Heap` stays a bump allocator, but when it runs off the committed end
it asks its `Backing` to map 2 MiB more of zeroed frames. The backing reports failure rather than
throwing: building an `OutOfMemoryError` would itself need the allocator. Nothing is freed until
there's a collector, below.

### Garbage collection

`duke.rt.Collector` is a non-moving mark-sweep, written in Java that never allocates while it
runs. Non-moving keeps identity hashes (address-derived) and `Magic.addressOf` results valid, and
needs no pointer fix-ups.

Roots are static reference fields and build-time reference arrays (both listed in tables the
compiler emits), plus the stack. Stacks are walked through the rbp chain with **precise stack
maps**. Every value a template-compiled method holds is in its frame at a call, never only in a
register, so every call site is a safepoint. At each call the compiler records which rbp-relative
slots hold references. The types come from `FrameTypes`, the JVM verifier's type pass reduced to
reference/non-reference, seeded by the class file's StackMapTable. Templates often pop operands
into registers before calling a helper, so each call also records how many operand slots it
already consumed. Those slots aren't in the frame any more.

Marking uses one bit per 8 heap bytes (a build-time array for the early arena, a committed side
table for the growable region) and an explicit mark stack. Sweeping walks objects by their
headers, coalesces dead runs into holes, chains them into an address-ordered free list, and hands
a dead tail back to the bump pointer. Free space is always walkable: holes carry a small marker
word that can't be a TIB address. Allocation bumps first, then takes holes, then collects once
committed memory passes twice the last live size (32 MiB minimum), and only then commits more.

The collector clears both mark bitmaps by whole words before each marking pass. The region bitmap
contains one word per 512 heap bytes; committed regions are page-aligned. Reused heap holes are also
word-aligned and are zeroed with `Magic.zeroMemoryWords` before the allocator returns them.

`make conformance-gc` runs the whole conformance suite with a collection at every allocation
(about 50,000 collections). A slot missing from a stack map shows up there as a wrong result or a
GC panic. Interrupt handlers still must not allocate. Other threads' stacks are roots too, and
because threads only stop at safepoints (see Threads), their frames are scanned just as precisely.

Allocation and collection hold the heap lock (`Heap.LOCK`), whose spin is a safepoint. A
collection first stops every other CPU (`Smp.stopOthers`): it kicks each with an IPI whose handler
requests preemption, and each CPU's next safepoint with interrupts on records its frame and waits
in `Smp.stopIfRequested`. The collector walks those CPUs' running threads from there, then lets
them go before sweeping, which only touches dead objects and can't race an allocation. A kick that
lands while a CPU is between threads, with preemption requests ignored, is lost, so the collector
kicks again every millisecond until every CPU has stopped. Nothing may allocate under the
scheduler lock: a CPU spinning on it has interrupts off and can't stop, so that collection would
never start (the heap panics instead). Under `-smp 4` in QEMU the stops cost
`make conformance-gc` about 40%, with every allocation waking three CPUs.
Java objects still come from the fixed bump arena in `.bss` (see Objects below).

## Platform

`duke.kernel.acpi` finds the RSDP through Limine and walks the XSDT, verifying every table's
checksum. It parses the MADT (CPUs, I/O APICs, ISA interrupt overrides) and the HPET's address.
Device registers live in reserved physical memory outside the direct map, so
`KernelAddressSpace.mapDevice` maps them uncached into their own PML4 slot. QEMU runs with
`-smp 4`, so the CPU count is a real check.

`duke.kernel.pci` reads the MCFG table and walks PCI configuration space through ECAM. It starts at
the host bridges and follows PCI-to-PCI bridges to their secondary buses, mapping each bus's 1 MiB
of config space only when the scan reaches it, so a q35 machine maps one bus rather than 256 MiB.
Each function keeps its ids, class and decoded BARs (I/O or memory, 64-bit ones joined), and the
`pci` shell command prints one line per function.

`duke.kernel.virtio` drives virtio block devices through the virtio 1.0 PCI interface: vendor
capabilities point into the BARs for the common config, the doorbells and the disk's capacity. One
split virtqueue per disk lives in a single frame, with one request in flight, polled with interrupts
suppressed. Heap arrays aren't physically contiguous, so sectors pass through a staging frame, 8 at a
time. `make ktest` attaches a 64-sector disk with a known pattern behind a PCIe root port, which also
gives the PCI scan a bridge to follow.

`duke.kernel.fs.Fat` reads FAT32 and FAT16 volumes, on a whole disk or in the first FAT partition
of an MBR, and the boot mounts the first one it finds for the shell's `ls` and `cat`. It follows
cluster chains through the FAT and reads long names, checking each against its short name's
checksum. A 16-bit FAT size of 0 marks FAT32: the spec decides by cluster count, but QEMU's
`fat:32:` disks are too small for that. Those disks also get a FAT16 boot sector, so FAT32 tests
use images from `tools/FatImage.java`, and QEMU's `fat:16:` directories cover FAT16.

Time comes from the local APIC timer. `Pic.disable()` remaps the legacy PICs to vectors
0xE0-0xEF and masks them, so a spurious IRQ can't land on a CPU exception vector. The APIC timer
is calibrated against the HPET's main counter over 10 ms, then runs periodic at 100 Hz into a
Java handler that counts ticks and sends EOI. `Kernel.init()` ends by enabling interrupts. Every
suite runs with the tick live. The GC-stress conformance run takes about 6,000 timer interrupts at
arbitrary instructions, in the middle of about 53,000 collections.

## Framebuffer console

`Console` writes every character to the serial port and, once `FramebufferConsole` is up, to
Limine's framebuffer as 8x16 glyphs (1280x800 gives 160x50 text under QEMU), scrolling by moving
pixels up a row. The font is Spleen 8x16 (BSD-2-Clause, license in the file header), converted from
its BDF by `tools/GenerateFont.java` into a string literal, so the glyphs are image data.
`make shell-test` takes a QEMU screendump and checks rendered text pixel for pixel against the font.

## Input and the shell

`IoApic` routes ISA IRQs to the boot CPU, applying the MADT's polarity and trigger overrides. The
PS/2 keyboard (IRQ 1, scan code set 1, US layout) and COM1's receive interrupt (IRQ 4) both push
characters into `Input`, a ring buffer of image arrays, so handlers never allocate. After the boot
log, `Kernel.main` runs `Shell`: line editing plus `help`, `uptime`, `mem`, `gc`, `cpus`,
`threads`, `pci`, `ls`, `cat`, `run`, `echo` and `panic`. `make shell-test` boots the real kernel with serial on pipes and the QEMU monitor on a
socket. It types over serial and as PS/2 keystrokes (`sendkey`) and checks the replies.

## Threads

`java.lang.Thread` runs on `duke.kernel.Scheduler`: kernel threads, round-robin, with `start`,
`join`, `sleep`, `yield` and `currentThread`. The boot thread becomes `main`, and every CPU has an
idle thread that halts when nothing else can run. Each thread gets a 64 KiB stack from
`KernelStacks`, in its own PML4 slot with an unmapped guard page below each stack. A stack that
comes back keeps its frames mapped for the next thread: the old thread may have run on any CPU,
and unmapping would need a TLB shootdown to stop them writing through stale translations.

Preemption is timer-driven but only lands at safepoints. The timer interrupt doesn't switch
threads itself. It calls `Magic.requestPreemption`, which stores an impossible value (-1) in the
stack limit every method prologue already compares rsp against. The next prologue takes its slow
path into the overflow stub, which sees -1, puts the real limit back and calls
`Runtime.preempt`, which yields. A loop that makes no calls never reaches a prologue, so backward
branches also compare the limit with -1 (`cmp qword gs:[8], -1; jne`), at the cost of one
compare per iteration. This is the stack-limit trick from Jikes RVM, and it means a parked thread
is always stopped at a call site with a stack map. The collector walks every parked stack with
those maps, so there is no conservative scanning and no separate safepoint flag to poll.

The switch itself is `Magic.switchStack(saveAt, rsp)` inside `Scheduler.swap`: push rbp, save
rsp, load the other thread's rsp, pop rbp. Every parked thread is suspended in that same
instruction sequence, so the epilogue that follows returns into the other thread's caller. A new
thread's stack is built to look like it was parked there, with `Scheduler.threadMain` as the
return address and a zero rbp to end stack walks. Each thread has its own stack base (the overflow
stub and the unwinder compute limits from it). Stack checks are off between saving one thread's
limit and the next thread installing its own, so nothing compares one thread's rsp against
another's limit.

Every CPU runs threads from one shared run queue, at the same time. Scheduler and monitor state
is guarded by one spinlock (`Scheduler.lock`, an `xchg` loop in `duke.rt.SpinLock`), taken with
interrupts off. A switch hands the lock over: whichever thread is switched to releases it,
possibly on another CPU than the one it last ran on. Queueing a thread kicks an idle CPU with an
IPI rather than leaving it for that CPU's next tick. The allocator and collector are skipped by
`Runtime.preempt` while they run on that CPU, since neither is reentrant and a thread holding the
heap lock mustn't move. Every CPU's local APIC timer ticks, and time comes from the HPET rather
than from counting those interrupts, so the first CPU whose timer fires after a sleeper's deadline
wakes it. `threads` in the shell shows which CPU each running thread is on.

The rest of the shared kernel state has its own interrupts-off locks: the console (reentrant per
CPU, so a panic raised while printing can still print), and the physical frame allocator, which
the heap and thread stacks both draw from.

### User mode

`duke.kernel.user.UserMode.run(space, entry, stack)` runs ring 3 code on the calling thread and
returns its exit status. `Magic.enterUser` leaves a frame record at a 16-byte-aligned kernel stack top, a
saved rbp under a return address back into `run`, and points the TSS's rsp0 and the CPU block at
it, then `iretq`s to ring 3. Interrupts and system calls from ring 3 land just below that record.
`Magic.leaveUser` cuts back to it, which is how exit and user faults return from `run`. An
exception in ring 3 (anything under vector 32 but NMI and machine checks) comes back as a thrown
`UserMode.Fault`, not a panic.

`syscall` goes to a compiler-emitted stub (`IA32_LSTAR`) that switches to the recorded kernel
stack, saves registers in the interrupt frame's layout and calls `SystemCalls.dispatch`, which
uses Linux's numbers and registers (the list is below). `write` takes fd 1 or 2 and checks every
page of the buffer is mapped for ring 3. Ring 3 can load its own GS (one `mov gs` used to
take the machine down), so both entry paths and `enterUser` `swapgs` at the boundary. Each `run`
starts ring 3 with a GS base of 0, but nothing saves it per thread yet, so a thread that moves
CPUs picks up whatever that CPU's last user left there.

The return address in that record is a safepoint. Both entry stubs point rbp at the record, so the
collector and the unwinder walk from a handler straight into `run`'s frame and on up. User code
never reaches a prologue, so preemption and stop-the-world requests are taken on the way back:
after a handler for an interrupt from ring 3, `Interrupts.dispatch` turns interrupts back on and
calls `UserMode.interrupted`, whose prologue can yield. The thread may come back on another CPU,
so every return to ring 3 re-points that CPU's rsp0 at the thread's kernel stack first.

Each process has an `AddressSpace`: its own lower half, the kernel's higher half shared. `run`
hands it to the scheduler, which keeps it with the thread and loads CR3 whenever it
switches between threads on different tables, so kernel code reading user memory in a system
call sees the right process even after moving CPUs. The CR3 write flushes the old process's TLB
entries, which is all the shootdown single-threaded processes need. `run` goes back to the
kernel's table before returning, so the space can be freed, with every frame mapped into it.

Memory can also be reserved rather than mapped: the stack and anonymous `mmap`s get page-table
entries only when a ring 3 page fault lands in them (`AddressSpace.pageIn`, called from
`Interrupts.dispatch` before treating the fault as fatal, with interrupts off and no heap
allocation). That's what lets `jsc`'s programs reserve a 1 GiB heap on a 256 MiB machine.
`write` pages in untouched reserved pages it's asked to read, as the copy's fault would on Linux.
`mmap` takes private anonymous mappings only, placed top down in the gap between the executable
and the stack.

System calls so far: `write` (1), `mmap` (9), `exit` (60) and `exit_group` (231), with Linux's
numbers. That's all `jsc` emits, so `make js-test` runs its x86 programs on Duke too: compiled on
the host, put on a FAT disk, and run one after another through the shell in a single boot, with
the output compared to node's.

`Elf.load` turns a static x86-64 executable into a fresh `AddressSpace`: each `PT_LOAD` segment
copied into zeroed frames (so bss is free), writable only with `PF_W` and NX without `PF_X`, and
pages two segments share get both permissions. It refuses dynamic and position-independent
executables, anything below 64 KiB or reaching the stack, and over 64 MiB in all, before
allocating. The 8 MiB stack sits under the lower half's last page, with Linux's layout at rsp:
argc, argv, an empty environment and an auxiliary vector (`AT_PHDR`, `AT_PHNUM`, `AT_PAGESZ`,
`AT_ENTRY`, `AT_RANDOM`) for a static libc's startup. The shell's `run <path>` loads one from the
FAT disk and runs it on the shell's thread. `tools/FatImage.java` assembles the test executables
by hand, so the build needs no cross toolchain.

### Monitors

`synchronized` blocks and methods, `wait`, `notify` and `notifyAll` run on `duke.kernel.Monitors`.
There's no lock word in object headers. A monitor exists only while some thread holds, waits on
or is blocked on it, as an entry in a small table keyed by the object's address, which the
non-moving collector keeps stable. Uncontended enter and exit cost a call and a short scan with
the scheduler's lock. Releasing hands the monitor straight to the first blocked thread.

`monitorenter` and `monitorexit` compile to calls into `duke.rt.Runtime`, and javac's own
handlers release block monitors on exceptions. A synchronized method gets the same calls in its
prologue and before each return, and keeps its lock object in a hidden slot at `[rbp - 8]`. Its
method table entry is flagged and records the code offset from which the monitor is held, so
`Exceptions.raise` releases it when an exception leaves the method, but not when one comes from
the prologue before the monitor was taken.

## Compiler pipeline

```
ClassPool ─▶ Compiler (worklist from Kernel.main) ─▶ MethodCompiler per method ─▶ X64 into Image sections ─▶ link ─▶ ElfWriter
```

- **ClassPool** holds every class in the image. The world is closed: anything missing from the
  pool doesn't exist, which is what makes devirtualization by class hierarchy analysis sound.
- **Compiler** drives reachability. A method is compiled only once something calls it. A class
  becomes reachable when its statics, methods or literals are used, and that schedules its
  `<clinit>`.
- **MethodCompiler** is a template code generator. Each bytecode becomes a fixed instruction
  sequence. That's slow code, but simple enough to trust while the rest of the system gets built.
  An SSA IR with register allocation is on the roadmap.
- **X64** encodes instructions. Each form is pinned to clang's output in `X64Test`.
- **Image / ElfWriter** do layout and relocation (`PC32`, `ABS64`) and emit a static ELF with a
  symbol table, so `objdump` and `gdb` show Java method names.

### Execution model

The machine stack is the JVM operand stack. Every JVM slot, local or stack, is one 8-byte machine
slot. `long` takes two, exactly as in the JVM: the value sits in the lower-numbered local (the
deeper stack slot), with a padding slot above it. This keeps `dup2`, `pop2` and the `dup_x`
family trivially correct.

Int values have undefined upper 32 bits in their slot. Any code that consumes an int as 64 bits
(`i2l`, array indexing) extends it explicitly.

### Calling convention

```
caller:  push arg0 ... push argN      ; first argument deepest
         call Owner.name(desc)
         add rsp, 8 * argSlots
         push rax                      ; (twice for long results)

callee frame:
         [rbp + 16 + 8*(argSlots-1-i)]  argument / local slot i  (i < argSlots)
         [rbp + 8]                      return address
         [rbp]                          saved rbp
         [rbp - 8*(j+1)]                local slot argSlots + j
```

Results come back in `rax`. All registers are caller-saved. Symbols are
`owner.name(descriptor)`, for example `duke/kernel/Console.print(J)V`.

### Objects

```
object:  [0] TIB pointer   [8...] fields (superclass first, then largest-first)
array:   [0] TIB pointer   [8] int length   [12] padding   [16...] elements
TIB:     [0] header (Class's TIB)   [8] super TIB   [16] size   [20] flags   [24] element TIB
         [32] name (a String)   [40] interface list   [48] itable   [56] reference field offsets
         [64...] vtable
```

### Dispatch

`invokevirtual` compiles to a direct call whenever class hierarchy analysis proves a single
target (the method is private or final, its class is final, or nothing overrides it). Otherwise
it loads the receiver's TIB and calls through `[tib + 64 + 8*slot]`. Slots come from `Vtables`:
superclass slots first, overrides reuse the inherited slot.

Vtables don't defeat tree-shaking. The compiler records every dispatched `(owner, name,
descriptor)` and, after each round of compilation, compiles the implementation every reachable
subclass would select, repeating until nothing new turns up. Slots nothing dispatches through stay
0.

Interface calls use a global selector per method name and descriptor called through any interface.
Every class that implements interfaces gets an itable indexed by selector, so `invokeinterface`
is load TIB, load itable, call `[itable + 8*selector]`. Constant time, at the cost of mostly-empty
tables, which is cheap while the kernel has a few hundred classes. Selection follows JVMS 5.4.6,
so default methods, `I.super.m()` and `invokevirtual` calls that land on an inherited default all
take the same path.

`new C` compiles to a call to `duke.rt.Heap.allocateObject(tib, size)`, plain Java bumping a
pointer (see Memory above for where the memory comes from). Nothing is freed yet.

A TIB is also its type's `java.lang.Class` object: word 0 is an ordinary object header pointing
at `Class`'s own TIB. So `getClass()` is one load, `Foo.class` is the TIB's address, and
`java.lang.Class` has no fields. It reads everything through `duke.rt.Tib`.

String literals are prebuilt objects in `.data`, Latin-1 only for now. `String`'s `value` field
layout is part of the contract between `dukec` and `kernel/src/java/lang/String.java`.

### Lambdas

`LambdaCompiler` does `LambdaMetafactory`'s job at build time. For each lambda or method reference
call site, it uses the JDK's `java.lang.classfile` builder to generate a real class:
`Caller$$Lambda$N`, which implements the functional interface, has a field per captured value, and
has a static `create` factory. Non-capturing lambdas get a singleton. The generated class goes
into the class pool like any other, and the `invokedynamic` compiles to `invokestatic create`.
Boxing, unboxing, casts and widening between the interface's erased signature and the
implementation are emitted as ordinary bytecode in the generated method.

`java.lang.invoke` in the kernel is stubs: javac needs the names to exist, nothing calls them.

### Class initialization

Classes initialize lazily, with JVM semantics (JVMS 5.5): on the first `new`, static field access
or static call, superclass first. Each class whose initialization runs code gets a one-byte flag
and a stub. Trigger sites compile to `cmp byte [flag], 1; je skip; call stub`. No check is
emitted for classes with nothing to run, or inside the class itself or a subclass. The stub moves
the flag from 0 to "initializing" with a `lock cmpxchg` before `<clinit>` runs, so only one
thread on any CPU wins, and records the thread doing it. Another thread that arrives meanwhile waits in
`Runtime.awaitInitialization`; the initializing thread itself carries on, so cycles and
self-references see default values exactly as on HotSpot. That wait path calls into `Runtime`
and `Scheduler`, so the compiler refuses to give either of them a runtime initializer.

Some initializers never run at boot. `BuildTimeInit` interprets a `<clinit>` at compile time when
it's straight-line code that only builds constants: pushes, array creation, array loads and
stores, and the class's own statics. The resulting arrays are emitted into `.data` with the normal
heap layout, the fields point at them, and the class needs no init stub or checks at all. Anything
else (a call, a branch, `new`) keeps lazy runtime init. This is safe because such an initializer
has no effect outside its own fields, so when it runs is unobservable. A fuller image heap
(running arbitrary initializers on the host, GraalVM-style) would need a heap snapshotter and a
policy for which classes are safe. That waits until something needs it.

### Extra roots

The compiler starts from the entry point, so a class nothing refers to isn't in the image. For code
that has to be there anyway (generated modules that register themselves, say), `dukec --include`
adds roots. Each is a class (`--include pkg/Class`) or, with a trailing slash, every class under a
package prefix (`--include pkg/`), and may be repeated. An included class is compiled into the image
and, if it has a static initializer, `_start` runs it before the entry point, after the entry class's
own. `duke/js/gen/` is included by default, so the generated JavaScript modules need no flag. A name
that isn't in the class pool is a compile error; a package prefix that matches nothing is not.
Initializers run that early can't rely on anything the entry point sets up, so they should only
register work for later.

### Runtime checks

Null dereferences, array bounds and division by zero are checked inline, and on failure call
`duke.rt.Runtime` inline too, which throws the JDK's exception with the JDK's message. The call
being inline matters: its return address sits inside the right `try` range and source line.

### Exceptions

`athrow` calls `duke.rt.Exceptions.raise`, plain Java. It walks the rbp chain and, for each
frame, looks the return address up in the method table, then in that method's exception table:
rows of (start, end, handler, catch TIB) in x86 code offsets, translated from the class file's
exception table after codegen. On a match it calls the `resumeAt` intrinsic. That resets rsp to
the frame's base (rbp minus its locals), sets rbp, pushes the exception (the JVM's handler entry
state) and jumps. `finally`, multi-catch and try-with-resources are just javac's bytecode on top.
Unwinding stops at an interrupt entry, since exceptions can't propagate out of an interrupt.

Stack overflow is a real, catchable `StackOverflowError`. Every prologue compares rsp against
the stack limit, which sits 16 KiB above the bottom of the stack. Crossing it calls a stub that lowers
the limit to 4 KiB above the bottom and throws from inside that reserve. The unwinder restores the
normal limit when it resumes in a handler. Overflowing the reserve too panics instead of
corrupting memory. The check is 20 bytes per method.

The limit, the saved limit during a preemption request and the stack base live in a per-CPU block
that GS points at (`IA32_GS_BASE`), so every CPU checks against its own running thread's stack. The
compiler addresses them as `gs:[offset]`, the same cost as the RIP-relative globals they replaced
plus a prefix byte. `_start` points GS at the boot CPU's block before any Java runs, and
`Magic.loadSegments` leaves GS alone, since loading a selector into it would zero the base.

### Other CPUs

`Smp.start` brings up the application processors through Limine's MP request. Limine starts them
and parks each one spinning on its `limine_mp_info`, on Limine's page tables and stacks in
bootloader-reclaimable memory, so this has to happen before the kernel reclaims that memory, and
`Kernel.init` now reclaims last. For each CPU the boot CPU prepares a block (its GS), a stack from
`KernelStacks`, and a GDT, TSS and double-fault stack, then writes the block into the info's extra
argument and `Magic.apEntry` into its goto address. The entry stub reads CR3 and the stack from
the block (image data, so Limine's tables map it too), moves onto them, points GS at the block and
calls `Smp.apMain`, which loads the CPU's GDT and the shared IDT, starts its local APIC and timer,
checks in and becomes the idle thread `Smp.start` created for it, on the stack it already has.

An uncaught exception panics with `uncaught <toString>`, its stack trace and its causes.
`Throwable` captures return addresses at construction. The compiler flags Throwable constructors
and runtime plumbing as hidden so traces start where the JDK's would. `OutOfMemoryError` is still a
panic: there's no memory left to build it in.

### Backtraces

The compiler emits a method table (start, size, name, source file, line table) for every function
in `.text`, sorted by address. Line tables come from `LineNumber` entries seen during codegen,
plus a row for each slow-path stub carrying its site's line. `duke.rt.Backtrace` walks the rbp
chain (every method keeps a frame pointer, and `_start` zeroes rbp) and binary-searches the table.
Panics and CPU exception reports print Java-style frames:

```
PANIC: ArithmeticException: / by zero
  at duke.rt.Runtime.divideByZero(Runtime.java:23)
  at duke.panics.DeepTrace.inner(DeepTrace.java:15)
  at duke.panics.DeepTrace.middle(DeepTrace.java:19)
```

`checkcast`, `instanceof` and reference array stores decide exact TIB matches inline (plus null,
and stores into `Object[]`) and otherwise call `duke.rt.Types`, which walks the super chain, the
TIB's interface list, or array element types.

### Intrinsics

Native methods on `duke.rt.Magic` are compiled inline: port I/O (`outb`/`inb` and the wider
forms), raw memory access (`peek*`/`poke*`), `addressOf`, `halt`, `disableInterrupts`,
`enableInterrupts` and `pause`. A native method anywhere else is a compile error.

## What compiles today

Static, instance, virtual and interface methods (including defaults), exceptions, the core of
`java.util` (collections, `Arrays`, `Comparator`, `java.util.function`), type checks and casts, boxing, string concatenation, lambdas and method references, object and array allocation
(including multi-dimensional), constructors, int/long/boolean/byte/char/short arithmetic with Java semantics, all control flow
including both switch forms, static and instance fields, array loads and stores, string literals
and `String.length`/`charAt`.

Not yet, and a clear compile error: floating point.

String concatenation works because the kernel compiles with `javac -XDstringConcat=inline`, which
turns `+` into `StringBuilder` calls instead of an `invokedynamic`. Every place that compiles
kernel sources has to pass it: the Makefile, `tools/Harness.java` and `CompilerTest`.

## Testing

- `make unit-test` runs encoder golden tests, ELF structure checks, and compiler error-message
  tests.
- `make boot-test` boots the real kernel and waits for `DUKE-BOOT-OK` on serial.
- `make conformance` runs `tools/Conformance.java`, which executes every test in
  `tests/conformance` on HotSpot, generates a kernel that runs the same tests, boots it and
  compares the results. Add a test by adding a non-private, no-argument static method with a
  primitive result.
- `make ktest` runs every `static void test*()` in `tests/kernel` inside one booted kernel, for
  code HotSpot can't run (physical memory, page tables, interrupts). Failures are thrown
  `AssertionError`s, caught per test. The kernel exits QEMU through `isa-debug-exit` with the
  overall result, so the harness reads an exit status instead of watching serial.
- `make panic-tests` boots one kernel per file in `tests/panics` and checks the output contains
  the lines in the file's leading `// expect:` comments, in order: the panic line, then
  optionally backtrace frames. This covers the failure side of every
  runtime check (null, bounds, division, casts, array stores, allocation).

## jsc, the JavaScript compiler

`jsc program.js --output program` (`compiler/src/main/java/duke/js`) compiles a subset of
JavaScript straight to x86-64 and writes a static Linux ELF. It reuses dukec's `X64` encoder,
`Image` and `ElfWriter`, but has its own front end and nothing to do with the JVM or the kernel.

```
Lexer ─▶ Parser ─▶ Analyzer (scopes, captures, frame slots) ─▶ CodeGen (X64 into Image) ─▶ ElfWriter
```

- **Values** are 64-bit words: low bit 1 is an integer (`n << 1 | 1`), low bits `00` a pointer to a
  heap object, low bits `10` the constants undefined, null, false and true.
- **Closures** share variables through boxes: a variable some inner function uses lives in a heap
  box, and the closure holds the box pointers. Top-level variables and functions are fixed
  symbols in `.data`, so they are never captured. `for (let ...)` copies the box each iteration.
- **Calls** push `this`, the callee and the arguments left to right; the closure goes in rdi,
  `this` in rsi, the argument count in rdx. Callees read missing arguments as undefined.
- **The runtime is JavaScript.** `compiler/src/main/resources/duke/js/prelude.js` is compiled
  with every program and implements strings, arrays, objects, `console.log` and the standard
  library methods. It reaches the machine through intrinsics the compiler expands inline
  (`__peek`, `__poke`, `__syscall`, `__alloc`, `__arg` and a few more), the way the kernel uses
  `Magic`. Memory is a bump allocator over one `mmap`; nothing is freed.
- **Supported:** `let`/`const`/`var`, functions, arrows, closures, `this` and method calls, objects,
  arrays, strings, template literals, `if`/loops (`for`, `for...of`, `while`, `do`), `break`/`continue`,
  the usual operators, `console.log` (node-style formatting) and the common array and string methods.
- **Not yet:** floating point (numbers are 63-bit integers, so `/` truncates, and there is no NaN or
  Infinity), exceptions, classes and `new`, `switch`, destructuring, spread, regular expressions,
  non-ASCII strings, a garbage collector. Each is a clear compile error or a runtime failure with
  exit status 1. `console.log` doesn't group arrays of more than six items like node does, and
  object keys keep insertion order even when they look like integers.

### JavaScript in the kernel

`jsc --java DIR modules...` is a second back end. It translates each JS module to a Java class, and
those classes are compiled with the kernel's own sources, so `dukec` sees them as ordinary Java:

```
kernel/js/*.js ──jsc --java──▶ build/jsgen/duke/js/**/*.java ──┐
kernel/src/**/*.java ───────────────────────────────────────────┴─javac──▶ .class ──dukec──▶ kernel.elf
```

JS and Java share one heap, collector, calling convention and exception system, because to
`dukec` there is no difference. The translator (`JavaGen`) reuses the lexer, parser and analyzer.

- **Values** are plain Java objects: `Long` numbers, `String`, `Boolean`, `JsArray`, `JsObject`,
  `JsFunction`, Java `null` for undefined and `JS.NULL` for null. Operators and property access are
  calls into `duke.js.rt.JS`.
- **Variables:** top-level ones are static fields. Others are Java locals, or one-element arrays when
  a closure captures them, since Java lambdas need effectively final variables. Functions are lambdas,
  and every generated name carries a number, because Java forbids a lambda to shadow an enclosing name.
- **The runtime** (`kernel/src/duke/js/rt`) is plain Java with no kernel dependencies, so it also runs on
  HotSpot. That is what lets `make js-test` check the translator against node without booting anything.
- **How the kernel reaches it:** each generated module registers its top-level code with
  `duke.js.rt.Modules` from a static initializer. Nothing refers to the generated classes, so they are
  kept by `dukec`'s default include of `duke/js/gen/` (see Extra roots above), which runs those
  initializers at boot. When there is at least one module, jsc also generates `JsStartup`, whose
  initializer registers `JsHost.start()` with `duke.kernel.Startup`; `Kernel.main` runs the startup hooks
  just before the shell, and `JsHost.start()` runs the modules in alphabetical order by class name. So
  `Kernel` and `Shell` know nothing about JavaScript. They have two generic registries, `Startup` for boot
  hooks and `Commands` for shell commands, and a kernel built with no modules contains no JavaScript
  runtime at all. The entry point is still `Kernel.main`.
- **What JS can touch** is whatever `duke.kernel.JsHost` puts in the `Kernel` global: printing, uptime,
  free memory, and `Kernel.command(name, fn)`, which registers a shell command with `Commands`. It's
  deliberately small and explicit. `kernel/js/commands.js` has the first commands (`hello`, `fib`, `meminfo`).
- **Limits:** what the language covers is in [js-language.md](js-language.md). On the kernel's side, JS code
  must stay out of interrupt handlers, since those can't allocate, and the stack it runs on is small, so deep recursion in a command overflows it. The runtime keeps static state (the global
  table, the command registry, `console.log`'s formatting depth), so JS runs only on the thread that runs
  the shell; it is not safe to call from several threads yet.

`make js-test` runs every program in `tests/js` under node, as an x86-64 executable and through
the Java back end on this JVM, and compares output and exit status (off x86-64 Linux the
executables run in an amd64 Docker container; `JS_BACKENDS=java` skips them). `make shell-test`
runs the JS shell commands in the booted kernel.
