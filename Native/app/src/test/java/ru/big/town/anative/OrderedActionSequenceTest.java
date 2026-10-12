package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

public class OrderedActionSequenceTest {
    @Test
    public void waitsForEachActionAndIgnoresRepeatedCallbacks() {
        List<String> executed = new ArrayList<>();
        List<Runnable> terminals = new ArrayList<>();
        List<Boolean> completed = new ArrayList<>();
        Queue<Runnable> scheduled = new ArrayDeque<>();
        List<String> configured =
                new ArrayList<>(Arrays.asList("headlights:parking", "app:com.demo"));
        OrderedActionSequence.run(
                configured,
                (action, terminal) -> {
                    executed.add(action);
                    terminals.add(terminal);
                },
                scheduled::add,
                completed::add);
        configured.clear();

        assertEquals(Collections.singletonList("headlights:parking"), executed);
        assertTrue(scheduled.isEmpty());
        assertTrue(completed.isEmpty());
        terminals.get(0).run();
        terminals.get(0).run();
        assertEquals(1, scheduled.size());
        scheduled.remove().run();
        assertEquals(Arrays.asList("headlights:parking", "app:com.demo"), executed);
        assertTrue(completed.isEmpty());
        terminals.get(1).run();
        terminals.get(1).run();
        scheduled.remove().run();
        assertTrue(scheduled.isEmpty());
        assertEquals(Collections.singletonList(true), completed);
    }

    @Test
    public void exceptionStopsAlreadyQueuedFollowupActions() {
        Queue<Runnable> scheduled = new ArrayDeque<>();
        List<String> executed = new ArrayList<>();
        List<Boolean> completed = new ArrayList<>();
        OrderedActionSequence.run(
                Arrays.asList("first", "second"),
                (action, terminal) -> {
                    executed.add(action);
                    terminal.run();
                    throw new IllegalStateException("Action failed");
                },
                scheduled::add,
                completed::add);
        scheduled.remove().run();
        assertEquals(Collections.singletonList("first"), executed);
        assertEquals(Collections.singletonList(false), completed);
    }
}
