// expect: PANIC: #PF page fault (vector 14, error 0x0) at address 0xffffe00000000000
package duke.panics;

import duke.kernel.Serial;
import duke.kernel.x86.Idt;
import duke.rt.Magic;
import duke.rt.Tib;

/** Only a user address is the system call's to fail: a kernel one faulting in the copy is a kernel bug. */
final class UserCopyFromKernel {
    static void main() {
        Serial.init();
        Idt.load();
        byte[] into = new byte[8];
        Magic.copyUserMemory(Magic.addressOf(into) + Tib.ARRAY_DATA, 0xffff_e000_0000_0000L, 8);
    }
}
