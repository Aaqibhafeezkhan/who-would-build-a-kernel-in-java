# Attach to `make debug`: x86_64-elf-gdb -x tools/debug.gdb, from the repo root.
file build/kernel.elf
directory kernel/src
target remote localhost:1234
