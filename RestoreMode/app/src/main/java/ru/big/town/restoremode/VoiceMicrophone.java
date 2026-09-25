package ru.big.town.restoremode;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.io.IOException;

/** Created only after preparation and a visible Activity's audio-focus approval. */
final class VoiceMicrophone implements VoiceSessionControl.Capture {
    private final AudioRecord recorder;

    @android.annotation.SuppressLint("MissingPermission")
    VoiceMicrophone() throws IOException {
        int minimum = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IOException("Unsupported microphone format");
        recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 48000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(48000, minimum));
        try {
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone unavailable");
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Recording failed");
        } catch (IOException | RuntimeException e) {
            recorder.release();
            throw e;
        }
    }
    @Override public int read(short[] buffer, int offset, int length) {
        return recorder.read(buffer, offset, length, AudioRecord.READ_NON_BLOCKING);
    }
    @Override public void close() {
        try {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop();
        } catch (RuntimeException ignored) {
        } finally { recorder.release(); }
    }
}
