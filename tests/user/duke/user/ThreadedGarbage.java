package duke.user;

import java.util.ArrayList;
import java.util.List;

/**
 * Collections while other threads run: four threads churn through far more than the collection
 * threshold, keeping some of what they allocate, while another waits on a monitor holding a list
 * only its own stack refers to, and main waits in join holding one of its own. Everything kept
 * has to come through intact.
 */
class ThreadedGarbage {

    private static final Object LOCK = new Object();
    private static boolean churned;

    private static List<int[]> kept(int seed, int count) {
        List<int[]> kept = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int[] block = new int[256];
            block[0] = seed + i;
            block[255] = seed - i;
            kept.add(block);
        }
        return kept;
    }

    private static long check(List<int[]> kept, int seed) {
        long sum = 0;
        for (int i = 0; i < kept.size(); i++) {
            int[] block = kept.get(i);
            if (block[0] != seed + i || block[255] != seed - i) {
                throw new AssertionError("block " + i + " of seed " + seed + " was overwritten");
            }
            sum += block[0];
        }
        return sum;
    }

    static void main() throws InterruptedException {
        long[] sums = new long[5];
        Thread waiter = new Thread(() -> {
            List<int[]> mine = kept(1_000_000, 500);
            synchronized (LOCK) {
                while (!churned) {
                    try {
                        LOCK.wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            sums[4] = check(mine, 1_000_000);
        });
        waiter.start();

        Thread[] churners = new Thread[4];
        for (int t = 0; t < churners.length; t++) {
            int index = t;
            churners[t] = new Thread(() -> {
                List<int[]> kept = new ArrayList<>();
                for (int i = 0; i < 2_000; i++) {
                    int[] block = new int[16 * 1024];
                    block[0] = index * 10_000 + i;
                    block[block.length - 1] = i;
                    if (i % 100 == 0) {
                        kept.add(block);
                    }
                }
                long sum = 0;
                for (int[] block : kept) {
                    sum += block[0] + block[block.length - 1];
                }
                sums[index] = sum;
            });
            churners[t].start();
        }
        List<int[]> mine = kept(-1_000_000, 500);
        for (Thread churner : churners) {
            churner.join();
        }
        synchronized (LOCK) {
            churned = true;
            LOCK.notifyAll();
        }
        waiter.join();
        System.out.println("churners kept " + sums[0] + " " + sums[1] + " " + sums[2] + " " + sums[3]);
        System.out.println("waiter kept " + sums[4] + ", main kept " + check(mine, -1_000_000));
    }
}
