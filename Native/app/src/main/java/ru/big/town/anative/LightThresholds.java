package ru.big.town.anative;

/** Immutable and Android-free auto-light threshold snapshot. */
final class LightThresholds {
    static final int DEFAULT_ON = 3;
    static final int DEFAULT_OFF = 5;

    final int on;
    final int off;

    LightThresholds(int on, int off) {
        if (on <= off) {
            this.on = on;
            this.off = off;
        } else {
            this.on = off;
            this.off = on;
        }
    }

    static LightThresholds defaults() {
        return new LightThresholds(DEFAULT_ON, DEFAULT_OFF);
    }

    Boolean desiredFor(int level) {
        if (level < 0) return null;
        if (level <= on) return Boolean.TRUE;
        if (level > off) return Boolean.FALSE;
        return null;
    }
}
