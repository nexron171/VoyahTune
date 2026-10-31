package ru.big.town.anative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.big.town.common.NavigationAudioSettings;

import java.util.List;
import java.util.Set;

public final class NavigationAudioPlaybackTest {
    @Test
    public void onlyActiveNavigationOutputFromSelectedUidTriggersDucking() {
        Set<Integer> selected = Set.of(10042);
        assertTrue(NavigationAudioPlayback.matches(true, 1, 10042, selected, "app.nav", List.of()));
        assertFalse(
                NavigationAudioPlayback.matches(false, 1, 10042, selected, "app.nav", List.of()));
        assertFalse(
                NavigationAudioPlayback.matches(true, 3, 10042, selected, "app.nav", List.of()));
        assertFalse(
                NavigationAudioPlayback.matches(true, 1, 10043, selected, "app.nav", List.of()));
    }

    @Test
    public void ttsEngineUsesSelectedCallerTagAndStopsMatchingWhenDeselected() {
        List<String> tags = List.of(NavigationAudioSettings.TAG_PREFIX + "app.nav");
        assertTrue(NavigationAudioPlayback.matches(true, 1, 10075, Set.of(), "app.nav", tags));
        assertFalse(
                NavigationAudioPlayback.matches(true, 1, 10075, Set.of(), "app.navigation", tags));
        assertFalse(NavigationAudioPlayback.matches(true, 1, 10075, Set.of(), "", tags));
        assertFalse(NavigationAudioPlayback.matches(true, 3, 10075, Set.of(), "app.nav", tags));
    }
}
