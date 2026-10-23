package ru.big.town.common;

import java.util.Locale;

/** Primitive-only commands carried by the OEM radio MediaSession. */
public final class RadioWidgetProtocol {
    public static final String PACKAGE = "com.pateo.rdsapp";
    public static final String SERVICE = "com.pateo.midrds.media.RdsMediaBrowserService";
    public static final String COMMAND = "ru.big.town.voyahtune.RADIO_WIDGET";
    public static final int VERSION = 1;
    public static final String SCHEMA = "schema";
    public static final String ACTION = "action";
    public static final String SNAPSHOT = "snapshot";
    public static final String PLAY = "play";
    public static final String PAUSE = "pause";
    public static final String NEXT = "next";
    public static final String PREVIOUS = "previous";
    public static final String FREQUENCY = "freq";
    public static final String FM = "fm";
    public static final String NAME = "name";
    public static final String READY = "ready";
    public static final String PLAYING = "playing";
    public static final String CAN_SKIP = "canSkip";
    public static final String ACCEPTED = "accepted";
    public static final String REQUESTED_FREQUENCY = "requestedFreq";
    public static final String REQUESTED_FM = "requestedFm";
    public static final String MESSAGE = "message";

    private RadioWidgetProtocol() {}

    public static boolean validFrequency(boolean fm, int frequency) {
        return fm
                ? frequency >= 6500 && frequency <= 10800
                : frequency >= 150 && frequency <= 30000;
    }

    public static String frequency(boolean fm, int frequency) {
        if (!validFrequency(fm, frequency)) {
            return "—";
        }
        return fm
                ? String.format(new Locale("ru", "RU"), "%.1f", frequency / 100.0)
                : Integer.toString(frequency);
    }
}
