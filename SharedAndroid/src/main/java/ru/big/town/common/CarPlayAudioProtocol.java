package ru.big.town.common;

/** Audio requests use the existing signature-protected Native service. */
public final class CarPlayAudioProtocol {
    private CarPlayAudioProtocol() {}
    public static final int FOCUS = 126, QUERY = 127, SET = 128, VALUES = 129;
    public static final int MEDIA = 3, NAVIGATION = 1, PHONE = 6, SIRI = 22, NONE = -1;
    public static final String STREAM = "audioStream", LEVEL = "audioLevel",
            LEVELS = "audioLevels", MAXIMA = "audioMaxima", REQUEST = "audioRequest";
    public static int[] streams() { return new int[] {MEDIA, NAVIGATION, PHONE, SIRI}; }
    public static boolean validStream(int stream) {
        return stream == MEDIA || stream == NAVIGATION || stream == PHONE || stream == SIRI;
    }
}
