package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.Scheduler;
import duke.kernel.mm.PageTable;
import duke.kernel.mm.PhysicalMemory;
import duke.rt.Magic;
import duke.rt.Tib;

/**
 * Magic.copyUserMemory and Magic.loadUserInt on a process's table, as a system call uses them: a
 * user page that isn't there ends the access instead of panicking the kernel.
 */
final class UserAccessTest {

    private static final long PAGE = PhysicalMemory.PAGE_SIZE;
    /** Between the program's code and data pages: never mapped. */
    private static final long HOLE = 0x40_0000 + PAGE;

    private static long copy(UserModeTest.Program p, long destination, long source, long bytes) {
        Magic.disableInterrupts();
        Scheduler.setAddressSpace(p.space);
        long left = Magic.copyUserMemory(destination, source, bytes);
        Scheduler.setAddressSpace(null);
        Magic.enableInterrupts();
        return left;
    }

    private static long load(UserModeTest.Program p, long address) {
        Magic.disableInterrupts();
        Scheduler.setAddressSpace(p.space);
        long value = Magic.loadUserInt(address);
        Scheduler.setAddressSpace(null);
        Magic.enableInterrupts();
        return value;
    }

    private static long data(byte[] array) {
        return Magic.addressOf(array) + Tib.ARRAY_DATA;
    }

    static void testCopyOutStopsWhereThePageEnds() {
        UserModeTest.Program p = new UserModeTest.Program();
        try {
            byte[] from = new byte[48];
            for (int i = 0; i < from.length; i++) {
                from[i] = (byte) (i + 1);
            }
            assertEquals(32, copy(p, p.data + PAGE - 16, data(from), from.length), "bytes past the data page left");
            assertEquals(16, Magic.peekByte(p.kernelView((int) PAGE - 1)), "the last byte that fit");
        } finally {
            p.free();
        }
    }

    static void testCopyInFromAHole() {
        UserModeTest.Program p = new UserModeTest.Program();
        try {
            byte[] into = new byte[8];
            assertEquals(8, copy(p, data(into), HOLE, into.length), "nothing copied");
            assertEquals(0, copy(p, data(into), HOLE, 0), "an empty copy touches nothing");
        } finally {
            p.free();
        }
    }

    static void testCopyPagesInAReservation() {
        UserModeTest.Program p = new UserModeTest.Program();
        try {
            long reserved = 0x2000_0000L;
            p.space.reserve(reserved, PAGE, PageTable.WRITABLE);
            byte[] from = {7, 8, 9};
            assertEquals(0, copy(p, reserved + 100, data(from), from.length), "all copied");
            long frame = p.space.table().translate(reserved);
            assertTrue(frame >= 0, "paged in");
            assertEquals(9, Magic.peekByte(PhysicalMemory.toVirtual(frame) + 102), "landed");
        } finally {
            p.free();
        }
    }

    static void testLoadUserInt() {
        UserModeTest.Program p = new UserModeTest.Program();
        try {
            Magic.pokeInt(p.kernelView(8), -2);
            assertEquals(0xFFFF_FFFEL, load(p, p.data + 8), "zero-extended, so it can't be mistaken for -1");
            assertEquals(-1L, load(p, HOLE), "missing");
        } finally {
            p.free();
        }
    }
}
