package duke.user;

/**
 * Threads summing in double and float at once, preempted mid-loop: each one's xmm registers and
 * rounding have to come back as it left them, so every sum matches HotSpot to the bit.
 */
class FloatingPoint {

    static void main() throws InterruptedException {
        long[] sums = new long[4];
        Thread[] threads = new Thread[sums.length];
        for (int t = 0; t < threads.length; t++) {
            int index = t;
            threads[t] = new Thread(() -> {
                double harmonic = 0;
                float drift = 0;
                for (int i = 1; i <= 3_000_000; i++) {
                    harmonic += 1.0 / (i + index);
                    drift += 0.1f;
                }
                sums[index] = Double.doubleToRawLongBits(harmonic) ^ Float.floatToRawIntBits(drift);
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        for (long sum : sums) {
            System.out.println(Long.toHexString(sum));
        }
    }
}
