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
        AMBIENT_TEMPERATURE,
        ENERGY_TELEMETRY
    }

    enum Origin {
        LIVE,
        SEED,
        REPLAY
    }

    /** Бит открытой двери в маске события {@link Kind#DOOR} (поле {@code second}). */
    static final int DOOR_DRIVER = 1;
    static final int DOOR_PASSENGER = 1 << 1;
    static final int DOOR_REAR_LEFT = 1 << 2;
    static final int DOOR_REAR_RIGHT = 1 << 3;
    static final int DOOR_BOOT = 1 << 4;
    static final int DOOR_HOOD = 1 << 5;
    /** Хотя бы одно поле двери недоступно (-1): остальным битам этого события доверять нельзя. */
    static final int DOOR_UNKNOWN = 1 << 6;
    static final int DOOR_ALL = DOOR_DRIVER | DOOR_PASSENGER | DOOR_REAR_LEFT | DOOR_REAR_RIGHT
            | DOOR_BOOT | DOOR_HOOD;

    final Kind kind;
    final Origin origin;
    final long connectionEpoch;
    final long sequence;
    final long elapsedRealtime;
    final int first;
    final int second;
    final int third;
    final EnergyTelemetrySample telemetry;

    private CanBusEvent(Kind kind, Origin origin, long connectionEpoch, long sequence,
                        long elapsedRealtime, int first, int second, int third) {
        this(kind, origin, connectionEpoch, sequence, elapsedRealtime, first, second, third, null);
    }

    private CanBusEvent(Kind kind, Origin origin, long connectionEpoch, long sequence,
                        long elapsedRealtime, int first, int second, int third, EnergyTelemetrySample telemetry) {
        this.telemetry = telemetry;
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

    /** Совместимая форма без маски: используется там, где важна только водительская дверь. */
    static CanBusEvent door(Origin origin, long epoch, long sequence, long elapsed, int frontLeft) {
        return door(origin, epoch, sequence, elapsed, frontLeft, 0);
    }

    static CanBusEvent door(Origin origin, long epoch, long sequence, long elapsed,
                            int frontLeft, int doorMask) {
        return new CanBusEvent(Kind.DOOR, origin, epoch, sequence,
                elapsed, frontLeft, doorMask, 0);
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

    static CanBusEvent telemetry(Origin origin, long epoch, long sequence, long elapsed, EnergyTelemetrySample sample) {
        return new CanBusEvent(Kind.ENERGY_TELEMETRY, origin, epoch, sequence, elapsed, sample.kind, 0, 0, sample);
    }

    int signalKey() {
        return (kind == Kind.VEHICLE_STATE || kind == Kind.ENERGY_TELEMETRY)
                ? (kind.ordinal() << 24) ^ first
                : kind.ordinal() << 24;
    }

    boolean isOrderedTransition() {
        return kind == Kind.DOOR || kind == Kind.GEAR;
    }

    boolean samePayload(CanBusEvent other) {
        // Telemetry repeats refresh transport freshness, even when the measured value is unchanged.
        return kind != Kind.ENERGY_TELEMETRY && other != null && kind == other.kind && origin == other.origin
                && connectionEpoch == other.connectionEpoch && first == other.first
                && second == other.second && third == other.third;
    }
}
