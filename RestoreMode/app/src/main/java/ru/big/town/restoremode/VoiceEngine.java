package ru.big.town.restoremode;

import android.content.Context;

/** Owns all resident models; no microphone, focus request, Activity or session data. */
final class VoiceEngine implements AutoCloseable {
    private final VoiceNeuralFilter filter;

    VoiceEngine(Context context, int attenuationDb) throws Exception {
        VoiceNeuralFilter created = null;
        try {
            VoiceModels.warmUp(context);
            created = new VoiceNeuralFilter(attenuationDb);
            created.warmUp();
            filter = created;
        } catch (Exception | LinkageError e) {
            if (created != null) created.close();
            VoiceModels.release();
            throw e;
        }
    }

    VoiceNeuralFilter prepareFilter(int attenuationDb) throws Exception {
        filter.reset(attenuationDb);
        filter.warmUp();
        return filter;
    }

    @Override public void close() {
        try { filter.close(); }
        finally { VoiceModels.release(); }
    }
}
