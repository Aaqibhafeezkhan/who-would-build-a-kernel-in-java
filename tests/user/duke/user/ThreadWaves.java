package duke.user;

/**
 * Waves of threads, some wider than the stacks the runtime keeps spare, each touching a good part
 * of its stack: later waves reuse some mappings and get fresh ones where the runtime unmapped the
 * rest. A wave's threads wait at a gate until it's all started, or the first would finish before
 * the next started and every one would take the same slot.
 */
class ThreadWaves {

    private static final Object GATE = new Object();
    private static boolean open;

    private static int depth(int n) {
        return n == 0 ? 0 : 1 + depth(n - 1);
    }

    public static void main(String[] args) throws InterruptedException {
        for (int wave : new int[] {20, 3, 20, 1, 12}) {
            Thread[] threads = new Thread[wave];
            int[] results = new int[wave];
            for (int i = 0; i < wave; i++) {
                int index = i;
                threads[i] = new Thread(() -> {
                    synchronized (GATE) {
                        while (!open) {
                            try {
                                GATE.wait();
                            } catch (InterruptedException e) {
                                throw new AssertionError(e);
                            }
                        }
                    }
                    results[index] = depth(2000 + index);
                });
                threads[i].start();
            }
            synchronized (GATE) {
                open = true;
                GATE.notifyAll();
            }
            long sum = 0;
            for (int i = 0; i < wave; i++) {
                threads[i].join();
                sum += results[i];
            }
            open = false;
            System.out.println(wave + " threads: " + sum);
        }
    }
}
