package ru.big.town.anative;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FullscreenPackagePolicyTest {
    @Test
    public void normalizeDropsInvalidAndDuplicatePackages() {
        assertEquals("com.example.nav,ru.test.player",
                FullscreenPackagePolicy.normalizeCsv(
                        " com.example.nav,invalid,com.example.nav,ru.test.player,bad/pkg "));
    }

    @Test
    public void membershipMatchesWholePackageOnly() {
        String csv = "com.example.nav,com.example.music";
        assertTrue(FullscreenPackagePolicy.contains(csv, "com.example.nav"));
        assertFalse(FullscreenPackagePolicy.contains(csv, "com.example"));
        assertFalse(FullscreenPackagePolicy.contains(csv, "com.example.navigation"));
    }

    @Test
    public void fullscreenListKeepsServiceButOnlyShowsOverlayForTopMatch() {
        String csv = "com.example.nav";
        assertTrue(FullscreenPackagePolicy.requiresAccessibilityService(false, false, csv));
        assertTrue(FullscreenPackagePolicy.shouldShowOverlay(false, csv, "com.example.nav"));
        assertFalse(FullscreenPackagePolicy.shouldShowOverlay(false, csv, "com.example.music"));
        assertTrue(FullscreenPackagePolicy.shouldShowOverlay(true, csv, "com.example.music"));
    }
}
