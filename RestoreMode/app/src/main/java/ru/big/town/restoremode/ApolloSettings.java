package ru.big.town.restoremode;

/** Persisted Apollo targets owned by VoyahTune; no live CAN state is mirrored into these values. */
final class ApolloSettings {
    static final String STOCK_UI = "apolloStockUiEnabled";
    static final String TLC = "apolloTlcEnabled";
    static final String TRAFFIC_LIGHTS = "apolloTrafficLightsEnabled";
    static final String GREEN_SOUND = "apolloGreenSoundEnabled";
    static final String TRAFFIC_SIGNS = "apolloTrafficSignsEnabled";

    static final boolean DEFAULT_ENABLED = false;

    private ApolloSettings() {
    }
}
