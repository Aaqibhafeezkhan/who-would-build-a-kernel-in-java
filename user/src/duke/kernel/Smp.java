package duke.kernel;

/** The collector doesn't stop a user program's other threads yet, so it refuses to run alongside them. */
public final class Smp {

    private Smp() {
    }

    public static int cpuCount() {
        return 1;
    }

    public static void stopOthers() {
        if (Scheduler.othersAlive()) {
            Panic.panic("a collection with other threads running isn't supported yet");
        }
    }

    public static void resumeOthers() {
    }

    public static long stoppedFrame(int cpu) {
        return 0;
    }

    public static void stopIfRequested() {
    }
}
