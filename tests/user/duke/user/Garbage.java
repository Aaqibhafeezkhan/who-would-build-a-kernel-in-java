package duke.user;

import java.util.ArrayList;
import java.util.List;

/**
 * Allocates half again the heap's whole 1 GiB reservation while keeping little alive, so it only
 * finishes if the collector gives memory back.
 */
class Garbage {
    static void main() {
        List<int[]> kept = new ArrayList<>();
        long sum = 0;
        for (int i = 0; i < 24_000; i++) {
            int[] block = new int[16 * 1024];
            block[i % block.length] = i;
            sum += block[i % block.length];
            if (i % 1000 == 0) {
                kept.add(block);
            }
        }
        System.out.println("allocated 1.5 GiB in 64 KiB blocks, kept " + kept.size() + ", sum " + sum);
    }
}
