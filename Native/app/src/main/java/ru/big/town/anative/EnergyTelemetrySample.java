package ru.big.town.anative;

/** Fixed primitive layouts verified against H97X CanBusService; no OEM classes are loaded. */
final class EnergyTelemetrySample {
    static final int SOC = 0, TRIP = 1, TIRES = 2, ODOMETER = 3, FUEL = 4, PRECISE_ODOMETER = 5, COUNT = 6;
    static final int[] TRANSACTIONS = {71, 80, 70, 1, 9, 56};
    static final int[] WORD_COUNTS = {1, 20, 11, 10, 7, 8};
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
            case PRECISE_ODOMETER:
                // H97C/H97X raw 0x2FE, observed against the whole-km odometer on H97X.
                // Keep 100 m ticks as an exact integer float; consumers validate against ODOMETER.
                for (int b : words) if (b < 0 || b > 255) return unavailable(kind);
                int ticks = words[0] | (words[1] << 8) | (words[2] << 16);
                return new EnergyTelemetrySample(kind, ticks > 0 && ticks < 0xffffff ? ticks : Float.NaN);
            case SOC:
                return new EnergyTelemetrySample(kind, value(words[0], 0, 100));
            case FUEL:
                // Ignore OEM capacity (52 on H97X); estimates use the user setting, default 56 L.
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
