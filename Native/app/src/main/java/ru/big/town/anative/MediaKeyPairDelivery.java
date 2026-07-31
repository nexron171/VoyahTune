package ru.big.town.anative;

/** Android-free exact-one guard for a media key DOWN/UP pair. */
final class MediaKeyPairDelivery {
    enum Outcome { NOT_SENT, DOWN_ONLY, COMPLETE }

    interface EventSender {
        boolean send(boolean down) throws Exception;
    }

    private MediaKeyPairDelivery() {}

    static Outcome dispatch(EventSender sender) {
        if (sender == null) return Outcome.NOT_SENT;
        final boolean downAccepted;
        try {
            downAccepted = sender.send(true);
        } catch (Throwable ignored) {
            return Outcome.NOT_SENT;
        }
        if (!downAccepted) return Outcome.NOT_SENT;

        try {
            return sender.send(false) ? Outcome.COMPLETE : Outcome.DOWN_ONLY;
        } catch (Throwable ignored) {
            // Once DOWN was accepted, a second backend would be a possible double toggle.
            return Outcome.DOWN_ONLY;
        }
    }
}
