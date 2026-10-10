package duke.kernel.user;

import duke.kernel.mm.KernelAddressSpace;
import duke.kernel.mm.PageTable;
import duke.kernel.mm.PhysicalMemory;

/**
 * A process's memory: its own lower half, and the kernel's higher half shared with everyone. Every
 * frame mapped into it belongs to it and goes back with {@link #free}.
 */
public final class AddressSpace {

    private final PageTable table = PageTable.sharingHigherHalf(KernelAddressSpace.table());

    public PageTable table() {
        return table;
    }

    /** Maps a zeroed frame at {@code virtual} for ring 3 and returns the frame's physical address. */
    public long allocate(long virtual, long flags) {
        if (virtual < 0 || virtual >= UserMode.END) {
            throw new IllegalArgumentException("0x" + Long.toHexString(virtual) + " isn't in the lower half");
        }
        long frame = PhysicalMemory.allocateZeroed();
        table.map(virtual, frame, flags | PageTable.USER);
        return frame;
    }

    /** Frees every page and table. No thread may still be running in it. */
    public void free() {
        table.freeLowerHalf();
    }
}
