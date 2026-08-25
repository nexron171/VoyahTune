package ru.big.town.anative;

/**
 * Android-free bounded latest-value slots used by a single drain lane.
 * Caller provides synchronization; each slot retains only its newest pending value.
 */
final class SplitHostPendingSlots<T> {
    private final Object[] pending;
    private boolean drainActive;

    SplitHostPendingSlots(int slotCount) {
        if (slotCount <= 0) throw new IllegalArgumentException("slotCount must be positive");
        pending = new Object[slotCount];
    }

    /** @return true only when the caller must post the one drain runnable. */
    boolean offer(int slot, T value) {
        checkSlot(slot);
        if (value == null) throw new IllegalArgumentException("null work");
        pending[slot] = value;
        if (drainActive) return false;
        drainActive = true;
        return true;
    }

    @SuppressWarnings("unchecked")
    T take(int slot) {
        checkSlot(slot);
        T value = (T) pending[slot];
        pending[slot] = null;
        return value;
    }

    @SuppressWarnings("unchecked")
    T peek(int slot) {
        checkSlot(slot);
        return (T) pending[slot];
    }

    void clear(int slot) {
        checkSlot(slot);
        pending[slot] = null;
    }

    /** @return true when coalesced work remains and the drain must post itself once more. */
    boolean finishDrain() {
        for (Object value : pending) {
            if (value != null) return true;
        }
        drainActive = false;
        return false;
    }

    /** A Handler rejected the drain; retain latest work but allow a future offer to retry posting. */
    void rejectDrainPost() {
        drainActive = false;
    }

    int pendingCount() {
        int count = 0;
        for (Object value : pending) if (value != null) count++;
        return count;
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= pending.length) {
            throw new IllegalArgumentException("unknown slot " + slot);
        }
    }
}
