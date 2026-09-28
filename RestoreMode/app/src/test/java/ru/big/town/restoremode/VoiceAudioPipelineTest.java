package ru.big.town.restoremode;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class VoiceAudioPipelineTest {
    @Test public void oldModelAndFilterPreferencesCannotOverridePipeline() {
        android.content.SharedPreferences prefs = (android.content.SharedPreferences)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{android.content.SharedPreferences.class}, (proxy, method, args) -> {
                            assertEquals("Only the saved attenuation should be read", "getInt", method.getName());
                            assertEquals(VoiceAudioConfig.DEEP_FILTER_DB_KEY, args[0]);
                            assertEquals(6, args[1]);
                            return 12;
                        });
        VoiceAudioConfig config = VoiceAudioConfig.read(prefs);
        assertEquals(12, config.deepFilterDb);
        assertEquals("Zipformer2 · русский 0.54 INT8 + DeepFilterNet3 · 12 дБ", config.label());
    }

    @Test public void decimatorKeepsVoiceAndRejectsAliasing() {
        assertTrue("1 kHz voice band", powerAt(1000) > .45);
        assertTrue("12 kHz must not fold into 4 kHz", powerAt(12000) < .00001);
    }

    @Test public void deepFilterStrengthIsBounded() {
        assertEquals(0, new VoiceAudioConfig(-5).deepFilterDb);
        assertEquals(30, new VoiceAudioConfig(99).deepFilterDb);
        assertEquals(0, new VoiceAudioConfig(0).deepFilterDb);
        assertEquals(6, new VoiceAudioConfig(6).deepFilterDb);
        assertTrue(new VoiceAudioConfig(12).label().contains("12 дБ"));
    }

    @Test public void decimatorKeepsStateAcrossFrames() {
        VoiceDecimator decimator = new VoiceDecimator();
        float[] input = new float[480], output = new float[160];
        Arrays.fill(input, .25f);
        for (int i = 0; i < 5; i++) decimator.process(input, output);
        for (float sample : output) assertEquals(.25f, sample, .00001f);
    }

    @Test public void dictionaryUsesCatalogAndIncludesNegativePhrasesWithoutSyntaxInjection() {
        List<VoiceCommandCatalog.Command> commands = Arrays.asList(
                new VoiceCommandCatalog.Command("x", "x", false, "Подогрев руля", "подогрев руля"),
                new VoiceCommandCatalog.Command("y", "y", false, "Открой Навигатор", "имя/два :99", "топливо 70", "открой Spotify"));
        String hotwords = VoiceHotwords.fromCommands(commands);
        List<String> phrases = Arrays.asList(hotwords.split("/"));
        assertTrue(phrases.contains("подогрев руля"));
        assertTrue(phrases.contains("не подогрев руля"));
        assertTrue(phrases.contains("открой навигатор"));
        assertTrue(phrases.contains("не"));
        assertFalse(hotwords.contains(":"));
        assertFalse(hotwords.contains("spotify"));
        assertFalse(hotwords.contains("70"));
        assertEquals(1, phrases.stream().filter("подогрев руля"::equals).count());
    }

    private static double powerAt(double frequency) {
        VoiceDecimator decimator = new VoiceDecimator();
        float[] input = new float[480], output = new float[160];
        double energy = 0;
        for (int frame = 0; frame < 20; frame++) {
            for (int i = 0; i < input.length; i++) input[i] = (float) Math.sin(2 * Math.PI * frequency * (frame * 480 + i) / 48000);
            decimator.process(input, output);
            if (frame >= 5) for (float sample : output) energy += sample * sample;
        }
        return energy / (15 * 160);
    }
}
