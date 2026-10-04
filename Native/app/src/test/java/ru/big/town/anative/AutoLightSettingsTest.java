package ru.big.town.anative;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class AutoLightSettingsTest {
    private static final class Runtime implements AutoLightSettings.Runtime {
        Boolean saved;
        boolean cached;
        boolean unavailable;
        final List<String> actions = new ArrayList<>();
        @Override public Boolean saved() {
            if (unavailable) throw new IllegalStateException("provider unavailable");
            return saved;
        }
        @Override public boolean cached() { return cached; }
        @Override public void cache(boolean enabled) {
            cached = enabled;
            actions.add("cache:" + enabled);
        }
        @Override public void apply(boolean enabled) { actions.add("service:" + enabled); }
    }

    @Test public void choiceSavedWithoutNativeOverridesItsOlderCache() {
        Runtime runtime = new Runtime();
        runtime.saved = true;
        AutoLightSettings.restore(runtime);
        assertTrue(runtime.cached);
        assertEquals(Arrays.asList("cache:true", "service:true"), runtime.actions);
    }

    @Test public void savedOffStopsServiceEvenWhenNativePreviouslyCachedOn() {
        Runtime runtime = new Runtime();
        runtime.saved = false;
        runtime.cached = true;
        AutoLightSettings.restore(runtime);
        assertFalse(runtime.cached);
        assertEquals(Arrays.asList("cache:false", "service:false"), runtime.actions);
    }

    @Test public void unavailableProviderAndMissingColumnKeepCachedChoice() {
        for (boolean unavailable : new boolean[]{false, true}) {
            for (boolean cached : new boolean[]{false, true}) {
                Runtime runtime = new Runtime();
                runtime.unavailable = unavailable;
                runtime.cached = cached;
                AutoLightSettings.restore(runtime);
                assertEquals(cached, runtime.cached);
                assertEquals("service:" + cached, runtime.actions.get(1));
            }
        }
    }

    @Test public void nextAccReadsCurrentChoiceAndRevivesServiceRatherThanReusingStartupDecision() {
        Runtime runtime = new Runtime();
        runtime.saved = true;
        AutoLightSettings.restore(runtime);
        AutoLightSettings.restore(runtime); // Service might have been killed during sleep.
        runtime.saved = false;
        AutoLightSettings.restore(runtime);
        assertEquals(Arrays.asList("cache:true", "service:true", "cache:true", "service:true",
                "cache:false", "service:false"), runtime.actions);
    }
}
