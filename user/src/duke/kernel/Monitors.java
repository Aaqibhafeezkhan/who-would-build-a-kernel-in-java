package duke.kernel;

/**
 * Monitors with one thread: never contended, so entering only records the object, for holds and
 * the IllegalMonitorStateException checks. Waiting would never be notified.
 */
public final class Monitors {

    private static Object[] held = new Object[16];
    private static int count;

    private Monitors() {
    }

    public static void enter(Object object) {
        if (object == null) {
            throw new NullPointerException();
        }
        if (count == held.length) {
            Object[] bigger = new Object[count * 2];
            System.arraycopy(held, 0, bigger, 0, count);
            held = bigger;
        }
        held[count++] = object;
    }

    public static void exit(Object object) {
        if (!tryExit(object)) {
            throw new IllegalMonitorStateException("current thread is not owner");
        }
    }

    /** For the unwinder leaving a synchronized method, where the monitor may already be gone. */
    public static void exitUnwinding(Object object) {
        tryExit(object);
    }

    private static boolean tryExit(Object object) {
        for (int i = count - 1; i >= 0; i--) {
            if (held[i] == object) {
                System.arraycopy(held, i + 1, held, i, count - i - 1);
                held[--count] = null;
                return true;
            }
        }
        return false;
    }

    public static void await(Object object, long millis) {
        if (!holds(object)) {
            throw new IllegalMonitorStateException("current thread is not owner");
        }
        throw new UnsupportedOperationException("wait() in a user program: no other thread can notify");
    }

    public static void notify(Object object, boolean all) {
        if (!holds(object)) {
            throw new IllegalMonitorStateException("current thread is not owner");
        }
    }

    public static boolean holds(Object object) {
        for (int i = 0; i < count; i++) {
            if (held[i] == object) {
                return true;
            }
        }
        return false;
    }
}
