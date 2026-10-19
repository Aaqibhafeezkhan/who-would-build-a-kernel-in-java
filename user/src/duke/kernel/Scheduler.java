package duke.kernel;

/** A user program's one thread, main. Starting another isn't supported yet. */
public final class Scheduler {

    public static final class Task {
        final Thread thread;

        public Task(Thread thread) {
            this.thread = thread;
        }
    }

    private static final long MAIN_ID = 1;
    private static Thread main;
    private static int threadNumber;

    private Scheduler() {
    }

    public static int nextThreadNumber() {
        return threadNumber++;
    }

    public static boolean lockedHere() {
        return false;
    }

    public static Thread currentThread() {
        if (main == null) {
            main = new Thread("main");
        }
        return main;
    }

    public static long currentId() {
        return MAIN_ID;
    }

    public static boolean isAlive(long id) {
        return id == MAIN_ID;
    }

    public static void start(Task task) {
        throw new UnsupportedOperationException("a user program can't start threads yet");
    }

    public static boolean isAlive(Task task) {
        return task.thread == main;
    }

    public static void yield() {
    }

    public static void preempted() {
    }

    public static void pause() {
    }

    public static void sleep(long millis) {
        throw new UnsupportedOperationException("a user program can't sleep yet");
    }

    public static void join(Task task) {
    }

    public static int taskCount() {
        return 0;
    }

    public static long parkedFrame(int index) {
        return 0;
    }
}
