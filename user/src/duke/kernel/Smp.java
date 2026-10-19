package duke.kernel;

/** One thread, so the collector has nobody to stop. */
public final class Smp {

    private Smp() {
    }

    public static int cpuCount() {
        return 1;
    }

    public static void stopOthers() {
    }

    public static void resumeOthers() {
    }

    public static long stoppedFrame(int cpu) {
        return 0;
    }

    public static void stopIfRequested() {
    }
}
