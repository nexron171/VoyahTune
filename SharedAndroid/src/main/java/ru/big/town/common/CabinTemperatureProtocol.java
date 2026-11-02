package ru.big.town.common;

/** Experimental cabin measurement over the protected Native channel. */
public final class CabinTemperatureProtocol {
    public static final int WATCH = 170;
    public static final int UNWATCH = 171;
    public static final int STATE = 172;
    public static final String REQUEST = "ru.big.town.anative.CABIN_TEMPERATURE_REQUEST";
    public static final String UPDATE = "ru.big.town.anative.CABIN_TEMPERATURE_UPDATE";
    public static final String PERMISSION = "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";
    public static final String RAW = "raw";
    public static final String SAMPLED_AT = "sampledAt";
    public static final String VALUE = "temperature";
    public static final String AVAILABLE = "available";
    public static final String MESSAGE = "message";

    private CabinTemperatureProtocol() {}

    public static double decode(int raw) {
        return raw >= 0 && raw < 255 ? raw * 0.5 - 40 : Double.NaN;
    }

    public static String format(double value) {
        if (!Double.isFinite(value)) return "—";
        return value == Math.rint(value)
                ? String.format(java.util.Locale.ROOT, "%.0f", value)
                : String.format(java.util.Locale.ROOT, "%.1f", value).replace('.', ',');
    }
}
