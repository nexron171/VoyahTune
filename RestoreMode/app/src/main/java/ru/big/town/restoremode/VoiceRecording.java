package ru.big.town.restoremode;

import java.io.*;

/** A bounded PCM16 copy of the 16 kHz audio passed to ASR, stored only in private cache. */
final class VoiceRecording {
    static final String DIRECTORY = "voice-preview";
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream(320000);

    void append(float[] samples) {
        if (pcm.size() + samples.length * 2 > 320000) throw new IllegalStateException("Recording exceeds 10 seconds");
        for (float sample : samples) {
            int value = Math.round(Math.max(-32768, Math.min(32767, sample * 32768)));
            pcm.write(value & 255); pcm.write((value >> 8) & 255);
        }
    }
    boolean empty() { return pcm.size() == 0; }

    File save(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create recording cache");
        File file = File.createTempFile("session-", ".wav", directory);
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            out.write(new byte[]{'R','I','F','F'}); le(out, 36 + pcm.size(), 4);
            out.write(new byte[]{'W','A','V','E','f','m','t',' '}); le(out, 16, 4);
            le(out, 1, 2); le(out, 1, 2); le(out, 16000, 4); le(out, 32000, 4);
            le(out, 2, 2); le(out, 16, 2);
            out.write(new byte[]{'d','a','t','a'}); le(out, pcm.size(), 4);
            pcm.writeTo(out);
        } catch (IOException e) { file.delete(); throw e; }
        return file;
    }
    private static void le(OutputStream out, int value, int bytes) throws IOException {
        for (int i = 0; i < bytes; i++) out.write((value >>> (8 * i)) & 255);
    }
    static void clear(File directory) {
        File[] files = directory.listFiles((dir, name) -> name.startsWith("session-") && name.endsWith(".wav"));
        if (files != null) for (File file : files) file.delete();
    }
}
