package ru.big.town.common;

/** One-shot controls over the signature-protected Native Messenger. */
public final class QuickControlProtocol {
    public static final int EXECUTE = 190;
    public static final int RESULT = 191;

    private QuickControlProtocol() {}

    public static boolean handles(String action) {
        return "rear_fog:on".equals(action)
                || "rear_fog:off".equals(action)
                || "rear_fog:toggle".equals(action)
                || "hotspot:on".equals(action)
                || "hotspot:off".equals(action)
                || "hotspot:toggle".equals(action);
    }
}
