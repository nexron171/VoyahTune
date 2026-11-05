package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class EmbeddedAppLaunchCoordinatorTest {
    @Test
    public void closesOnlyConflictingHostsBeforeLaunching() {
        EmbeddedAppLaunchCoordinator coordinator = new EmbeddedAppLaunchCoordinator();
        List<String> events = new ArrayList<>();
        coordinator.register(
                "old",
                "music",
                4,
                done -> {
                    events.add("close old");
                    coordinator.remove("old");
                    done.run();
                });
        coordinator.register("target", "music", 5, done -> fail("Target closed"));
        coordinator.register("other", "maps", 6, done -> fail("Unrelated host closed"));

        coordinator.prepare("music", 5, () -> true, () -> events.add("launch"));

        assertEquals(List.of("close old", "launch"), events);
        assertEquals(2, coordinator.packages().size());
    }

    @Test
    public void waitsForSplitCompanionBeforeLaunching() {
        EmbeddedAppLaunchCoordinator coordinator = new EmbeddedAppLaunchCoordinator();
        List<Runnable> pending = new ArrayList<>();
        List<String> events = new ArrayList<>();
        coordinator.register(
                "left",
                "music",
                4,
                done -> {
                    events.add("close split");
                    coordinator.remove("left");
                    coordinator.remove("right");
                    pending.add(done);
                });
        coordinator.register("right", "maps", 5, done -> fail("Second split close"));

        coordinator.prepare("music", 1, () -> true, () -> events.add("launch passenger"));
        assertEquals(List.of("close split"), events);
        events.add("companion fullscreen");
        pending.get(0).run();
        assertEquals(List.of("close split", "companion fullscreen", "launch passenger"), events);
        assertTrue(coordinator.packages().isEmpty());
    }

    @Test
    public void cancelsStaleLaunchWhileHostIsClosing() {
        EmbeddedAppLaunchCoordinator coordinator = new EmbeddedAppLaunchCoordinator();
        List<Runnable> pending = new ArrayList<>();
        boolean[] valid = {true};
        coordinator.register("widget", "music", 4, pending::add);
        coordinator.prepare("music", 0, () -> valid[0], () -> fail("Stale launch"));
        valid[0] = false;
        pending.get(0).run();
    }

    @Test
    public void sameDisplayAndUnhostedPackageLaunchWithoutClosing() {
        EmbeddedAppLaunchCoordinator coordinator = new EmbeddedAppLaunchCoordinator();
        coordinator.register("widget", "music", 4, done -> fail("Host closed"));
        List<String> events = new ArrayList<>();
        coordinator.prepare("music", 4, () -> true, () -> events.add("same display"));
        coordinator.prepare("maps", 0, () -> true, () -> events.add("unhosted"));
        assertEquals(List.of("same display", "unhosted"), events);
    }
}
