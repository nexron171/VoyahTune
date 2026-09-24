package ru.big.town.restoremode;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Single process worker owns model, recognizer and recorder. Cancellation never blocks the UI. */
final class VoiceRecognizer {
    private static final String MODEL = "vosk-model-small-ru-0.22";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Model model; // Worker only; retained for subsequent sessions.
    interface Listener {
        void listening();
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
        WORKER.execute(() -> recognize(app, token, listener));
    }

    private static void post(long token, Runnable action) {
        MAIN.post(() -> { if (GENERATION.get() == token) action.run(); });
    }

    @android.annotation.SuppressLint("MissingPermission")
    private static void recognize(Context context, long token, Listener listener) {
        AudioRecord recorder = null;
        try {
            if (GENERATION.get() != token) return;
            if (model == null) model = new Model(unpack(context).getAbsolutePath());
            if (GENERATION.get() != token) return;
            int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("Unsupported microphone format");
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(6400, minimum));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone unavailable");
            // General ASR deliberately retains unknown words and negation. A closed grammar can
            // force unrelated speech into a valid car command.
            try (Recognizer recognizer = new Recognizer(model, 16000)) {
                recorder.startRecording();
                if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Recording failed");
                post(token, listener::listening);
                short[] buffer = new short[1600];
                long deadline = SystemClock.elapsedRealtime() + 10000;
                long nextUi = 0;
                while (GENERATION.get() == token && SystemClock.elapsedRealtime() < deadline) {
                    int count = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_NON_BLOCKING);
                    if (count < 0) throw new IOException("Audio read failed: " + count);
                    if (count == 0) { Thread.sleep(15); continue; }
                    double energy = 0;
                    for (int i = 0; i < count; i++) energy += (double) buffer[i] * buffer[i];
                    float rms = (float) Math.sqrt(energy / count) / 32768f;
                    float level = Math.max(0, Math.min(1, (float) ((20 * Math.log10(Math.max(rms, 0.00001)) + 55) / 40)));
                    if (recognizer.acceptWaveForm(buffer, count)) {
                        String text = new JSONObject(recognizer.getResult()).optString("text");
                        if (!text.isEmpty()) { post(token, () -> listener.result(text)); return; }
                    }
                    long now = SystemClock.elapsedRealtime();
                    if (now >= nextUi) {
                        String partial = new JSONObject(recognizer.getPartialResult()).optString("partial");
                        post(token, () -> listener.audio(level, partial));
                        nextUi = now + 60;
                    }
                }
                if (GENERATION.get() == token) {
                    String text = new JSONObject(recognizer.getFinalResult()).optString("text");
                    post(token, () -> listener.result(text));
                }
            }
        } catch (Exception | LinkageError e) {
            android.util.Log.e("VoyahVoice", "Recognition failed", e);
            post(token, () -> listener.error("Не удалось запустить распознавание. Проверьте доступ к микрофону."));
        } finally {
            if (recorder != null) {
                try { if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop(); }
                catch (RuntimeException ignored) { }
                recorder.release();
            }
        }
    }

    private static File unpack(Context context) throws IOException {
        File root = new File(context.getNoBackupFilesDir(), MODEL);
        File ready = new File(root, ".ready");
        if (!ready.isFile()) {
            copyAssets(context, MODEL, root);
            if (!ready.createNewFile()) throw new IOException("Cannot mark model ready");
        }
        return root;
    }

    private static void copyAssets(Context context, String path, File destination) throws IOException {
        String[] children = context.getAssets().list(path);
        if (children != null && children.length > 0) {
            if (!destination.isDirectory() && !destination.mkdirs()) throw new IOException("Cannot create model directory");
            for (String child : children) copyAssets(context, path + "/" + child, new File(destination, child));
        } else {
            try (InputStream input = context.getAssets().open(path);
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            }
        }
    }
}
