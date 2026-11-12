package duke.kernel.user;

import duke.kernel.Console;
import duke.kernel.Shell;
import duke.rt.Magic;
import duke.rt.Tib;

/** The console as a file: reads take a line typed at the keyboard, writes go to the screen. */
final class Terminal extends OpenFile {

    // A line typed at the console that a read didn't take all of, kept for the next read.
    private byte[] pendingLine;
    private int pendingAt;

    /**
     * A line at a time, as a terminal in canonical mode gives it: the shell's line editor echoes and
     * edits it, and the read returns once Enter is pressed.
     */
    @Override
    synchronized long read(long buffer, long count) {
        if (pendingLine == null) {
            String typed = Shell.readLine();
            if (typed == null) {
                return -SystemCalls.EINTR;
            }
            String line = typed + "\n";
            pendingLine = new byte[line.length()];
            for (int i = 0; i < line.length(); i++) {
                pendingLine[i] = (byte) line.charAt(i);
            }
            pendingAt = 0;
        }
        long n = Math.min(count, pendingLine.length - pendingAt);
        if (!SystemCalls.putBytes(buffer, pendingLine, pendingAt, n)) {
            return -SystemCalls.EFAULT;
        }
        pendingAt += (int) n;
        if (pendingAt == pendingLine.length) {
            pendingLine = null;
        }
        return n;
    }

    @Override
    long write(long buffer, long count) {
        byte[] chunk = new byte[(int) Math.min(count, 4096)];
        for (long done = 0; done < count; done += chunk.length) {
            int n = (int) Math.min(chunk.length, count - done);
            if (Magic.copyUserMemory(Magic.addressOf(chunk) + Tib.ARRAY_DATA, buffer + done, n) != 0) {
                return done > 0 ? done : -SystemCalls.EFAULT;
            }
            for (int i = 0; i < n; i++) {
                Console.write(chunk[i] & 0xFF);
            }
        }
        return count;
    }

    @Override
    int mode() {
        return MODE_CHARACTER;
    }
}
