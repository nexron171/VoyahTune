package ru.big.town.anative;

import ru.big.town.common.CarPlayAudioProtocol;

/** Qinggan focus attributes must resolve to the same stream as the CarPlay AudioTrack. */
final class CarPlayAudioPolicy {
    final int usage, content, gain;
    private CarPlayAudioPolicy(int usage, int content, int gain) {
        this.usage = usage;
        this.content = content;
        this.gain = gain;
    }
    static CarPlayAudioPolicy forStream(int stream) {
        switch (stream) {
            case CarPlayAudioProtocol.MEDIA: return new CarPlayAudioPolicy(1, 2, 1);
            case CarPlayAudioProtocol.NAVIGATION: return new CarPlayAudioPolicy(12, 1, 3);
            case CarPlayAudioProtocol.PHONE: return new CarPlayAudioPolicy(2, 11, 2);
            case CarPlayAudioProtocol.SIRI: return new CarPlayAudioPolicy(16, 14, 3);
            default: return null;
        }
    }
    static int clampVolume(int value, int maximum) {
        if (value < 0 || maximum <= 0) throw new IllegalArgumentException("Invalid volume");
        return Math.min(value, maximum);
    }
}
