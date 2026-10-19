BUILD := build
DUKEC := compiler/build/install/dukec/bin/dukec
JSC   := compiler/build/install/dukec/bin/jsc

# v11.x-binary. Bump deliberately: the Limine protocol changes between majors.
LIMINE_REPO := https://github.com/Limine-Bootloader/limine.git
LIMINE_REV  := 5be26a73d7b7b4d4477d18be94e1d16e615adf56

OVMF ?= $(firstword $(wildcard \
	/opt/homebrew/share/qemu/edk2-x86_64-code.fd \
	/usr/local/share/qemu/edk2-x86_64-code.fd \
	/usr/share/OVMF/OVMF_CODE_4M.fd \
	/usr/share/OVMF/OVMF_CODE.fd \
	/usr/share/qemu/OVMF.fd))
export OVMF

KERNEL_SRCS   := $(shell find kernel/src -name '*.java')
# JavaScript modules: jsc translates them to Java under build/jsgen, and the kernel compiles that as
# one more source root. dukec includes duke/js/gen by default, so nothing refers to the generated code.
JS_SRCS       := $(wildcard kernel/js/*.js)
# Keep in sync with tools/Harness.java and CompilerTest. javac's default string concatenation is
# invokedynamic; inline makes it plain StringBuilder calls the compiler can handle.
KERNEL_JAVAC  := javac --system none -XDstringConcat=inline
COMPILER_SRCS := $(shell find compiler/src/main -name '*.java') compiler/build.gradle.kts

.PHONY: all compiler run test unit-test boot-test conformance conformance-gc panic-tests ktest shell-test js-test user-test disasm clean

all: $(BUILD)/esp/boot/kernel.elf

compiler: $(DUKEC)

$(DUKEC): $(COMPILER_SRCS)
	./gradlew -q :compiler:installDist
	touch $@

$(BUILD)/jsgen.stamp: $(JS_SRCS) $(DUKEC)
	rm -rf $(BUILD)/jsgen
	$(JSC) --java $(BUILD)/jsgen $(JS_SRCS)
	touch $@

$(BUILD)/kclasses.stamp: $(KERNEL_SRCS) $(BUILD)/jsgen.stamp
	rm -rf $(BUILD)/kclasses
	$(KERNEL_JAVAC) --module-source-path java.base=kernel/src:$(BUILD)/jsgen -d $(BUILD)/kclasses \
		$(KERNEL_SRCS) $$(find $(BUILD)/jsgen -name '*.java')
	touch $@

$(BUILD)/kernel.elf: $(DUKEC) $(BUILD)/kclasses.stamp
	$(DUKEC) --classes $(BUILD)/kclasses --entry duke/kernel/Kernel.main --output $@ --map $(BUILD)/kernel.map

$(BUILD)/limine/BOOTX64.EFI:
	rm -rf $(BUILD)/limine
	git init -q $(BUILD)/limine
	git -C $(BUILD)/limine fetch -q --depth=1 $(LIMINE_REPO) $(LIMINE_REV)
	git -C $(BUILD)/limine checkout -q FETCH_HEAD

$(BUILD)/esp/boot/kernel.elf: $(BUILD)/kernel.elf $(BUILD)/limine/BOOTX64.EFI boot/limine.conf
	tools/make-esp.sh $(BUILD)/esp $(BUILD)/limine/BOOTX64.EFI boot/limine.conf $(BUILD)/kernel.elf

run: all
	tools/qemu.sh $(BUILD)/esp -serial mon:stdio

test: unit-test js-test boot-test conformance panic-tests ktest shell-test user-test

unit-test:
	./gradlew -q :compiler:test

boot-test: all
	tools/boot-test.sh $(BUILD)/esp $(BUILD)/serial.log DUKE-BOOT-OK

conformance: $(DUKEC) $(BUILD)/limine/BOOTX64.EFI
	java tools/Conformance.java

# The suite again with a collection every GC_STRESS allocations: missing stack-map slots show up
# as wrong results or a GC panic.
GC_STRESS ?= 1
conformance-gc: $(DUKEC) $(BUILD)/limine/BOOTX64.EFI
	java tools/Conformance.java --gc-stress $(GC_STRESS)

panic-tests: $(DUKEC) $(BUILD)/limine/BOOTX64.EFI
	java tools/PanicTests.java

ktest: $(DUKEC) $(BUILD)/limine/BOOTX64.EFI
	java tools/KernelTests.java

shell-test: all
	java tools/ShellTest.java

# jsc programs against node, on Linux, the Java back end and Duke. Needs node, and the x86 back end
# needs Docker unless this is x86-64 Linux; JS_BACKENDS=java,duke skips it.
js-test: $(DUKEC) all
	java tools/JsTests.java

# Java programs built with dukec --user, against HotSpot, on Linux and Duke. The Linux run needs
# Docker unless this is x86-64 Linux; USER_BACKENDS=duke skips it.
user-test: $(DUKEC) all
	java tools/UserJavaTests.java

disasm: $(BUILD)/kernel.elf
	objdump -d -M intel $(BUILD)/kernel.elf | less

clean:
	rm -rf $(BUILD)
	./gradlew -q clean
