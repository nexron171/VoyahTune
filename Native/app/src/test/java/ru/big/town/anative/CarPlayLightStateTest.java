package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CarPlayLightStateTest {
    private final ArrayDeque<Runnable> queries = new ArrayDeque<>();
    private final ArrayDeque<Runnable> replies = new ArrayDeque<>();
    private final List<Integer> published = new ArrayList<>();
    private Integer currentReason = 2;
    private int readCount;
    private final CarPlayLightState state = new CarPlayLightState(
            queries::add,
            replies::add,
            () -> {
                readCount++;
                return currentReason;
            },
            published::add);

    @Test
    public void alreadyDarkAtStartupIsPublishedWithoutAChangeEvent() {
        for (int reason : new int[] {2, 3, 4}) {
            currentReason = reason;
            state.refresh();
            assertTrue(published.isEmpty());
            queries.remove().run();
            assertTrue(published.isEmpty());
            replies.remove().run();
            assertEquals(Collections.singletonList(reason), published);
            published.clear();
            state.reset();
        }
        assertEquals(3, readCount);
    }

    @Test
    public void eventsContinueUpdatingAfterTheInitialRead() {
        state.refresh();
        completeRead();
        state.onEvent(0);
        state.onEvent(3);
        assertEquals(Arrays.asList(2, 0, 3), published);
        assertEquals(1, readCount);
        assertTrue(queries.isEmpty());
    }

    @Test
    public void delayedSnapshotCannotOverrideANewerLiveEvent() {
        state.refresh();
        queries.remove().run();
        state.onEvent(0);
        replies.remove().run();
        assertEquals(Collections.singletonList(0), published);
    }

    @Test
    public void reconnectRejectsTheOldSnapshotAndReadsCurrentState() {
        state.refresh();
        queries.remove().run();
        state.reset();
        currentReason = 0;
        state.refresh();
        replies.remove().run();
        assertTrue(published.isEmpty());
        completeRead();
        assertEquals(Collections.singletonList(0), published);
    }

    @Test
    public void closedSessionIgnoresPendingReadsAndEvents() {
        state.refresh();
        state.reset();
        completeRead();
        state.onEvent(4);
        assertTrue(published.isEmpty());
    }

    @Test
    public void unavailableReadDoesNotInventDayModeOrDisableEvents() {
        currentReason = null;
        state.refresh();
        completeRead();
        assertTrue(published.isEmpty());
        state.onEvent(4);
        assertEquals(Collections.singletonList(4), published);
    }

    private void completeRead() {
        queries.remove().run();
        replies.remove().run();
    }
}
