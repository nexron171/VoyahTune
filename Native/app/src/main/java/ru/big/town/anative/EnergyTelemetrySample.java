package ru.big.town.anative;

/** Fixed primitive layouts verified against H97X CanBusService; no OEM classes are loaded. */
final class EnergyTelemetrySample {
    static final int INSTANT = 0, TRIP = 1, TIRES = 2, ODOMETER = 3;
    static final int[] TRANSACTIONS = {79, 80, 70, 1};
    static final int[] WORD_COUNTS = {35, 20, 11, 10};
    final int kind;
    final float[] values;

    EnergyTelemetrySample(int kind, float... values) {
        this.kind = kind;
        this.values = values.clone();
    }

    static EnergyTelemetrySample unavailable(int kind) {
        int count = kind == TIRES ? 4 : kind == TRIP ? 3 : kind == INSTANT ? 2 : 1;
        float[] values = new float[count];
        java.util.Arrays.fill(values, Float.NaN);
        return new EnergyTelemetrySample(kind, values);
    }

    static EnergyTelemetrySample decode(int kind, int[] words) {
        if (kind < 0 || kind >= WORD_COUNTS.length) throw new IllegalArgumentException("kind");
        if (words == null || words.length < WORD_COUNTS[kind]) return unavailable(kind);
        switch (kind) {
            case INSTANT:
                return new EnergyTelemetrySample(kind, value(words[33], -20, 106),
                        value(words[34], 0, 31));
            case TRIP:
                return new EnergyTelemetrySample(kind, value(words[0], 0, 2_000_000),
                        value(words[1], -50, 2000), value(words[3], 0, 200));
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
