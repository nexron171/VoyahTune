package ru.big.town.anative;

/** Fixed primitive layouts verified against H97X CanBusService; no OEM classes are loaded. */
final class EnergyTelemetrySample {
    static final int SOC = 0, TRIP = 1, TIRES = 2, ODOMETER = 3, FUEL = 4, COUNT = 5;
    static final int[] TRANSACTIONS = {71, 80, 70, 1, 9};
    static final int[] WORD_COUNTS = {1, 20, 11, 10, 7};
    final int kind;
    final float[] values;
    final int tripCounter;

    EnergyTelemetrySample(int kind, float... values) {
        this(kind, values, -1);
    }

    private EnergyTelemetrySample(int kind, float[] values, int tripCounter) {
        this.kind = kind;
        this.values = values.clone();
        this.tripCounter = tripCounter;
    }

    static EnergyTelemetrySample unavailable(int kind) {
        int count = kind == TIRES ? 4 : kind == TRIP ? 3 : 1;
        float[] values = new float[count];
        java.util.Arrays.fill(values, Float.NaN);
        return new EnergyTelemetrySample(kind, values);
    }

    static EnergyTelemetrySample decode(int kind, int[] words) {
        if (kind < 0 || kind >= WORD_COUNTS.length) throw new IllegalArgumentException("kind");
        if (words == null || words.length < WORD_COUNTS[kind]) return unavailable(kind);
        switch (kind) {
            case SOC:
                return new EnergyTelemetrySample(kind, value(words[0], 0, 100));
            case FUEL:
                // Ignore the OEM tank capacity (52 on H97X); the estimate uses the agreed 56 L.
                return new EnergyTelemetrySample(kind, value(words[2], 0, 100));
            case TRIP:
                return new EnergyTelemetrySample(kind, new float[]{value(words[0], 0, 2_000_000),
                        value(words[1], -50, 2000), Float.NaN}, words[4] >= 0 ? words[4] : -1);
            case TIRES:
                return new EnergyTelemetrySample(kind, value(words[7], 0, 25),
                        value(words[8], 0, 25), value(words[9], 0, 25), value(words[10], 0, 25));
            default:
                // mBeenTravelingMileage; OEM defines zero as UNKNOWN. Do not use range/REV fields.
                return new EnergyTelemetrySample(kind,
                        words[1] > 0 && words[1] < 10_000_000 ? words[1] : Float.NaN);
        }
    }

    private static float value(int word, float min, float max) {
        float v = Float.intBitsToFloat(word);
        return Float.isFinite(v) && v != Float.MIN_VALUE && v >= min && v <= max ? v : Float.NaN;
    }
}
