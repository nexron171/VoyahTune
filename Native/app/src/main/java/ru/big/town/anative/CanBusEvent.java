package ru.big.town.anative;

/** Normalized event emitted by the single process-wide CanBus callback. */
final class CanBusEvent {
    enum Kind {
        CONNECTION,
        CONNECTION_LOST,
        DOOR,
        GEAR,
        LIGHT_STATUS,
        VEHICLE_STATE,
        AMBIENT_TEMPERATURE
    }

    enum Origin {
        LIVE,
        SEED,
        REPLAY
    }

    final Kind kind;
    final Origin origin;
    final long connectionEpoch;
    final long sequence;
    final long elapsedRealtime;
    final int first;
    final int second;
    final int third;

    private CanBusEvent(Kind kind, Origin origin, long connectionEpoch, long sequence,
                        long elapsedRealtime, int first, int second, int third) {
        this.kind = kind;
        this.origin = origin;
        this.connectionEpoch = connectionEpoch;
        this.sequence = sequence;
        this.elapsedRealtime = elapsedRealtime;
        this.first = first;
        this.second = second;
        this.third = third;
    }

    static CanBusEvent connection(long epoch, long sequence, long elapsed) {
        return new CanBusEvent(Kind.CONNECTION, Origin.LIVE, epoch, sequence,
                elapsed, 0, 0, 0);
    }

    static CanBusEvent connectionLost(long barrierEpoch, long sequence, long elapsed,
                                      long closedEpoch) {
        return new CanBusEvent(Kind.CONNECTION_LOST, Origin.LIVE, barrierEpoch, sequence,
                elapsed, (int) Math.min(Integer.MAX_VALUE, closedEpoch), 0, 0);
    }

    static CanBusEvent door(Origin origin, long epoch, long sequence, long elapsed, int frontLeft) {
        return new CanBusEvent(Kind.DOOR, origin, epoch, sequence,
                elapsed, frontLeft, 0, 0);
    }

    static CanBusEvent gear(Origin origin, long epoch, long sequence, long elapsed, int value) {
        return new CanBusEvent(Kind.GEAR, origin, epoch, sequence,
                elapsed, value, 0, 0);
    }

    static CanBusEvent light(Origin origin, long epoch, long sequence, long elapsed,
                             int autoLamp, int dippedBeam, int headLight) {
        return new CanBusEvent(Kind.LIGHT_STATUS, origin, epoch, sequence,
                elapsed, autoLamp, dippedBeam, headLight);
    }

    static CanBusEvent vehicleState(Origin origin, long epoch, long sequence, long elapsed,
                                    int stableId, int value) {
        return new CanBusEvent(Kind.VEHICLE_STATE, origin, epoch, sequence,
                elapsed, stableId, value, 0);
    }

    static CanBusEvent ambientTemperature(Origin origin, long epoch, long sequence,
                                          long elapsed, int value) {
        return new CanBusEvent(Kind.AMBIENT_TEMPERATURE, origin, epoch, sequence,
                elapsed, value, 0, 0);
    }

    int signalKey() {
        return kind == Kind.VEHICLE_STATE
                ? (kind.ordinal() << 24) ^ first
                : kind.ordinal() << 24;
    }

    boolean isOrderedTransition() {
        return kind == Kind.DOOR || kind == Kind.GEAR;
    }

    boolean samePayload(CanBusEvent other) {
        return other != null && kind == other.kind && origin == other.origin
                && connectionEpoch == other.connectionEpoch && first == other.first
                && second == other.second && third == other.third;
    }
}
