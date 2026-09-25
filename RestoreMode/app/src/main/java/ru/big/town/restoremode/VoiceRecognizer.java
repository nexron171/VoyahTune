package ru.big.town.restoremode;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.io.File;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Single process worker owns model, recognizer and recorder. Cancellation never blocks the UI. */
final class VoiceRecognizer {
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor();
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long modelUse; // Worker only; independent of UI cancellation tokens.
    interface Listener {
        void listening();
        default void processing() {}
        default void details(String text) {}
        default void recording(File file) { file.delete(); }
        void audio(float level, String partial);
        void result(String text);
        void error(String message);
    }
    private long generation;

    void cancel() {
        GENERATION.compareAndSet(generation, generation + 1);
    }

    void start(Context context, Listener listener) {
        final long token = GENERATION.incrementAndGet();
        generation = token;
        final Context app = context.getApplicationContext();
        VoiceAudioConfig config = VoiceAudioConfig.read(app.getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE));
        WORKER.execute(() -> recognize(app, token, listener, config));
    }

    private static void post(long token, Runnable action) {
        MAIN.post(() -> { if (GENERATION.get() == token) action.run(); });
    }

    @android.annotation.SuppressLint("MissingPermission")
    private static void recognize(Context context, long token, Listener listener, VoiceAudioConfig config) {
        AudioRecord recorder = null;
        VoiceRecording recording = new VoiceRecording();
        boolean recordingPublished = false;
        File recordingDirectory = new File(context.getCacheDir(), VoiceRecording.DIRECTORY);
        String stage = "подготовка модели";
        long use = ++modelUse;
        try {
            if (GENERATION.get() != token) return;
            VoiceRecording.clear(recordingDirectory);
            post(token, () -> listener.details(config.label()));
            try (VoiceModels.Session engine = VoiceModels.open(context);
                 VoiceNeuralFilter filter = new VoiceNeuralFilter(config.deepFilterDb)) {
                if (GENERATION.get() != token) return;
                stage = "микрофон 48 кГц";
                int minimum = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) throw new IOException("Unsupported microphone format");
                recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 48000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(48000, minimum));
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone unavailable");
                final String description = config.label();
                recorder.startRecording();
                if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Recording failed");
                post(token, listener::listening);
                stage = "обработка звука";
                short[] buffer = new short[480];
                float[] input = new float[480], clean = new float[480], samples = new float[160];
                VoiceDecimator decimator = new VoiceDecimator();
                long started = SystemClock.elapsedRealtime(), nextUi = 0, filterNanos = 0;
                int filled = 0, frames = 0;
                while (GENERATION.get() == token && frames < 1000 && SystemClock.elapsedRealtime() - started < 10000) {
                    int count = recorder.read(buffer, filled, buffer.length - filled, AudioRecord.READ_NON_BLOCKING);
                    if (count < 0) throw new IOException("Audio read failed: " + count);
                    if (count == 0) { Thread.sleep(5); continue; }
                    filled += count;
                    if (filled < buffer.length) continue;
                    filled = 0;
                    for (int i = 0; i < 480; i++) input[i] = buffer[i] / 32768f;
                    long filterStart = System.nanoTime();
                    filter.process(input, clean);
                    decimator.process(clean, samples);
                    filterNanos += System.nanoTime() - filterStart;
                    frames++;
                    recording.append(samples);
                    boolean endpoint = engine.accept(samples);
                    long now = SystemClock.elapsedRealtime();
                    // Do not silently lose audio if the chosen combination cannot keep up.
                    if (now - started - frames * 10L > 1500) {
                        throw new IOException("Audio processing cannot keep up");
                    }
                    if (endpoint) break;
                    if (now >= nextUi) {
                        double energy = 0;
                        for (float sample : samples) energy += sample * sample;
                        float rms = (float) Math.sqrt(energy / samples.length);
                        float level = Math.max(0, Math.min(1, (float) ((20 * Math.log10(Math.max(rms, .00001)) + 55) / 40)));
                        String partial = engine.partial();
                        post(token, () -> listener.audio(level, partial));
                        nextUi = now + 60;
                    }
                }
                recorder.stop();
                if (GENERATION.get() != token) return;
                publishRecording(recordingDirectory, token, listener, recording);
                recordingPublished = true;
                stage = "распознавание фразы";
                post(token, listener::processing);
                long decodingStarted = SystemClock.elapsedRealtime();
                String text = engine.finish();
                long decodeMs = SystemClock.elapsedRealtime() - decodingStarted;
                String stats = description + String.format(Locale.ROOT,
                        "\nАудио %.1f с · фильтр %.0f мс/с · финал %d мс · память приложения %d МБ",
                        frames / 100f, filterNanos / Math.max(1.0, frames * 10000.0), decodeMs,
                        android.os.Debug.getPss() / 1024);
                android.util.Log.i("VoyahVoice", stats.replace('\n', ' '));
                post(token, () -> { listener.details(stats); listener.result(text); });
            }
        } catch (Exception | LinkageError e) {
            if (!recordingPublished) publishRecording(recordingDirectory, token, listener, recording);
            android.util.Log.e("VoyahVoice", "Recognition failed: " + config.label() + " / " + stage, e);
            String message = "Ошибка: " + stage
                    + ("Audio processing cannot keep up".equals(e.getMessage())
                    ? ". Обработка звука не успевает за записью. Попробуйте установить шумоподавление на 0 дБ."
                    : ". Проверьте микрофон и повторите попытку.");
            post(token, () -> listener.error(message));
        } finally {
            if (recorder != null) {
                try { if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop(); }
                catch (RuntimeException ignored) { }
                recorder.release();
            }
            // Keep a warm ASR model for comparisons, then return its memory after one minute idle.
            WORKER.schedule(() -> {
                if (modelUse == use) VoiceModels.release();
            }, 60, TimeUnit.SECONDS);
        }
    }

    private static void publishRecording(File directory, long token, Listener listener, VoiceRecording recording) {
        if (recording.empty() || GENERATION.get() != token) return;
        try {
            File file = recording.save(directory);
            MAIN.post(() -> {
                if (GENERATION.get() == token) listener.recording(file);
                else file.delete();
            });
        } catch (IOException e) {
            // Preview failure must not change recognition or cause a different command.
            android.util.Log.w("VoyahVoice", "Cannot save temporary recording", e);
        }
    }
}
