package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertThrows;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.Scheduler;
import duke.kernel.mm.AddressSpace;
import duke.kernel.mm.PageTable;
import duke.kernel.mm.PhysicalMemory;
import duke.kernel.user.SystemCalls;
import duke.rt.Magic;
import duke.rt.Tib;

final class CopyOnWriteTest {

    private static final long PAGE = PhysicalMemory.PAGE_SIZE;
    private static final int PAGES = 256;

    private static long view(AddressSpace space, long address) {
        return PhysicalMemory.toVirtual(space.table().translate(address));
    }

    static void testForkSharesPagesUntilOneSideWrites() {
        long before = PhysicalMemory.freeFrames();
        AddressSpace parent = new AddressSpace();
        parent.setMapRange(0x1000_0000L, 0x2000_0000L);
        long a = parent.map(PAGES * PAGE, PageTable.WRITABLE);
        for (int i = 0; i < PAGES; i++) {
            assertTrue(parent.pageIn(a + i * PAGE), "paged in");
            Magic.pokeLong(view(parent, a + i * PAGE), i);
        }
        long touched = PhysicalMemory.freeFrames();

        AddressSpace child = parent.copy();
        assertTrue(touched - PhysicalMemory.freeFrames() < 8, "only tables for the child, not " + PAGES + " pages");
        assertEquals(parent.table().translate(a), child.table().translate(a), "one frame for both");
        assertEquals(PageTable.COPY_ON_WRITE, parent.table().flags(a) & (PageTable.WRITABLE | PageTable.COPY_ON_WRITE), "the parent's is read-only now");
        assertEquals(PageTable.COPY_ON_WRITE, child.table().flags(a) & (PageTable.WRITABLE | PageTable.COPY_ON_WRITE), "and so is the child's");

        assertTrue(child.copyOnWrite(a + 8), "the child writes");
        assertTrue(parent.table().translate(a) != child.table().translate(a), "a frame of its own");
        assertTrue((child.table().flags(a) & PageTable.WRITABLE) != 0, "writable");
        Magic.pokeLong(view(child, a), 1000);
        assertEquals(0, Magic.peekLong(view(parent, a)), "the parent still sees what it wrote");

        long shared = parent.table().translate(a + PAGE);
        assertTrue(parent.copyOnWrite(a + PAGE), "the parent writes");
        assertEquals(shared, child.table().translate(a + PAGE), "the child keeps the shared frame");
        Magic.pokeLong(view(parent, a + PAGE), 2000);
        assertEquals(1, Magic.peekLong(view(child, a + PAGE)), "and what was in it");

        child.free();
        long alone = parent.table().translate(a + 2 * PAGE);
        long free = PhysicalMemory.freeFrames();
        assertTrue(parent.copyOnWrite(a + 2 * PAGE), "nobody else maps it now");
        assertEquals(alone, parent.table().translate(a + 2 * PAGE), "so it keeps the frame");
        assertEquals(free, PhysicalMemory.freeFrames(), "and copies nothing");
        assertTrue(parent.copyOnWrite(a + 2 * PAGE), "a second fault there finds it writable");
        assertEquals(2, Magic.peekLong(view(parent, a + 2 * PAGE)), "contents kept");

        parent.free();
        assertEquals(before, PhysicalMemory.freeFrames(), "every frame back");
    }

    static void testOnlyCopyOnWritePagesAreCopied() {
        UserModeTest.Program p = new UserModeTest.Program();
        try {
            assertTrue(!p.space.copyOnWrite(p.base), "the code page is read-only for good");
            assertTrue(!p.space.copyOnWrite(0x40_0000 + PAGE), "nothing mapped");
            assertTrue(p.space.copyOnWrite(p.data), "already writable");
        } finally {
            p.free();
        }
    }

    // CR0.WP: without it the kernel's copy would write straight into the frame the child sees.
    static void testKernelCopyStopsAtACopyOnWritePage() {
        UserModeTest.Program p = new UserModeTest.Program();
        AddressSpace child = p.space.copy();
        try {
            byte[] from = {1, 2, 3, 4, 5, 6, 7, 8};
            Magic.disableInterrupts();
            Scheduler.setAddressSpace(p.space);
            long left = Magic.copyUserMemory(p.data, Magic.addressOf(from) + Tib.ARRAY_DATA, from.length);
            Scheduler.setAddressSpace(null);
            Magic.enableInterrupts();
            assertEquals(8, left, "nothing copied");
            assertEquals(0, Magic.peekByte(view(child, p.data)), "the child's page untouched");
        } finally {
            child.free();
            p.free();
        }
    }

    static void testKernelPagesAreNeverCopied() {
        UserModeTest.Program p = new UserModeTest.Program();
        try {
            assertTrue(!p.space.copyOnWrite(Magic.addressOf(new int[1])), "writable, but the kernel's");
            assertTrue(!p.space.copyOnWrite(AddressSpace.END), "just past the lower half");
        } finally {
            p.free();
        }
    }

    static void testSystemCallWritesThroughACopyOnWritePage() {
        UserModeTest.Program p = new UserModeTest.Program();
        int offset = 64;
        p.load(new UserModeTest.Code().movEax(SystemCalls.GETCWD).movEdi((int) (p.data + offset)).movRsi(2)
                .syscall().exitWithResult());
        AddressSpace child = p.space.copy();
        try {
            assertEquals(2, p.run(), "getcwd bytes written");
            assertTrue(p.space.table().translate(p.data) != child.table().translate(p.data), "copied first");
            assertEquals('/', Magic.peekByte(p.kernelView(offset)) & 0xFF, "into the parent's copy");
            assertEquals(0, Magic.peekByte(view(child, p.data + offset)), "not the child's");
        } finally {
            child.free();
            p.free();
        }
    }

    static void testForkOutOfMemoryGivesEveryFrameBack() {
        UserModeTest.Program p = new UserModeTest.Program();
        // In another PML4 slot from the program, so the copy fails after sharing its pages.
        long far = 0x80_0000_0000L;
        p.space.allocate(far, PageTable.WRITABLE);
        // Grows the heap and the share counts now, so the copy below only takes frames for tables.
        p.space.copy().free();
        long data = p.space.table().translate(p.data);
        long[] taken = new long[(int) PhysicalMemory.freeFrames()];
        int n = 0;
        try {
            // The child's root, three tables for the program's pages and one of three for far's.
            while (PhysicalMemory.freeFrames() > 5) {
                taken[n++] = PhysicalMemory.allocate();
            }
            assertThrows(OutOfMemoryError.class, p.space::copy, "no table for far");
            assertEquals(5, PhysicalMemory.freeFrames(), "the child's frames back");
        } finally {
            for (int i = 0; i < n; i++) {
                PhysicalMemory.free(taken[i]);
            }
        }
        try {
            assertTrue(p.space.copyOnWrite(p.data), "the parent writes");
            assertEquals(data, p.space.table().translate(p.data), "its pages aren't shared any more");
        } finally {
            p.free();
        }
    }
}
