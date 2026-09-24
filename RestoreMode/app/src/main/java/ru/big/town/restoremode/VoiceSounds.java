package ru.big.town.restoremode;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import java.util.HashSet;
import java.util.Set;

/** Short local cues; cancelled sessions never leave queued or overlapping sounds behind. */
final class VoiceSounds {
    private final SoundPool pool;
    private final Set<Integer> loaded = new HashSet<>();
    private final int activation, success, error;
    private int pending, stream;
    private boolean released;

    VoiceSounds(Context context) {
        pool = new SoundPool.Builder().setMaxStreams(1)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        pool.setOnLoadCompleteListener((sounds, sample, status) -> {
            if (released || status != 0) return;
            loaded.add(sample);
            if (pending == sample) play(sample);
        });
        activation = pool.load(context, R.raw.voice_activation, 1);
        success = pool.load(context, R.raw.voice_success, 1);
        error = pool.load(context, R.raw.voice_error, 1);
    }

    void activation() { play(activation); }
    void success() { play(success); }
    void error() { play(error); }

    private void play(int sample) {
        if (released) return;
        stop();
        if (!loaded.contains(sample)) { pending = sample; return; }
        stream = pool.play(sample, .45f, .45f, 1, 0, 1);
    }

    void stop() {
        if (released) return;
        pending = 0;
        if (stream != 0) { pool.stop(stream); stream = 0; }
    }

    void release() {
        if (released) return;
        stop(); released = true; pool.release(); loaded.clear();
    }
}
