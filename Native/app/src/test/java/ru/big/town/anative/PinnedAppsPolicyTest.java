package ru.big.town.anative;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PinnedAppsPolicyTest {

    @Test
    public void normalizeDropsInvalidAndDuplicatePackages() {
        assertEquals("com.example.nav,ru.test.player",
                PinnedAppsPolicy.normalizeCsv(
                        " com.example.nav,invalid,com.example.nav,ru.test.player,bad/pkg "));
    }

    @Test
    public void membershipMatchesWholePackageOnly() {
        String csv = "com.example.nav,com.example.music";
        assertTrue(PinnedAppsPolicy.contains(csv, "com.example.nav"));
        assertFalse(PinnedAppsPolicy.contains(csv, "com.example"));
        assertFalse(PinnedAppsPolicy.contains(csv, "com.example.navigation"));
        assertFalse(PinnedAppsPolicy.contains("", "com.example.nav"));
    }

    @Test
    public void pinToggleKeepsOrderAndIsIdempotent() {
        String pinned = PinnedAppsPolicy.setPinned("", "com.example.nav", true);
        assertEquals("com.example.nav", pinned);
        assertEquals(pinned, PinnedAppsPolicy.setPinned(pinned, "com.example.nav", true));

        String both = PinnedAppsPolicy.setPinned(pinned, "ru.test.player", true);
        assertEquals("com.example.nav,ru.test.player", both);
        assertEquals(pinned, PinnedAppsPolicy.setPinned(both, "ru.test.player", false));
        assertEquals(pinned, PinnedAppsPolicy.setPinned(pinned, "ru.test.player", false));
    }

    @Test
    public void invalidPackageNeverChangesTheList() {
        assertEquals("com.example.nav",
                PinnedAppsPolicy.setPinned("com.example.nav", "bad/pkg", true));
        assertEquals("com.example.nav",
                PinnedAppsPolicy.setPinned("com.example.nav", null, true));
    }
}
