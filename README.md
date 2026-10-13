# who would build a kernel in Java?

[![CI](https://github.com/JackFurton/who-would-build-a-kernel-in-java/actions/workflows/ci.yml/badge.svg)](https://github.com/JackFurton/who-would-build-a-kernel-in-java/actions/workflows/ci.yml)

Us, apparently. **Duke** is an x86_64 kernel written in Java, with no JVM underneath. `dukec`, an
ahead-of-time compiler that is also written in Java, turns the kernel's bytecode straight into
machine code and a bootable ELF. Limine boots it. No C, no assembler, no linker.

```
kernel/src/**/*.java ──javac --system none──▶ .class ──dukec──▶ kernel.elf ──Limine (UEFI)──▶ QEMU / hardware
```

Right now it boots on every CPU, handles interrupts and exceptions in Java, and agrees with HotSpot
on every test in the conformance suite: objects, virtual and interface dispatch, lambdas, boxing,
string concatenation, try/catch/finally, stack overflow. Java threads run preemptively on all CPUs
at once, with monitors and a garbage collector. It drives the timer, keyboard, serial port,
framebuffer, PCI and virtio disks, reads FAT32 and FAT16, and has a shell.

User programs run in ring 3, each in its own address space. Static x86-64 Linux executables load
from disk and make Linux system calls, so the programs `jsc` compiles from JavaScript print the same
output on Duke as on Linux and under node. Processes (`fork`, `execve`, file descriptors) are next
on the [roadmap](https://github.com/JackFurton/who-would-build-a-kernel-in-java/milestones).

```
Duke: hello from Java on bare metal
bytecode arithmetic check: 6 * 7 = 42
interrupts: IDT loaded, 2 breakpoints handled and resumed
exceptions: caught java.lang.NumberFormatException: For input string: "forty-two"
...
duke> cat hello.txt
hello from FAT32
duke> run /bin/hello
hello from ring 3, I am /bin/hello
duke> run bin/crash
bin/crash: #GP general protection at 0x4000e8 (error 0x0)
```

## Progress

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/JackFurton/who-would-build-a-kernel-in-java/metrics/progress-dark.svg">
  <img alt="Tests in the suites, boot kernel code size and lines of Java over time" src="https://raw.githubusercontent.com/JackFurton/who-would-build-a-kernel-in-java/metrics/progress-light.svg">
</picture>

CI appends a row for every commit to master. The data is
[metrics.csv](https://github.com/JackFurton/who-would-build-a-kernel-in-java/blob/metrics/metrics.csv)
on the `metrics` branch.

## How it works

- **The kernel is its own `java.base`.** Kernel sources compile with `javac --system none`, so
  `java.lang.Object` and `java.lang.String` are ours, in `kernel/src/java/lang`. Nothing from the
  JDK ends up in the image.
- **`dukec` compiles the whole program.** It reads class files with `java.lang.classfile`, starts
  at `Kernel.main`, compiles every reachable method to x86-64, lays out objects, string literals
  and class metadata, and writes the ELF itself.
- **Hardware access goes through intrinsics.** `duke.rt.Magic` declares native methods like
  `outb`, `peekLong` and `halt` that the compiler replaces with inline instructions.
- **Correctness is tested differentially.** Every test in `tests/conformance` runs on HotSpot and
  inside the booted kernel. The results must match exactly.

The details (calling convention, object layout, boot sequence) are in
[docs/architecture.md](docs/architecture.md).

## Building

You need JDK 25, `make`, `git`, QEMU (`qemu-system-x86_64`) and x86_64 UEFI firmware.
Homebrew's `qemu` ships the firmware. On Debian or Ubuntu, `apt install qemu-system-x86 ovmf`
covers both. Put the firmware somewhere unusual? Set `OVMF=/path/to/OVMF_CODE.fd`.

```sh
make run          # build and boot to a shell on your terminal (try help); Ctrl-A X quits
make test         # unit tests, jsc vs node, boot, conformance, panic, kernel and shell suites
make disasm       # objdump the kernel (symbols included)
```

The first build downloads a pinned Limine release into `build/limine`.

## Layout

| Path | What |
| --- | --- |
| `compiler/` | `dukec`: class loading, code generation (`MethodCompiler`), x86-64 encoder (`asm/`), ELF writer (`image/`) |
| `kernel/src/` | The kernel's `java.base`: `java.lang`, `duke.rt` (runtime and intrinsics), `duke.kernel` |
| `compiler/src/main/java/duke/js/` | `jsc`: a JavaScript-subset compiler, to x86-64 Linux executables or to Java for the kernel (see [architecture](docs/architecture.md#jsc-the-javascript-compiler)) |
| `kernel/js/` | Kernel code written in JavaScript: shell commands so far |
| `tests/conformance/` | Differential tests, run on HotSpot and in the kernel |
| `tests/panics/` | Kernels that must die with a specific panic and backtrace |
| `tests/kernel/` | Tests that run inside the booted kernel (`make ktest`) |
| `tests/js/` | JavaScript programs `jsc` must run like node does: on Linux, the JVM and Duke (`make js-test`) |
| `tools/` | ESP layout, QEMU launch, test harnesses, metrics and the progress chart |
| `boot/` | Limine config |

## Credits

The framebuffer font is [Spleen](https://github.com/fcambus/spleen) 8x16 by Frederic Cambus,
BSD-2-Clause; the license travels with it in `kernel/src/duke/kernel/Font.java`.

## Contributing

New contributors are welcome. [CONTRIBUTING.md](CONTRIBUTING.md) covers setup and how the tests
work, and [`good first issue`](https://github.com/JackFurton/who-would-build-a-kernel-in-java/labels/good%20first%20issue)
lists small, self-contained places to start. The [project board](https://github.com/users/JackFurton/projects/1)
shows what's open and what's done.
