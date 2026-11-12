package ru.big.town.anative;

/** A connection snapshot must not be mistaken for a new opening. */
final class FuelFlapOpeningPolicy {
    static final int STATE_ID = 780;
    private long epoch = Long.MIN_VALUE;
    private Integer previous;

    boolean accept(CanBusEvent event) {
        if (event.connectionEpoch < epoch) return false;
        if (event.connectionEpoch != epoch || event.kind == CanBusEvent.Kind.CONNECTION_LOST) {
            epoch = event.connectionEpoch;
            previous = null;
        }
        if (event.kind != CanBusEvent.Kind.VEHICLE_STATE || event.first != STATE_ID) return false;
        int state = event.second;
        if (state != 1 && state != 2) {
            previous = null;
            return false;
        }
        boolean opened =
                event.origin == CanBusEvent.Origin.LIVE
                        && Integer.valueOf(1).equals(previous)
                        && state == 2;
        previous = state;
        return opened;
    }

    static String message(Integer liters) {
        return liters == null
                ? "Лючок бензобака открыт. Объём заправки недоступен"
                : "Лючок бензобака открыт. Можно заправить примерно " + liters + " л";
    }
}
