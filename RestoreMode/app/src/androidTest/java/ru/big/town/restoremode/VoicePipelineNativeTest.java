package ru.big.town.restoremode;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VoicePipelineNativeTest {
    private static final int[] STRENGTHS = {0, 6, 30};

    @Test public void warmedModelsSupportSuccessiveSessionsWithoutVadCarryover() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (VoiceEngine resident = new VoiceEngine(target, 6)) {
            for (int session = 0; session < 2; session++) {
                VoiceNeuralFilter filter = resident.prepareFilter(6);
                try (VoiceModels.Session engine = VoiceModels.open(target)) {
                    byte[] audio = fixture("command");
                    VoiceDecimator decimator = new VoiceDecimator();
                    float[] input = new float[480], clean = new float[480], samples = new float[160];
                    for (int offset = 0; offset + 960 <= audio.length; offset += 960) {
                        for (int i = 0; i < 480; i++) input[i] = (short) ((audio[offset + 2*i] & 255)
                                | (audio[offset + 2*i + 1] << 8)) / 32768f;
                        filter.process(input, clean); decimator.process(clean, samples);
                        if (engine.accept(samples)) break;
                    }
                    String text = engine.finish();
                    VoiceCommandCatalog.Command command = VoiceCommandCatalog.match(VoiceCommands.load(target), text);
                    assertNotNull(text, command);
                    assertEquals(VoiceCommandCatalog.match(VoiceCommands.load(target), "включи обогрев руля").action, command.action);
                }
                // The previous VAD segment must not survive Session.close/open.
                try (VoiceModels.Session silence = VoiceModels.open(target)) {
                    for (int frame = 0; frame < 100; frame++) silence.accept(new float[160]);
                    assertEquals("", silence.finish());
                }
            }
        }
    }

    @Test public void recognizesRussianCommandAtEachStrength() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        byte[] audio = fixture("command");
        try {
            for (int strength : STRENGTHS) {
                String result = recognize(target, strength, audio);
                assertFalse(strength + " dB returned no speech", result.isEmpty());
                VoiceCommandCatalog.Command command = VoiceCommandCatalog.match(VoiceCommands.load(target), result);
                assertNotNull(strength + " dB: " + result, command);
                assertEquals(VoiceCommandCatalog.match(VoiceCommands.load(target), "включи обогрев руля").action, command.action);
            }
        } finally { VoiceModels.release(); }
    }

    @Test public void negativeAndUnrelatedSpeechDoNotSelectCommands() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try {
            for (int strength : STRENGTHS) {
                for (String name : new String[]{"negative", "unrelated"}) {
                    String result = recognize(target, strength, fixture(name));
                    assertFalse(strength + " dB lost " + name, result.isEmpty());
                    assertNull(strength + " dB falsely selected command: " + result,
                            VoiceCommandCatalog.match(VoiceCommands.load(target), result));
                }
            }
        } finally { VoiceModels.release(); }
    }

    private static byte[] fixture(String name) throws Exception {
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("voice_" + name + "_48k.pcm");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static String recognize(Context target, int strength, byte[] audio) throws Exception {
        long start = SystemClock.elapsedRealtime();
        try (VoiceModels.Session engine = VoiceModels.open(target);
             VoiceNeuralFilter filter = new VoiceNeuralFilter(strength)) {
            VoiceDecimator decimator = new VoiceDecimator();
            float[] input = new float[480], clean = new float[480], samples = new float[160];
            for (int offset = 0; offset + 960 <= audio.length; offset += 960) {
                for (int i = 0; i < 480; i++) input[i] = (short) ((audio[offset + 2*i] & 255)
                        | (audio[offset + 2*i + 1] << 8)) / 32768f;
                filter.process(input, clean);
                decimator.process(clean, samples);
                if (engine.accept(samples)) break;
            }
            String result = engine.finish();
            Log.i("VoicePipelineTest", "DeepFilter " + strength + " dB " + (SystemClock.elapsedRealtime() - start) + " ms: " + result);
            return result;
        }
    }

    @Test public void neuralFiltersReleaseAndRecreateWithoutInvalidAudio() throws Exception {
        for (int strength : STRENGTHS) {
            for (int session = 0; session < 2; session++) {
                try (VoiceNeuralFilter filter = new VoiceNeuralFilter(strength)) {
                    float[] input = new float[480], output = new float[480];
                    for (int i = 0; i < 60; i++) {
                        filter.process(input, output);
                        for (float value : output) assertTrue(Float.isFinite(value) && Math.abs(value) <= 1);
                    }
                }
            }
        }
    }

    @Test public void deepFilterAttenuationControlsRealNativeOutput() throws Exception {
        double softEnergy = 0, strongEnergy = 0;
        java.util.Random random = new java.util.Random(42);
        try (VoiceNeuralFilter bypass = new VoiceNeuralFilter(0);
             VoiceNeuralFilter soft = new VoiceNeuralFilter(6);
             VoiceNeuralFilter strong = new VoiceNeuralFilter(30)) {
            float[] input = new float[480], dry = new float[480], a = new float[480], b = new float[480];
            for (int frame = 0; frame < 160; frame++) {
                for (int i = 0; i < input.length; i++) input[i] = (random.nextFloat() - .5f) * .12f;
                bypass.process(input, dry); soft.process(input, a); strong.process(input, b);
                assertArrayEquals("0 dB must preserve the original samples", input, dry, 0);
                for (int i = 0; i < input.length; i++) {
                    assertTrue(Float.isFinite(a[i]) && Float.isFinite(b[i]));
                    if (frame >= 30) { softEnergy += a[i] * a[i]; strongEnergy += b[i] * b[i]; }
                }
            }
        }
        Log.i("VoicePipelineTest", "DeepFilter noise energy: 6dB=" + softEnergy + " 30dB=" + strongEnergy);
        assertTrue("6 dB should preserve more noise than 30 dB", softEnergy > strongEnergy * 2);
    }
}
