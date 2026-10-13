# Contributing

Glad you're here. Duke is a kernel written in Java, compiled by its own ahead-of-time compiler,
so a lot of everyday work is "make this piece of `java.lang` or `java.util` exist" or "teach the
kernel one more thing". You don't need to know the whole system to help.

## Finding something to work on

- [`good first issue`](https://github.com/JackFurton/who-would-build-a-kernel-in-java/labels/good%20first%20issue)
  issues are small and self-contained, and say how to prove they're done.
- Comment `@duke-bot claim` on an issue to take it before you start, so two people don't build
  the same thing. duke-bot assigns you within a minute; `@duke-bot release` gives it back if
  you can't finish.
- The [project board](https://github.com/users/JackFurton/projects/1) shows everything open and
  what's done.
- Bigger work is grouped into [milestones](https://github.com/JackFurton/who-would-build-a-kernel-in-java/milestones).
  Ask in the issue if you want to take one on.

## Setup

You need JDK 25, `make`, `git`, `qemu-system-x86_64`, x86_64 UEFI firmware, and `node` (the `jsc` tests compare against it).

- macOS: `brew install openjdk qemu` (Homebrew's QEMU includes the firmware).
- Debian/Ubuntu: `apt install openjdk-25-jdk qemu-system-x86 ovmf`.
- Anywhere else, or if you'd rather not install anything: open the repo in GitHub Codespaces
  (Code, then Codespaces) or in VS Code's "Reopen in Container". `.devcontainer/` sets up the same
  toolchain CI uses.

```sh
make run     # boot to the shell (type help); Ctrl-A X quits QEMU
make test    # everything CI runs
```

## How the code fits together

- `kernel/src` is the kernel *and* its own `java.lang`/`java.util`. There's no JDK underneath, so
  if a class or method doesn't exist there, it doesn't exist.
- `compiler/` is `dukec`, which turns the kernel's bytecode into x86-64.
- `kernel/js` is kernel code in JavaScript. `jsc` turns it into Java at build time and `dukec` compiles that
  with everything else; `Kernel.command("name", fn)` adds a shell command. A kernel with no files there has
  no JavaScript in it. See
  [JavaScript in the kernel](docs/architecture.md#javascript-in-the-kernel).
- [docs/architecture.md](docs/architecture.md) explains the details.

## Tests

| Suite | What it is | Add a test when |
| --- | --- | --- |
| `make conformance` | `tests/conformance`: each static method runs on HotSpot and in the kernel; results must match | You add or change library behaviour (`java.lang`, `java.util`) or compiler features |
| `make ktest` | `tests/kernel`: tests that run inside the booted kernel | You touch memory, interrupts, devices |
| `make panic-tests` | `tests/panics`: kernels that must die with a specific message | You add a failure path |
| `make shell-test` | Types into the real shell over serial and PS/2 | You add a shell command, in Java or JavaScript |
| `make js-test` | `tests/js`: JavaScript programs compiled by `jsc`; output must match node | You touch `compiler/.../duke/js` |
| `make unit-test` | JUnit tests for the compiler | You touch `compiler/` |

A conformance test is a non-private, no-argument static method returning a primitive or a
single-line `String`. Because HotSpot is the reference, behaviour and exception messages must match
the JDK exactly. That's the point.

Library code runs in a kernel: no floating point yet, Strings are Latin-1, and interrupt handlers
must not allocate.

## Pull requests

- `master` only changes through pull requests: CI has to pass and someone has to approve.
- One issue per PR; put `Closes #N` in the description.
- Run `make test` before pushing. CI runs the same targets on your PR automatically (GitHub may
  hold the very first run from a brand-new account for approval, as a spam guard).
- Match the surrounding code: comments explain *why*, not *what*.
- CI fails on trailing whitespace, including whitespace-only lines. `git diff --check master`
  shows any locally, and most editors follow `.editorconfig` to avoid it.
- Maintainers: "Enable auto-merge" on an approved PR (or `gh pr merge --auto --squash`) merges it
  as soon as CI is green, so nobody has to wait around to click.
