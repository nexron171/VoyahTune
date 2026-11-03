package ru.big.town.common;

/** Recirculation observations and one-shot cycles over the protected Native Messenger. */
public final class RecirculationWidgetProtocol {
    public static final int WATCH = 210;
    public static final int UNWATCH = 211;
    public static final int CYCLE = 212;
    public static final int STATE = 213;

    public static final int UNKNOWN = -1;
    public static final int ON = 0;
    public static final int OFF = 1;
    public static final int AUTO = 2;

    public static final String MODE = "mode";
    public static final String AVAILABLE = "available";
    public static final String PENDING = "pending";
    public static final String MESSAGE = "message";

    private RecirculationWidgetProtocol() {}

    public static boolean validMode(int mode) {
        return mode >= ON && mode <= AUTO;
    }

    public static int nextMode(int mode) {
        switch (mode) {
            case ON:
                return OFF;
            case OFF:
                return AUTO;
            case AUTO:
                return ON;
            default:
                return UNKNOWN;
        }
    }

    public static String label(int mode) {
        switch (mode) {
            case ON:
                return "Вкл";
            case OFF:
                return "Выкл";
            case AUTO:
                return "Авто";
            default:
                return "—";
        }
    }
}
