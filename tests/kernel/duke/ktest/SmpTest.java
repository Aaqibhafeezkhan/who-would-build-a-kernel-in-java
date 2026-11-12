package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertTrue;

import duke.kernel.Scheduler;
import duke.kernel.Smp;
import duke.kernel.acpi.Madt;
import duke.kernel.time.HpetClock;
import duke.rt.Backtrace;
import duke.rt.Heap;
import duke.rt.Magic;
import duke.rt.Tib;

final class SmpTest {

    static void testEveryCpuCheckedIn() {
        assertEquals(Madt.cpus().size(), Smp.cpuCount(), "Limine started every CPU the MADT lists");
        assertEquals(Smp.cpuCount(), Smp.onlineCount(), "every CPU checked in");
    }

    static void testEachCpuHasItsOwnApic() {
        for (int i = 0; i < Smp.cpuCount(); i++) {
            for (int j = i + 1; j < Smp.cpuCount(); j++) {
                assertTrue(Smp.apicId(i) != Smp.apicId(j), "cpus " + i + " and " + j + " share an APIC id");
            }
        }
    }

    static void testCpuIndexComesFromTheBlock() {
        assertEquals(Magic.peekLong(Magic.cpuBlock() + 32), (long) Magic.cpuIndex(), "cpu index");
    }

    static void testExchangeReturnsTheOldValue() {
        int[] word = {7};
        long address = Magic.addressOf(word) + Tib.ARRAY_DATA;
        assertEquals(7, Magic.exchangeInt(address, 1), "first exchange");
        assertEquals(1, Magic.exchangeInt(address, 0), "second exchange");
        assertEquals(0, word[0], "stored");
    }

    // Whichever CPU's timer fires first after the deadline wakes a sleeper, so a thread that
    // sleeps often lands on more than one of them.
    static void testThreadsMoveBetweenCpus() throws InterruptedException {
        if (Smp.onlineCount() < 2) {
            return;
        }
        long[] seen = new long[1];
        Thread sleeper = new Thread(() -> {
            for (int i = 0; i < 40; i++) {
                seen[0] |= 1L << Magic.cpuIndex();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sleeper.start();
        sleeper.join();
        int cpus = 0;
        for (long bits = seen[0]; bits != 0; bits &= bits - 1) {
            cpus++;
        }
        assertTrue(cpus > 1, "ran on cpus 0x" + Long.toHexString(seen[0]));
    }

    private static volatile boolean stop;

    static void testThreadsRunAtTheSameTime() throws InterruptedException {
        if (Smp.onlineCount() < 2) {
            return;
        }
        stop = false;
        Thread[] spinners = new Thread[3];
        for (int i = 0; i < spinners.length; i++) {
            spinners[i] = new Thread(() -> {
                while (!stop) {
                    Magic.pause();
                }
            });
            spinners[i].start();
        }
        int most = 0;
        long until = HpetClock.nanos() + 2_000_000_000L;
        while (most < 4 && HpetClock.nanos() < until) {
            most = Math.max(most, Scheduler.busyCpus());
        }
        stop = true;
        for (Thread spinner : spinners) {
            spinner.join();
        }
        assertTrue(most >= 2, "at most " + most + " cpus busy at once");
    }

    static void testLocateFindsEveryOtherCpu() {
        for (int cpu = 0; cpu < Smp.cpuCount(); cpu++) {
            if (cpu != Magic.cpuIndex() && Smp.online(cpu)) {
                long[] where = Smp.locate(cpu);
                assertTrue(where != null, "cpu " + cpu + " answered");
                assertTrue(!Backtrace.describe(where[0]).startsWith("0x"), "cpu " + cpu + " in compiled code");
            }
        }
    }

    private static volatile int spinningOn;
    private static volatile boolean spinning;

    // What the NMI is for: a CPU that ignores the kick, spinning with interrupts off.
    static void testLocateFindsACpuWithInterruptsOff() throws InterruptedException {
        if (Smp.onlineCount() < 2) {
            return;
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            spinning = false;
            Thread spinner = new Thread(() -> {
                Magic.disableInterrupts();
                spinningOn = Magic.cpuIndex();
                spinning = true;
                // Bounded, in case it landed on the CPU that's meant to locate it.
                long until = HpetClock.nanos() + 1_000_000_000L;
                while (spinning && HpetClock.nanos() < until) {
                    Magic.pause();
                }
                spinning = false;
                Magic.enableInterrupts();
            });
            spinner.start();
            while (!spinning && spinner.isAlive()) {
                Magic.pause();
            }
            long[] where = spinning ? Smp.locate(spinningOn) : null;
            boolean stillSpinning = spinning;
            spinning = false;
            spinner.join();
            if (where != null && stillSpinning) {
                String at = Backtrace.describe(where[0]);
                assertTrue(at.contains("SmpTest"), "located at " + at);
                return;
            }
        }
        throw new AssertionError("the spinner never ran on another CPU long enough to locate");
    }

    // Four threads allocating at once, each checking its own lists, with collections stopping the others.
    static void testAllocationAndCollectionAcrossCpus() throws InterruptedException {
        long before = Heap.collections();
        boolean[] ok = new boolean[4];
        Thread[] workers = new Thread[ok.length];
        Heap.stress(2000);
        try {
            for (int t = 0; t < workers.length; t++) {
                int index = t;
                workers[t] = new Thread(() -> ok[index] = churn(index));
                workers[t].start();
            }
            for (Thread worker : workers) {
                worker.join();
            }
        } finally {
            Heap.stress(0);
        }
        for (int t = 0; t < ok.length; t++) {
            assertTrue(ok[t], "worker " + t + " saw its own data intact");
        }
        assertTrue(Heap.collections() - before >= 100, (Heap.collections() - before) + " collections ran meanwhile");
    }

    private static boolean churn(int seed) {
        for (int round = 0; round < 40; round++) {
            Node head = null;
            for (int i = 0; i < 2000; i++) {
                head = new Node(seed * 1_000_000 + i, new byte[256], head);
            }
            for (int i = 1999; i >= 0; i--) {
                if (head.value != seed * 1_000_000 + i || head.padding.length != 256) {
                    return false;
                }
                head = head.next;
            }
        }
        return true;
    }

    private static final class Node {
        final int value;
        final byte[] padding;
        final Node next;

        Node(int value, byte[] padding, Node next) {
            this.value = value;
            this.padding = padding;
            this.next = next;
        }
    }
}
