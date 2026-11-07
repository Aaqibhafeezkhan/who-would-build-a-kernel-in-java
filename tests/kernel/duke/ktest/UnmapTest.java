package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.Scheduler;
import duke.kernel.Smp;
import duke.kernel.mm.AddressSpace;
import duke.kernel.mm.PageTable;
import duke.kernel.mm.PhysicalMemory;
import duke.rt.Magic;

final class UnmapTest {

    private static final long PAGE = PhysicalMemory.PAGE_SIZE;
    private static final long FLOOR = 0x1000_0000L;
    private static final long TOP = 0x2000_0000L;

    static void testUnmapFreesFramesAndLeavesAHole() {
        long before = PhysicalMemory.freeFrames();
        AddressSpace space = new AddressSpace();
        space.setMapRange(FLOOR, TOP);
        long a = space.map(3 * PAGE, PageTable.WRITABLE);
        assertEquals(TOP - 3 * PAGE, a, "top down");
        for (int i = 0; i < 3; i++) {
            assertTrue(space.pageIn(a + i * PAGE), "paged in");
        }
        long touched = PhysicalMemory.freeFrames();

        space.unmap(a + PAGE, PAGE);
        assertEquals(touched + 1, PhysicalMemory.freeFrames(), "the middle page's frame back");
        assertEquals(-1, space.table().translate(a + PAGE), "unmapped");
        assertTrue(!space.pageIn(a + PAGE), "no longer reserved");
        assertTrue(space.table().translate(a) >= 0 && space.table().translate(a + 2 * PAGE) >= 0, "either side kept");
        assertEquals(a + PAGE, space.map(PAGE, PageTable.WRITABLE), "the hole is the highest gap");

        space.unmap(FLOOR, TOP - FLOOR);
        assertEquals(touched + 3, PhysicalMemory.freeFrames(), "every page back, tables kept");
        assertTrue(!space.pageIn(a), "nothing reserved");
        assertEquals(TOP - PAGE, space.map(PAGE, PageTable.WRITABLE), "all of it free again");
        space.free();
        assertEquals(before, PhysicalMemory.freeFrames(), "tables and root back too");
    }

    // A gigabyte reserved and never touched: nextMapped skips the missing tables instead of
    // walking 262144 pages.
    static void testUnmapSkipsWhatWasNeverTouched() {
        AddressSpace space = new AddressSpace();
        space.setMapRange(0x40_0000_0000L, 0x80_0000_0000L);
        long a = space.map(1L << 30, PageTable.WRITABLE);
        assertTrue(space.pageIn(a + (1L << 29)), "one page in the middle");
        assertEquals(a + (1L << 29), space.table().nextMapped(a, a + (1L << 30)), "found");
        assertEquals(a + (1L << 30), space.table().nextMapped(a + (1L << 29) + PAGE, a + (1L << 30)), "nothing after it");
        space.unmap(a, 1L << 30);
        assertEquals(-1, space.table().translate(a + (1L << 29)), "gone");
        space.free();
    }

    /**
     * A thread on another CPU reads a page, so its TLB holds the translation, then reads it again
     * after this one unmaps it: it must find it gone. A run where the two ever shared a CPU proves
     * nothing, since switching between them reloads CR3, so those are retried.
     */
    static void testUnmapReachesAnotherCpusTlb() throws InterruptedException {
        if (Smp.onlineCount() < 2) {
            return;
        }
        for (int attempt = 0; attempt < 20; attempt++) {
            UserModeTest.Program p = new UserModeTest.Program();
            Magic.pokeInt(p.kernelView(0), 42);
            int[] cpus = {-1, -1};
            int[] go = {0};
            long[] seen = new long[2];
            Thread reader = new Thread(() -> {
                Magic.disableInterrupts();
                Scheduler.setAddressSpace(p.space);
                seen[0] = Magic.loadUserInt(p.data);
                cpus[0] = Magic.cpuIndex();
                // On, to take the flush; a preemption here shows up as a different CPU below.
                Magic.enableInterrupts();
                while (go[0] == 0) {
                    Magic.pause();
                }
                Magic.disableInterrupts();
                seen[1] = Magic.loadUserInt(p.data);
                cpus[1] = Magic.cpuIndex();
                Scheduler.setAddressSpace(null);
                Magic.enableInterrupts();
            });
            reader.start();
            while (cpus[0] < 0) {
                Thread.sleep(1);
            }
            Magic.disableInterrupts();
            int here = Magic.cpuIndex();
            Magic.enableInterrupts();
            p.space.unmap(p.data, PAGE);
            go[0] = 1;
            reader.join();
            p.free();
            assertEquals(42, seen[0], "read before");
            if (here == cpus[0] || cpus[0] != cpus[1]) {
                continue;
            }
            assertEquals(-1, seen[1], "gone after, from cpu " + cpus[1] + " while cpu " + here + " unmapped it");
            return;
        }
        throw new AssertionError("never got the two threads onto different CPUs");
    }
}
