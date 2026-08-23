package ru.big.town.anative;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

public class PlaybackActivityTrackerTest {
    @Test
    public void repeatedStatesPreserveEveryEntryIntoActivePlayback() {
        PlaybackActivityTracker tracker = new PlaybackActivityTracker(false);
        List<PlaybackActivityTracker.Change> changes = Arrays.asList(
                tracker.update(false),
                tracker.update(true),
                tracker.update(true),
                tracker.update(false),
                tracker.update(true));

        assertEquals(2L, changes.stream()
                .filter(change -> change == PlaybackActivityTracker.Change.ENTERED_ACTIVE)
                .count());
        assertEquals(Arrays.asList(
                        PlaybackActivityTracker.Change.ENTERED_ACTIVE,
                        PlaybackActivityTracker.Change.LEFT_ACTIVE,
                        PlaybackActivityTracker.Change.ENTERED_ACTIVE),
                changes.stream()
                        .filter(change -> change != PlaybackActivityTracker.Change.SAME)
                        .collect(Collectors.toList()));
    }
}
