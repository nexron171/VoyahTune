package ru.big.town.anative;

/**
 * Serializes the user's OEM-Auto intent with automatic headlight actions.
 *
 * <p>The steering-wheel command itself runs on ApplyEngine's command thread, so merely changing
 * the committed flag inside that runnable leaves a window in which the main thread can enqueue an
 * automatic action behind it. A ticket closes that window as soon as the user command is accepted
 * by the receiver, while revision tokens invalidate automatic actions which were queued earlier.
 */
final class ManualAutoGate {
    static final long INVALID_AUTOMATIC_TOKEN = -1L;

    private long revision;
    private int pendingManualCommands;
    private boolean manualAutoSelected;

    synchronized Ticket reserveManualCommand() {
        pendingManualCommands++;
        revision++;
        return new Ticket(this);
    }

    synchronized boolean setSelected(boolean selected) {
        boolean previous = manualAutoSelected;
        manualAutoSelected = selected;
        revision++;
        return previous;
    }

    synchronized boolean blocksAntiAuto() {
        return manualAutoSelected || pendingManualCommands > 0;
    }

    /**
     * Starts a new sensor decision. A completed manual Auto selection is intentionally cleared by
     * the next real sensor decision, matching the legacy behaviour. A command which has only been
     * queued, however, always wins over automatic work.
     */
    synchronized long beginAutomaticDecision() {
        if (pendingManualCommands > 0) return INVALID_AUTOMATIC_TOKEN;
        manualAutoSelected = false;
        return ++revision;
    }

    synchronized boolean isAutomaticActionCurrent(long token) {
        return token != INVALID_AUTOMATIC_TOKEN
                && pendingManualCommands == 0
                && revision == token;
    }

    synchronized int pendingManualCommandsForTest() {
        return pendingManualCommands;
    }

    private synchronized void release(Ticket ticket) {
        if (ticket.closed) return;
        ticket.closed = true;
        if (pendingManualCommands > 0) pendingManualCommands--;
        revision++;
    }

    static final class Ticket implements AutoCloseable {
        private final ManualAutoGate owner;
        private boolean closed;

        private Ticket(ManualAutoGate owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            owner.release(this);
        }
    }
}
