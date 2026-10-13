package duke.kernel.user;

import duke.kernel.mm.AddressSpace;
import duke.kernel.x86.UserState;
import java.util.ArrayList;
import java.util.List;

/**
 * A user program: one address space and, for now, the one kernel thread that runs it. Pids count
 * up from 1 and are never reused.
 *
 * <p>A process that ends stays in the table as a zombie, holding only its exit status, until its
 * parent collects it with wait4 or the kernel code that started it calls {@link #waitFor}. Its
 * memory goes back as soon as it ends. Orphans, whose parent has ended, are dropped when they end.
 */
public final class Process {

    // Linux's signal numbers, for the wait status of a process that faulted.
    public static final int SIGILL = 4;
    public static final int SIGTRAP = 5;
    public static final int SIGBUS = 7;
    public static final int SIGFPE = 8;
    public static final int SIGSEGV = 11;

    /** Guards the table and every process's state; waiters wait on it. */
    private static final Object LOCK = new Object();
    private static final List<Process> TABLE = new ArrayList<>();
    private static int lastPid;

    /** The thread a process runs on, so system calls can find their process. */
    private static final class Runner extends Thread {
        final Process process;

        Runner(Process process, String name) {
            super(name);
            this.process = process;
        }

        @Override
        public void run() {
            process.runInThread();
        }
    }

    public final int pid;
    public final String name;
    private Process parent;
    /** Collected by the kernel's waitFor rather than a parent's wait4. */
    private final boolean startedByKernel;
    // The image the thread runs next: replaced by execve, and freed when it's left behind.
    private AddressSpace space;
    private long entry;
    private long stack;
    /** A forked child's first registers and FS, GS and FPU state; null to start from zeroes. */
    private long[] registers;
    private UserState userState;
    /** What execve loaded, for the thread to switch to once it's out of ring 3. */
    private Elf.Image pendingImage;
    private final Runner thread;
    private boolean ended;
    private long exitStatus;
    private UserMode.Fault fault;
    final FileTable files = new FileTable();

    private Process(String name, Process parent, AddressSpace space, long entry, long stack) {
        this.name = name;
        this.parent = parent;
        this.startedByKernel = parent == null;
        this.space = space;
        this.entry = entry;
        this.stack = stack;
        synchronized (LOCK) {
            pid = ++lastPid;
            TABLE.add(this);
        }
        thread = new Runner(this, name);
    }

    /**
     * Starts a process that runs {@code entry} in {@code space} with its stack pointer at {@code stack}.
     * The process owns {@code space} from here on and frees it when it ends. {@code parent} is null
     * when the kernel starts it, and then the caller has to {@link #waitFor} it.
     */
    public static Process start(String name, Process parent, AddressSpace space, long entry, long stack) {
        Process p = new Process(name, parent, space, entry, stack);
        p.thread.start();
        return p;
    }

    public static Process start(String name, Process parent, Elf.Image image) {
        return start(name, parent, image.space, image.entry, image.stack);
    }

    /** The process the calling thread runs, or null for a kernel thread. */
    public static Process current() {
        return Thread.currentThread() instanceof Runner r ? r.process : null;
    }

    /** The parent's pid, or 0 when the kernel started it or the parent has ended. */
    public int parentPid() {
        synchronized (LOCK) {
            return parent == null ? 0 : parent.pid;
        }
    }

    /**
     * A copy of the calling process for fork, carrying on from its system call with the same
     * registers (rax aside, 0 in the child), FS, GS and FPU state, memory and open files.
     */
    static Process fork(Process parent, AddressSpace space, long rip, long rsp, long[] registers, UserState state) {
        Process child = new Process(parent.name, parent, space, rip, rsp);
        child.registers = registers;
        child.userState = state;
        child.files.copyFrom(parent.files);
        child.thread.start();
        return child;
    }

    /** For execve: run {@code image} instead once this process's thread leaves ring 3. */
    void replaceImage(Elf.Image image) {
        pendingImage = image;
    }

    private void runInThread() {
        long status = 0;
        UserMode.Fault faulted = null;
        while (true) {
            try {
                status = UserMode.run(space, entry, stack, registers, userState);
            } catch (UserMode.Fault f) {
                faulted = f;
            }
            space.free();
            if (faulted != null || status != UserMode.EXECED) {
                break;
            }
            space = pendingImage.space;
            entry = pendingImage.entry;
            stack = pendingImage.stack;
            registers = null;
            userState = null;
            pendingImage = null;
        }
        synchronized (LOCK) {
            ended = true;
            exitStatus = status;
            fault = faulted;
            for (int i = TABLE.size() - 1; i >= 0; i--) {
                Process child = TABLE.get(i);
                if (child.parent == this) {
                    child.parent = null;
                    if (child.ended) {
                        TABLE.remove(i);
                    }
                }
            }
            if (parent == null && !startedByKernel) {
                TABLE.remove(this);
            }
            LOCK.notifyAll();
        }
    }

    /**
     * Waits for this process, started by the kernel, to end, drops it from the table, and returns
     * its exit status. Throws its {@link UserMode.Fault} if it faulted.
     */
    public long waitFor() throws InterruptedException {
        synchronized (LOCK) {
            while (!ended) {
                LOCK.wait();
            }
            TABLE.remove(this);
        }
        if (fault != null) {
            throw fault;
        }
        return exitStatus;
    }

    /** True once the process has ended, whether or not anyone has collected it. */
    public boolean ended() {
        synchronized (LOCK) {
            return ended;
        }
    }

    /** Linux's wait status: the exit code in bits 8 to 15, or the signal a fault would raise in bits 0 to 6. */
    public int waitStatus() {
        return fault != null ? signalFor(fault.vector) : (int) (exitStatus & 0xFF) << 8;
    }

    static int signalFor(int vector) {
        return switch (vector) {
            case 0, 16, 19 -> SIGFPE;
            case 1, 3 -> SIGTRAP;
            case 6 -> SIGILL;
            case 17 -> SIGBUS;
            default -> SIGSEGV;
        };
    }

    /** What wait4 found: the pid it collected and that child's wait status. */
    static final class Reaped {
        final int pid;
        final int status;

        Reaped(int pid, int status) {
            this.pid = pid;
            this.status = status;
        }
    }

    /** Nothing to wait for: no child matches. */
    static final Reaped NO_CHILD = new Reaped(-1, 0);
    /** WNOHANG, and a matching child is still running. */
    static final Reaped STILL_RUNNING = new Reaped(0, 0);

    /**
     * wait4 for {@code parent}: collects a child that has ended, the one with {@code pid} or any
     * for -1, waiting for one to end unless {@code block} is false.
     */
    static Reaped reap(Process parent, int pid, boolean block) throws InterruptedException {
        synchronized (LOCK) {
            while (true) {
                boolean any = false;
                for (int i = 0; i < TABLE.size(); i++) {
                    Process child = TABLE.get(i);
                    if (child.parent != parent || pid != -1 && child.pid != pid) {
                        continue;
                    }
                    if (child.ended) {
                        TABLE.remove(i);
                        return new Reaped(child.pid, child.waitStatus());
                    }
                    any = true;
                }
                if (!any) {
                    return NO_CHILD;
                }
                if (!block) {
                    return STILL_RUNNING;
                }
                LOCK.wait();
            }
        }
    }

    /** How many processes the table holds, zombies included. */
    public static int count() {
        synchronized (LOCK) {
            return TABLE.size();
        }
    }
}
