package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

public class HotspotControllerTest {
    @Test
    public void matchingStateDoesNotRestartTheHotspot() {
        for (boolean state : new boolean[] {false, true}) {
            FakeBackend backend = new FakeBackend(state);
            assertEquals(
                    "", HotspotController.execute(backend, state ? "hotspot:on" : "hotspot:off"));
            assertEquals(0, backend.starts);
            assertEquals(0, backend.stops);
        }
    }

    @Test
    public void startsAndStopsThroughTetheringAndReadsBackTheState() {
        for (boolean target : new boolean[] {false, true}) {
            FakeBackend backend = new FakeBackend(!target);
            assertEquals(
                    "", HotspotController.execute(backend, target ? "hotspot:on" : "hotspot:off"));
            assertEquals(target ? 1 : 0, backend.starts);
            assertEquals(target ? 0 : 1, backend.stops);
            assertEquals(2, backend.reads);
        }
    }

    @Test
    public void toggleUsesObservedStateAndRefusesUnknownState() {
        FakeBackend backend = new FakeBackend(null);
        assertFalse(HotspotController.execute(backend, "hotspot:toggle").isEmpty());
        assertEquals(0, backend.starts + backend.stops);
        backend.state = true;
        assertEquals("", HotspotController.execute(backend, "hotspot:toggle"));
        assertEquals(1, backend.stops);
    }

    @Test
    public void unconfirmedStartIsNotReportedAsSuccessOrRetried() {
        FakeBackend backend = new FakeBackend(false);
        backend.updatesState = false;
        assertFalse(HotspotController.execute(backend, "hotspot:on").isEmpty());
        assertEquals(1, backend.starts);
    }

    private static final class FakeBackend implements HotspotController.Backend {
        Boolean state;
        int starts;
        int stops;
        int reads;
        boolean updatesState = true;

        FakeBackend(Boolean state) {
            this.state = state;
        }

        public Boolean enabled() {
            reads++;
            return state;
        }

        public void start() {
            starts++;
            if (updatesState) state = true;
        }

        public void stop() {
            stops++;
            if (updatesState) state = false;
        }
    }
}
