package ru.big.town.anative;

import java.util.HashSet;
import java.util.Set;

/** Tracks events before condition evaluation, independently for each trigger. */
final class ScenarioTriggerHistory {
    interface Store {
        Set<String> read();
        void write(Set<String> consumed);
    }

    private final Store store;

    ScenarioTriggerHistory(Store store) {
        this.store = store;
    }

    static ScenarioTriggerHistory inMemory() {
        return new ScenarioTriggerHistory(new Store() {
            private Set<String> consumed = new HashSet<>();

            @Override
            public Set<String> read() {
                return consumed;
            }

            @Override
            public void write(Set<String> value) {
                consumed = value;
            }
        });
    }

    boolean accept(String scenarioId, ScenarioDefinition.Trigger trigger) {
        if (!trigger.firstOnly) return true;
        String key = scenarioId.length() + ":" + scenarioId + ":" + trigger.type
                + ":" + trigger.value + ":" + trigger.edge;
        synchronized (store) {
            Set<String> consumed = new HashSet<>(store.read());
            if (!consumed.add(key)) return false;
            store.write(consumed);
            return true;
        }
    }
}
