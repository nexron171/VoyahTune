package ru.big.town.restoremode;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FragranceSettingsTest {
    @Test
    public void validOemValuesArePreserved() {
        assertEquals(1, FragranceSettings.normalizeTaste(1));
        assertEquals(3, FragranceSettings.normalizeTaste(3));
        assertEquals(0, FragranceSettings.normalizeDuration(0));
        assertEquals(2, FragranceSettings.normalizeDuration(2));
        assertEquals(1, FragranceSettings.normalizeIntensity(1));
        assertEquals(3, FragranceSettings.normalizeIntensity(3));
    }

    @Test
    public void invalidValuesFallBackToSafeDefaults() {
        assertEquals(FragranceSettings.DEFAULT_TASTE,
                FragranceSettings.normalizeTaste(0));
        assertEquals(FragranceSettings.DEFAULT_DURATION,
                FragranceSettings.normalizeDuration(3));
        assertEquals(FragranceSettings.DEFAULT_INTENSITY,
                FragranceSettings.normalizeIntensity(-1));
    }
}
