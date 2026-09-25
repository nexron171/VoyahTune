package ru.big.town.restoremode;

import android.content.SharedPreferences;

/** Snapshot taken once per session; changing settings never changes an active audio pipeline. */
final class VoiceAudioConfig {
    static final String DEEP_FILTER_DB_KEY = "voiceDeepFilterAttenuationDb";
    static final int DEFAULT_DEEP_FILTER_DB = 6;
    static final int MAX_DEEP_FILTER_DB = 30;
    final int deepFilterDb;

    VoiceAudioConfig(int deepFilterDb) {
        this.deepFilterDb = Math.max(0, Math.min(MAX_DEEP_FILTER_DB, deepFilterDb));
    }

    static VoiceAudioConfig read(SharedPreferences prefs) {
        // Obsolete model/filter preferences are deliberately ignored after an upgrade.
        return new VoiceAudioConfig(prefs.getInt(DEEP_FILTER_DB_KEY, DEFAULT_DEEP_FILTER_DB));
    }

    String label() { return "Zipformer2 · русский 0.54 INT8 + DeepFilterNet3 · " + deepFilterDb + " дБ"; }
}
