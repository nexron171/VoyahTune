package ru.big.town.restoremode;

import android.content.Context;
import com.k2fsa.sherpa.onnx.*;
import java.io.*;
import java.util.Arrays;

/** Worker-confined model cache; only one ASR model stays resident. */
final class VoiceModels {
    private static OfflineRecognizer zipformer;

    interface Session extends AutoCloseable {
        boolean accept(float[] samples) throws Exception;
        String partial() throws Exception;
        String finish() throws Exception;
        @Override void close();
    }

    static Session open(Context context) throws Exception {
        if (zipformer == null) {
            File root = unpack(context, "zipformer-ru-0.54-int8");
            OfflineRecognizerConfig config = new OfflineRecognizerConfig();
            config.getFeatConfig().setSampleRate(16000);
            config.getFeatConfig().setDither(3e-5f);
            config.setDecodingMethod("modified_beam_search");
            config.setMaxActivePaths(10);
            config.setHotwordsScore(1.0f);
            OfflineModelConfig model = config.getModelConfig();
            model.setNumThreads(2);
            model.setProvider("cpu");
            model.setTokens(new File(root, "tokens.txt").getAbsolutePath());
            model.setModelingUnit("bpe");
            model.setBpeVocab(new File(root, "bpe.vocab").getAbsolutePath());
            model.getTransducer().setEncoder(new File(root, "encoder.int8.onnx").getAbsolutePath());
            model.getTransducer().setDecoder(new File(root, "decoder.int8.onnx").getAbsolutePath());
            model.getTransducer().setJoiner(new File(root, "joiner.int8.onnx").getAbsolutePath());
            zipformer = new OfflineRecognizer(null, config);
        }
        VadModelConfig vadConfig = new VadModelConfig();
        vadConfig.setNumThreads(1);
        vadConfig.setSampleRate(16000);
        vadConfig.getSileroVadModelConfig().setModel("silero_vad.onnx");
        vadConfig.getSileroVadModelConfig().setMinSilenceDuration(.7f);
        vadConfig.getSileroVadModelConfig().setMinSpeechDuration(.15f);
        vadConfig.getSileroVadModelConfig().setMaxSpeechDuration(10);
        String hotwords = VoiceHotwords.fromCommands(VoiceCommands.load(context));
        return new ZipformerSession(zipformer, new Vad(context.getAssets(), vadConfig), hotwords);
    }

    static void release() {
        if (zipformer != null) { zipformer.release(); zipformer = null; }
    }

    private static final class ZipformerSession implements Session {
        private final OfflineRecognizer recognizer;
        private final Vad vad;
        private final String hotwords;
        private final float[] recording = new float[16000 * 10];
        private int count;
        private boolean speech;
        ZipformerSession(OfflineRecognizer recognizer, Vad vad, String hotwords) {
            this.recognizer = recognizer; this.vad = vad; this.hotwords = hotwords;
        }
        public boolean accept(float[] samples) {
            int n = Math.min(samples.length, recording.length - count);
            System.arraycopy(samples, 0, recording, count, n);
            count += n;
            vad.acceptWaveform(samples);
            speech |= vad.isSpeechDetected() || !vad.empty();
            return !vad.empty() || count == recording.length;
        }
        public String partial() { return ""; }
        public String finish() {
            vad.flush();
            if ((!speech && vad.empty()) || count == 0) return "";
            // Feed the full recording including pre-roll: trimming to VAD can lose "не" or an imperative.
            OfflineStream stream = recognizer.createStream(hotwords);
            try {
                stream.acceptWaveform(Arrays.copyOf(recording, count), 16000);
                recognizer.decode(stream);
                return recognizer.getResult(stream).getText().trim();
            } finally { stream.release(); }
        }
        public void close() { vad.release(); }
    }

    private static File unpack(Context context, String asset) throws IOException {
        File root = new File(context.getNoBackupFilesDir(), asset);
        File ready = new File(root, ".ready");
        if (!ready.isFile()) {
            copyAssets(context, asset, root);
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
            try (InputStream input = context.getAssets().open(path); FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            }
        }
    }
}
