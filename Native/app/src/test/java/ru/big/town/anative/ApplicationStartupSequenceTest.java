package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ApplicationStartupSequenceTest {
    @Test
    public void startsImmediatelyThenWaitsBetweenActionsAndRunsOnlyOnce() {
        List<String> launched = new ArrayList<>();
        List<Runnable> pending = new ArrayList<>();
        List<Long> delays = new ArrayList<>();
        ApplicationStartupSequence sequence =
                new ApplicationStartupSequence(
                        (action, done) -> {
                            launched.add(action);
                            done.run();
                        },
                        (action, delay) -> {
                            pending.add(action);
                            delays.add(delay);
                        });
        sequence.start(Arrays.asList("app:first", "split:pair", "app:last"), 3000);
        sequence.start(Collections.singletonList("app:duplicate"), 0);
        assertEquals(Collections.singletonList("app:first"), launched);
        assertEquals(Collections.singletonList(3000L), delays);
        pending.remove(0).run();
        assertEquals(Arrays.asList("app:first", "split:pair"), launched);
        pending.remove(0).run();
        assertEquals(Arrays.asList("app:first", "split:pair", "app:last"), launched);
        assertTrue(pending.isEmpty());
    }

    @Test
    public void cancellationStopsQueuedLaunchesAndFailureDoesNotStopFollowingApps() {
        List<String> launched = new ArrayList<>();
        List<Runnable> pending = new ArrayList<>();
        ApplicationStartupSequence sequence =
                new ApplicationStartupSequence(
                        (action, done) -> {
                            launched.add(action);
                            if (action.equals("missing")) throw new IllegalStateException();
                            done.run();
                        },
                        (action, delay) -> pending.add(action));
        sequence.start(Arrays.asList("missing", "next", "cancelled"), 0);
        pending.remove(0).run();
        sequence.cancel();
        pending.remove(0).run();
        assertEquals(Arrays.asList("missing", "next"), launched);
    }

    @Test
    public void waitsForLaunchCompletionAndIgnoresDuplicateCallbacks() {
        List<Runnable> completions = new ArrayList<>();
        List<Runnable> pending = new ArrayList<>();
        List<String> launched = new ArrayList<>();
        ApplicationStartupSequence sequence =
                new ApplicationStartupSequence(
                        (action, done) -> {
                            launched.add(action);
                            completions.add(done);
                        },
                        (action, delay) -> pending.add(action));
        sequence.start(Arrays.asList("first", "second"), 0);
        assertTrue(pending.isEmpty());
        completions.get(0).run();
        completions.get(0).run();
        assertEquals(1, pending.size());
        pending.remove(0).run();
        assertEquals(Arrays.asList("first", "second"), launched);
    }

    @Test
    public void emptyListDoesNotScheduleAnything() {
        ApplicationStartupSequence sequence =
                new ApplicationStartupSequence(
                        (action, done) -> {
                            throw new AssertionError();
                        },
                        (action, delay) -> {
                            throw new AssertionError();
                        });
        sequence.start(Collections.emptyList(), 0);
    }
}
