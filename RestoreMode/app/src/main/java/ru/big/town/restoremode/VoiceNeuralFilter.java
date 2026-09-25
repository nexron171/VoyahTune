package ru.big.town.restoremode;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.io.IOException;

/** One native state per recording. Models run synchronously on the voice worker only. */
final class VoiceNeuralFilter implements AutoCloseable {
    interface DeepFilter extends Library {
        Pointer voice_df_create(float attenuationDb);
        int voice_df_process(Pointer state, float[] input, float[] output);
        void voice_df_free(Pointer state);
    }
    private DeepFilter deepFilter;
    private Pointer state;

    VoiceNeuralFilter(int deepFilterDb) throws IOException {
        if (deepFilterDb < 0 || deepFilterDb > VoiceAudioConfig.MAX_DEEP_FILTER_DB)
            throw new IllegalArgumentException("Invalid DeepFilter attenuation");
        // At 0 dB pass through the original samples, without a model or its latency.
        if (deepFilterDb == 0) return;
        deepFilter = Native.load("voyah_df", DeepFilter.class);
        state = deepFilter.voice_df_create(deepFilterDb);
        if (state == null) throw new IOException("Cannot initialize DeepFilterNet3");
    }

    void process(float[] input, float[] output) throws IOException {
        if (input.length != 480 || output.length != 480) throw new IllegalArgumentException("10 ms frames required");
        if (deepFilter != null) {
            if (deepFilter.voice_df_process(state, input, output) != 0) throw new IOException("DeepFilter processing failed");
        } else System.arraycopy(input, 0, output, 0, 480);
        for (int i = 0; i < 480; i++) {
            if (!Float.isFinite(output[i])) throw new IOException("Noise filter produced invalid audio");
            output[i] = Math.max(-1, Math.min(1, output[i]));
        }
    }
    @Override public void close() {
        if (state == null) return;
        if (deepFilter != null) deepFilter.voice_df_free(state);
        state = null;
    }
}
