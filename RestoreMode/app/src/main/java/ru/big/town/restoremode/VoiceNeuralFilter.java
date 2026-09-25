package ru.big.town.restoremode;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.io.IOException;

/** Resident compiled model with a fresh stream state for each recording. Worker thread only. */
final class VoiceNeuralFilter implements AutoCloseable {
    interface DeepFilter extends Library {
        Pointer voice_df_create(float attenuationDb);
        int voice_df_process(Pointer state, float[] input, float[] output);
        int voice_df_reset(Pointer state, float attenuationDb);
        void voice_df_free(Pointer state);
    }
    private DeepFilter deepFilter;
    private Pointer state;
    private int attenuationDb;
    private boolean warmed;

    VoiceNeuralFilter(int deepFilterDb) throws IOException {
        reset(deepFilterDb);
    }

    void reset(int deepFilterDb) throws IOException {
        if (deepFilterDb < 0 || deepFilterDb > VoiceAudioConfig.MAX_DEEP_FILTER_DB)
            throw new IllegalArgumentException("Invalid DeepFilter attenuation");
        attenuationDb = deepFilterDb;
        // Keep an already loaded model, but bypass it completely at 0 dB.
        if (deepFilterDb == 0 && state == null) return;
        if (state == null) {
            deepFilter = Native.load("voyah_df", DeepFilter.class);
            state = deepFilter.voice_df_create(deepFilterDb);
            if (state == null) throw new IOException("Cannot initialize DeepFilterNet3");
        } else if (deepFilter.voice_df_reset(state, deepFilterDb) != 0) {
            throw new IOException("Cannot reset DeepFilterNet3");
        }
    }

    void warmUp() throws IOException {
        if (attenuationDb == 0 || warmed) return;
        float[] input = new float[480], output = new float[480];
        for (int i = 0; i < input.length; i++) input[i] = (float) Math.sin(i * .17) * .01f;
        for (int frame = 0; frame < 20; frame++) process(input, output);
        reset(attenuationDb);
        warmed = true;
    }

    void process(float[] input, float[] output) throws IOException {
        if (input.length != 480 || output.length != 480) throw new IllegalArgumentException("10 ms frames required");
        if (attenuationDb > 0) {
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
        warmed = false;
    }
}
