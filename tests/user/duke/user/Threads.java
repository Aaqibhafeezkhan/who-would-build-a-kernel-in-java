package duke.user;

/**
 * Threads that contend for monitors, hand values over with wait and notify, and get joined, and
 * one still running when main returns, which the program waits for as HotSpot does.
 */
class Threads {

    private static final Object LOCK = new Object();
    private static int counted;
    private static int countedInBlock;
    private static int slot;
    private static boolean full;

    private static synchronized void count() {
        counted++;
    }

    private static void put(int value) {
        synchronized (LOCK) {
            while (full) {
                await();
            }
            slot = value;
            full = true;
            LOCK.notifyAll();
        }
    }

    private static int take() {
        synchronized (LOCK) {
            while (!full) {
                await();
            }
            full = false;
            LOCK.notifyAll();
            return slot;
        }
    }

    private static void await() {
        try {
            LOCK.wait();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    static void main() throws InterruptedException {
        Thread[] counters = new Thread[4];
        for (int i = 0; i < counters.length; i++) {
            counters[i] = new Thread(() -> {
                for (int j = 0; j < 10_000; j++) {
                    count();
                    synchronized (LOCK) {
                        countedInBlock++;
                    }
                }
            });
            counters[i].start();
        }
        for (Thread counter : counters) {
            counter.join();
        }
        System.out.println("counted " + counted + " and " + countedInBlock);

        Thread producer = new Thread(() -> {
            for (int i = 1; i <= 100; i++) {
                put(i);
            }
            put(-1);
        }, "producer");
        producer.start();
        long sum = 0;
        for (int value; (value = take()) >= 0; ) {
            sum += value;
        }
        producer.join();
        System.out.println("handed over " + sum + ", " + producer.getName() + " alive " + producer.isAlive());

        synchronized (LOCK) {
            LOCK.wait(20);
            System.out.println("wait(20) timed out holding the lock: " + Thread.holdsLock(LOCK));
        }

        Thread named = new Thread(() -> System.out.println("running in " + Thread.currentThread().getName()));
        named.start();
        named.join();
        System.out.println("main is " + Thread.currentThread().getName());

        System.out.println("main returns");
        new Thread(() -> {
            synchronized (LOCK) {
                try {
                    LOCK.wait(50);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            System.out.println("still running after main returned");
        }).start();
    }
}
