package ru.big.town.restoremode;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceRecordingTest {
    @Test public void savesTheProcessedSamplesAsPlayableMonoWavAndClearsOnlyRecordings() throws Exception {
        File directory = Files.createTempDirectory("voice-recording-test").toFile();
        File unrelated = new File(directory, "keep.txt");
        assertTrue(unrelated.createNewFile());
        try {
            VoiceRecording recording = new VoiceRecording();
            recording.append(new float[]{0, .5f, -1, 1});
            File file = recording.save(directory);
            byte[] bytes = Files.readAllBytes(file.toPath());
            ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals("RIFF", new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals(44, data.getInt(4));
            assertEquals(1, data.getShort(20)); assertEquals(1, data.getShort(22));
            assertEquals(16000, data.getInt(24)); assertEquals(8, data.getInt(40));
            assertEquals(0, data.getShort(44)); assertEquals(16384, data.getShort(46));
            assertEquals(-32768, data.getShort(48)); assertEquals(32767, data.getShort(50));
            VoiceRecording.clear(directory);
            assertFalse(file.exists()); assertTrue(unrelated.exists());
        } finally { unrelated.delete(); directory.delete(); }
    }
    @Test(expected = IllegalStateException.class) public void recordingCannotGrowPastTenSeconds() {
        VoiceRecording recording = new VoiceRecording();
        recording.append(new float[160000]); recording.append(new float[1]);
    }
}
