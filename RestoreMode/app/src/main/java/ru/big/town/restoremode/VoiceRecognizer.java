package ru.big.town.restoremode;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** One worker owns models; cancellation closes the microphone without waiting for model work. */
final class VoiceRecognizer {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final VoiceSessionControl SESSIONS = new VoiceSessionControl();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final VoiceEngineCache<VoiceEngine> ENGINES = new VoiceEngineCache<>();
    private static final AtomicLong RESIDENCY = new AtomicLong();

    /** Service lifetime is independent of an Activity/session cancellation token. */
    static void keepWarm(Context context, Consumer<Boolean> prepared) {
        final Context app = context.getApplicationContext();
        final long request = RESIDENCY.incrementAndGet();
        ENGINES.retain(true);
        WORKER.execute(() -> {
            if (RESIDENCY.get() != request || !ENGINES.retained()) return;
            boolean ready = false;
            long started = SystemClock.elapsedRealtime();
            try {
                VoiceAudioConfig config = VoiceAudioConfig.read(app.getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE));
                prepareEngine(app, config);
                ready = true;
                android.util.Log.i("VoyahVoice", "Warmup ready in " + (SystemClock.elapsedRealtime() - started) + " ms; microphone closed");
            } catch (Exception | LinkageError e) {
                ENGINES.release();
                android.util.Log.e("VoyahVoice", "Warmup failed", e);
            } finally {
                ENGINES.releaseIfUnretained();
            }
            final boolean result = ready;
            MAIN.post(() -> { if (RESIDENCY.get() == request && ENGINES.retained()) prepared.accept(result); });
        });
    }

    static void stopKeepingWarm() {
        RESIDENCY.incrementAndGet();
        ENGINES.retain(false);
        SESSIONS.cancelAll();
        WORKER.execute(ENGINES::releaseIfUnretained);
    }

    private static VoiceEngine prepareEngine(Context app, VoiceAudioConfig config) throws Exception {
        VoiceEngine engine = ENGINES.acquire(() -> new VoiceEngine(app, config.deepFilterDb));
        engine.prepareFilter(config.deepFilterDb);
        return engine;
    }
    interface Listener {
        void listening();
        default void ready(Runnable beginCapture) { beginCapture.run(); }
        default void processing() {}
        default void details(String text) {}
        default void recording(File file) { file.delete(); }
        void audio(float level, String partial);
        void result(String text);
        void error(String message);
    }
    private long generation;

    void cancel() {
        if (SESSIONS.cancel(generation)) {
            final long cancelled = generation + 1;
            WORKER.execute(() -> { if (SESSIONS.active(cancelled)) ENGINES.releaseIfUnretained(); });
        }
    }

    void start(Context context, Listener listener) {
        final long token = SESSIONS.begin();
        generation = token;
        final Context app = context.getApplicationContext();
        VoiceAudioConfig config = VoiceAudioConfig.read(app.getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE));
        WORKER.execute(() -> {
            if (!SESSIONS.active(token)) return;
            try {
                prepareEngine(app, config);
                // An Activity arriving during service warmup reaches this same cached engine.
                post(token, () -> listener.ready(() -> WORKER.execute(() -> recognize(app, token, listener, config))));
            } catch (Exception | LinkageError e) {
                ENGINES.release();
                android.util.Log.e("VoyahVoice", "Voice preparation failed", e);
                post(token, () -> listener.error("Не удалось подготовить помощника. Повторите попытку."));
            } finally {
                if (!SESSIONS.active(token)) ENGINES.releaseIfUnretained();
            }
        });
    }

    private static void post(long token, Runnable action) {
        MAIN.post(() -> { if (SESSIONS.active(token)) action.run(); });
    }

    private static void recognize(Context context, long token, Listener listener, VoiceAudioConfig config) {
        VoiceRecording recording = new VoiceRecording();
        boolean recordingPublished = false;
        File recordingDirectory = new File(context.getCacheDir(), VoiceRecording.DIRECTORY);
        String stage = "подготовка модели";
        try {
            if (!SESSIONS.active(token)) return;
            VoiceRecording.clear(recordingDirectory);
            post(token, () -> listener.details(config.label()));
            VoiceNeuralFilter filter = ENGINES.acquire(() -> new VoiceEngine(context, config.deepFilterDb))
                    .prepareFilter(config.deepFilterDb);
            try (VoiceModels.Session engine = VoiceModels.open(context)) {
                if (!SESSIONS.active(token)) return;
                stage = "микрофон 48 кГц";
                if (!SESSIONS.open(token, VoiceMicrophone::new)) return;
                final String description = config.label();
                post(token, listener::listening);
                stage = "обработка звука";
                short[] buffer = new short[480];
                float[] input = new float[480], clean = new float[480], samples = new float[160];
                VoiceDecimator decimator = new VoiceDecimator();
                long started = SystemClock.elapsedRealtime(), nextUi = 0, filterNanos = 0;
                int filled = 0, frames = 0;
                while (SESSIONS.active(token) && frames < 1000 && SystemClock.elapsedRealtime() - started < 10000) {
                    int count = SESSIONS.read(token, buffer, filled, buffer.length - filled);
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
                SESSIONS.finishCapture(token);
                if (!SESSIONS.active(token)) return;
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
            SESSIONS.finishCapture(token);
            ENGINES.releaseIfUnretained();
        }
    }

    private static void publishRecording(File directory, long token, Listener listener, VoiceRecording recording) {
        if (recording.empty() || !SESSIONS.active(token)) return;
        try {
            File file = recording.save(directory);
            MAIN.post(() -> {
                if (SESSIONS.active(token)) listener.recording(file);
                else file.delete();
            });
        } catch (IOException e) {
            // Preview failure must not change recognition or cause a different command.
            android.util.Log.w("VoyahVoice", "Cannot save temporary recording", e);
        }
    }
}
