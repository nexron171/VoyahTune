package ru.big.town.restoremode.settings.sections.vehicle;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;

final class TurnSoundPreview {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;

    TurnSoundPreview(Context context) {
        this.context = context;
    }

    void play(String sound, int volume) {
        stop();
        playPart(sound, volume, 0);
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void playPart(String sound, int volume, int index) {
        MediaPlayer current = new MediaPlayer();
        player = current;
        current.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        float amplitude = volume / 100f;
        current.setVolume(amplitude, amplitude);
        current.setOnPreparedListener(
                prepared -> {
                    if (player == prepared) {
                        prepared.start();
                    }
                });
        current.setOnCompletionListener(
                completed -> {
                    if (player != completed) {
                        return;
                    }
                    completed.release();
                    player = null;
                    if (index < 5) {
                        handler.postDelayed(() -> playPart(sound, volume, index + 1), 350);
                    }
                });
        current.setOnErrorListener(
                (failed, what, extra) -> {
                    if (player == failed) {
                        fail();
                    }
                    return true;
                });
        String part = index % 2 == 0 ? "tick" : "tock";
        try (AssetFileDescriptor asset =
                context.getAssets()
                        .openFd(
                                "turn_signal_"
                                        + sound.toLowerCase(Locale.ROOT)
                                        + "_"
                                        + part
                                        + ".wav")) {
            current.setDataSource(
                    asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
            current.prepareAsync();
        } catch (IOException | RuntimeException failure) {
            fail();
        }
    }

    private void fail() {
        stop();
        Toast.makeText(context, "Не удалось прослушать звук", Toast.LENGTH_SHORT).show();
    }
}
