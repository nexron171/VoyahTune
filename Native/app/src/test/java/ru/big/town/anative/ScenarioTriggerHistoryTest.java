package ru.big.town.anative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class ScenarioTriggerHistoryTest {
    private static final class MemoryStore implements ScenarioTriggerHistory.Store {
        Set<String> consumed = new HashSet<>();

        @Override
        public Set<String> read() {
            return consumed;
        }

        @Override
        public void write(Set<String> value) {
            consumed = value;
        }
    }

    @Test
    public void oldTriggersContinueAcceptingEveryEvent() {
        ScenarioTriggerHistory history = ScenarioTriggerHistory.inMemory();
        ScenarioDefinition.Trigger trigger = new ScenarioDefinition.Trigger("gear", "D", "");
        assertTrue(history.accept("scenario", trigger));
        assertTrue(history.accept("scenario", trigger));
    }

    @Test
    public void firstEventSurvivesServiceRecreationAndConfigurationReload() {
        MemoryStore store = new MemoryStore();
        assertTrue(new ScenarioTriggerHistory(store).accept("scenario", firstDrive()));
        assertFalse(new ScenarioTriggerHistory(store).accept("scenario", firstDrive()));
        store.consumed.clear();
        assertTrue(new ScenarioTriggerHistory(store).accept("scenario", firstDrive()));
    }

    @Test
    public void scenariosAndDifferentDoorEdgesHaveIndependentHistory() {
        ScenarioTriggerHistory history = ScenarioTriggerHistory.inMemory();
        ScenarioDefinition.Trigger open = new ScenarioDefinition.Trigger("door", "driver", "open", true);
        ScenarioDefinition.Trigger close = new ScenarioDefinition.Trigger("door", "driver", "close", true);
        assertTrue(history.accept("one", firstDrive()));
        assertTrue(history.accept("two", firstDrive()));
        assertTrue(history.accept("one", open));
        assertTrue(history.accept("one", close));
        assertFalse(history.accept("one", open));
        assertFalse(history.accept("one", firstDrive()));
    }

    private static ScenarioDefinition.Trigger firstDrive() {
        return new ScenarioDefinition.Trigger("gear", "D", "", true);
    }
}
