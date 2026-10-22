package duke.kernel.user;

import duke.kernel.mm.AddressSpace;
import duke.kernel.x86.UserState;
import java.util.ArrayList;
import java.util.List;

/**
 * A user program: one address space and the kernel threads that run it, one per thread clone made.
 * Pids and tids come from one counter, up from 1 and never reused, and the first thread's tid is the
 * pid. The process ends when its last thread does; exit_group, a fault or {@link #kill} make the
 * others leave at their next interrupt or system call.
 *
 * <p>A process that ends stays in the table as a zombie, holding only its exit status, until its
 * parent collects it with wait4 or the kernel code that started it calls {@link #waitFor}. Its
 * memory goes back as soon as it ends. Orphans, whose parent has ended, are dropped when they end.
 *
 * <p>A process the kernel starts leads a group that every process forked from it joins, as a
 * shell's job would. {@link #kill} ends the whole group, as Ctrl-C does on Linux.
 */
public final class Process {

    // Linux's signal numbers, for the wait status of a process that faulted or was killed.
    public static final int SIGINT = 2;
    public static final int SIGILL = 4;
    public static final int SIGTRAP = 5;
    public static final int SIGBUS = 7;
    public static final int SIGFPE = 8;
    public static final int SIGSEGV = 11;

    /** Guards the table and every process's state; waiters wait on it. */
    private static final Object LOCK = new Object();
    private static final List<Process> TABLE = new ArrayList<>();
    private static int lastPid;

    /** A kernel thread running one of a process's threads, so system calls can find their process. */
    static final class Runner extends Thread {
        final Process process;
        final int tid;
        // Where it starts in ring 3; a clone's or forked child's registers and FS, GS and FPU state.
        private long entry;
        private long stack;
        private long[] registers;
        private UserState userState;
        /** CLONE_CHILD_CLEARTID's word, zeroed and woken when the thread exits; 0 for none. */
        final long clearTid;

        Runner(Process process, int tid, long entry, long stack, long[] registers, UserState state, long clearTid) {
            super(process.name);
            this.process = process;
            this.tid = tid;
            this.entry = entry;
            this.stack = stack;
            this.registers = registers;
            this.userState = state;
            this.clearTid = clearTid;
        }

        @Override
        public void run() {
            process.runInThread(this);
        }
    }

    public final int pid;
    public final String name;
    private Process parent;
    /** The process the kernel started that this one was forked from, or this one. */
    private final Process group;
    /** Only the group leader's counts: set once, by kill. */
    private volatile boolean killed;
    /** Set once, by exit_group or a fault: every thread is to leave ring 3. */
    private volatile boolean exiting;
    /** exit_group's status, when exiting came from there. */
    private boolean groupExited;
    private long groupStatus;
    /** Threads that haven't ended yet. */
    private int live = 1;
    /** Collected by the kernel's waitFor rather than a parent's wait4. */
    private final boolean startedByKernel;
    // The image the threads run: replaced by execve, and freed when it's left behind.
    private AddressSpace space;
    /** What execve loaded, for the thread to switch to once it's out of ring 3. */
    private Elf.Image pendingImage;
    private final Runner thread;
    private boolean ended;
    private long exitStatus;
    private UserMode.Fault fault;
    private boolean interrupted;
    final FileTable files = new FileTable();

    private Process(String name, Process parent, AddressSpace space, long entry, long stack, long[] registers,
            UserState state) {
        this.name = name;
        this.parent = parent;
        this.startedByKernel = parent == null;
        this.group = parent == null ? this : parent.group;
        this.space = space;
        synchronized (LOCK) {
            pid = ++lastPid;
            TABLE.add(this);
        }
        thread = new Runner(this, pid, entry, stack, registers, state, 0);
    }

    /**
     * Starts a process that runs {@code entry} in {@code space} with its stack pointer at {@code stack}.
     * The process owns {@code space} from here on and frees it when it ends. {@code parent} is null
     * when the kernel starts it, and then the caller has to {@link #waitFor} it.
     */
    public static Process start(String name, Process parent, AddressSpace space, long entry, long stack) {
        Process p = new Process(name, parent, space, entry, stack, null, null);
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

    /** The calling thread's tid, or 0 for a kernel thread. */
    static int currentTid() {
        return Thread.currentThread() instanceof Runner r ? r.tid : 0;
    }

    /** CLONE_CHILD_CLEARTID's word for the calling thread, or 0. */
    static long currentClearTid() {
        return Thread.currentThread() instanceof Runner r ? r.clearTid : 0;
    }

    /**
     * Whether the calling thread's process has been killed or is exiting, so the thread is to leave
     * ring 3 rather than carry on. Allocates nothing.
     */
    public static boolean killedHere() {
        Process p = current();
        return p != null && (p.group.killed || p.exiting);
    }

    /**
     * Ends this process and every process in its group: each leaves ring 3 at its next interrupt
     * or system call, and a console read stops waiting. A wait4 needs no waking: the children it
     * waits for are in the same group, and it wakes when they end.
     */
    public void kill() {
        group.killed = true;
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
        Process child = new Process(parent.name, parent, space, rip, rsp, registers, state);
        child.files.copyFrom(parent.files);
        child.thread.start();
        return child;
    }

    /**
     * For clone: a new thread in this process, carrying on from {@code rip} with {@code rsp} and the
     * given registers and state. Started by {@link Runner#start}, once the caller has stored its tid
     * where clone was asked to. Null if the process is already on its way out.
     */
    Runner newThread(long rip, long rsp, long[] registers, UserState state, long clearTid) {
        synchronized (LOCK) {
            if (exiting || group.killed) {
                return null;
            }
            live++;
            return new Runner(this, ++lastPid, rip, rsp, registers, state, clearTid);
        }
    }

    /** True when the calling thread is the only one left, as execve needs. */
    boolean singleThreaded() {
        synchronized (LOCK) {
            return live == 1;
        }
    }

    /** exit_group: the process ends with {@code status} once every thread has left ring 3. */
    void exitGroup(long status) {
        synchronized (LOCK) {
            if (!exiting) {
                groupExited = true;
                groupStatus = status;
                exiting = true;
            }
            // A thread in wait4 is waiting on this.
            LOCK.notifyAll();
        }
    }

    /** For execve: run {@code image} instead once this process's thread leaves ring 3. */
    void replaceImage(Elf.Image image) {
        pendingImage = image;
    }

    private void runInThread(Runner self) {
        long status = 0;
        UserMode.Fault faulted = null;
        while (true) {
            try {
                status = killedHere() ? UserMode.KILLED
                        : UserMode.run(space, self.entry, self.stack, self.registers, self.userState);
            } catch (UserMode.Fault f) {
                faulted = f;
            }
            if (faulted != null || status != UserMode.EXECED) {
                break;
            }
            // execve only goes ahead with one thread, so nothing else runs in the old image.
            space.free();
            space = pendingImage.space;
            self.entry = pendingImage.entry;
            self.stack = pendingImage.stack;
            self.registers = null;
            self.userState = null;
            pendingImage = null;
        }
        synchronized (LOCK) {
            if (faulted != null && fault == null) {
                fault = faulted;
                exiting = true;
                LOCK.notifyAll();
            }
            if (--live > 0) {
                return;
            }
        }
        space.free();
        synchronized (LOCK) {
            ended = true;
            interrupted = fault == null && !groupExited && status == UserMode.KILLED;
            exitStatus = groupExited ? groupStatus : interrupted ? 0 : status;
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
     * its exit status, 0 if it was killed. Throws its {@link UserMode.Fault} if it faulted.
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

    /** Linux's wait status: the exit code in bits 8 to 15, or the signal that ended it in bits 0 to 6. */
    public int waitStatus() {
        return fault != null ? signalFor(fault.vector) : interrupted ? SIGINT : (int) (exitStatus & 0xFF) << 8;
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
    /** The waiting thread's own process is exiting or was killed. */
    static final Reaped INTERRUPTED = new Reaped(0, 0);

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
                if (killedHere()) {
                    return INTERRUPTED;
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
