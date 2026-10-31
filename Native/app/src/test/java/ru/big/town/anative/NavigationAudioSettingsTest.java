package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.big.town.common.NavigationAudioSettings;

public final class NavigationAudioSettingsTest {
    @Test
    public void normalizesSelectedPackagesAndRejectsProcessNamesAndVoyahTune() {
        assertEquals(
                "ru.yandex.yandexnavi,ru.dublgis.dgismobile",
                NavigationAudioSettings.normalizeCsv(
                        " ru.yandex.yandexnavi,invalid,ru.yandex.yandexnavi,"
                                + "ru.dublgis.dgismobile,bad/package,app.pkg:tts,"
                                + "ru.big.town.restoremode,ru.big.town.anative"));
        assertEquals("", NavigationAudioSettings.normalizeCsv(null));
    }

    @Test
    public void matchesOnlyCompleteSelectedPackageNames() {
        String packages = "ru.yandex.yandexnavi,ru.yandex.yandexmaps";
        assertTrue(NavigationAudioSettings.contains(packages, "ru.yandex.yandexnavi"));
        assertFalse(NavigationAudioSettings.contains(packages, "ru.yandex"));
        assertFalse(NavigationAudioSettings.contains(packages, "ru.yandex.yandexmaps.extra"));
        assertFalse(NavigationAudioSettings.contains(packages, null));
    }
}
