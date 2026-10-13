package duke.kernel;

import duke.kernel.x86.Interrupts;
import duke.kernel.x86.IoApic;
import duke.kernel.x86.LocalApic;
import duke.rt.Magic;

/** 16550 UART on COM1, polled. */
public final class Serial {

    private static final int COM1 = 0x3F8;
    private static final int LINE_STATUS = COM1 + 5;
    private static final int TX_EMPTY = 0x20;
    private static final int NORMAL = 0;
    private static final int ESCAPE = 1;
    private static final int CSI = 2;

    // A key sequence can arrive across multiple receive interrupts.
    private static int inputState = NORMAL;
    private static int sequenceParameter;

    private Serial() {
    }

    public static void init() {
        Magic.outb(COM1 + 1, 0x00); // no interrupts
        Magic.outb(COM1 + 3, 0x80); // DLAB on
        Magic.outb(COM1 + 0, 0x01); // divisor 1: 115200 baud
        Magic.outb(COM1 + 1, 0x00);
        Magic.outb(COM1 + 3, 0x03); // 8N1, DLAB off
        Magic.outb(COM1 + 2, 0xC7); // FIFO on, cleared, 14-byte threshold
        Magic.outb(COM1 + 4, 0x03); // DTR + RTS
    }

    public static final int VECTOR = 0x24;
    private static final int DATA_READY = 0x01;
    private static final int RECEIVE_INTERRUPT = 0x01;

    /** Turns on receive interrupts (IRQ 4) feeding Input; carriage returns arrive as newlines. */
    public static void enableInput() {
        Interrupts.register(VECTOR, frame -> {
            while ((Magic.inb(LINE_STATUS) & DATA_READY) != 0) {
                int c = Magic.inb(COM1);
                receive(c);
            }
            LocalApic.endOfInterrupt();
        });
        IoApic.routeIrq(4, VECTOR);
        // OUT2 gates the UART's interrupt line on PC-compatible hardware.
        Magic.outb(COM1 + 4, 0x0B);
        Magic.outb(COM1 + 1, RECEIVE_INTERRUPT);
    }

    private static void receive(int c) {
        if (c == 0x1B) {
            inputState = ESCAPE;
            return;
        }

        if (inputState == ESCAPE) {
            inputState = NORMAL;
            if (c == '[') {
                inputState = CSI;
                sequenceParameter = 0;
                return;
            }
        } else if (inputState == CSI) {
            if (c >= 0x20 && c <= 0x3F) {
                // Only the single parameter 3 is supported (Delete).
                if (sequenceParameter == 0 && c == '3') {
                    sequenceParameter = 3;
                } else {
                    sequenceParameter = -1;
                }
                return;
            }

            inputState = NORMAL;
            if (c >= 0x40 && c <= 0x7E) {
                int key = 0;
                if (sequenceParameter == 0) {
                    switch (c) {
                        case 'D': key = Input.KEY_LEFT; break;
                        case 'C': key = Input.KEY_RIGHT; break;
                        case 'H': key = Input.KEY_HOME; break;
                        case 'F': key = Input.KEY_END; break;
                    }
                } else if (sequenceParameter == 3 && c == '~') {
                    key = Input.KEY_DELETE;
                }

                if (key != 0) {
                    Input.put(key);
                }
                return;
            }
        }

        Input.put(c == '\r' ? '\n' : c);
    }

    public static void write(int b) {
        while ((Magic.inb(LINE_STATUS) & TX_EMPTY) == 0) {
            Magic.pause();
        }
        Magic.outb(COM1, b);
    }
}
