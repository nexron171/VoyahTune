package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ScreenBrightnessModeControllerTest {
    @Test
    public void enablingSelectsDayOnEveryScreen() throws Exception {
        Modes modes = new Modes(2);
        assertTrue(ScreenBrightnessModeController.apply(modes, 1, () -> true));
        assertEquals(Arrays.asList("1=1", "2=1", "16=1"), modes.writes);
    }

    @Test
    public void disablingRestoresAutoAndRepeatedReconciliationDoesNotWriteAgain() throws Exception {
        Modes modes = new Modes(1);
        assertTrue(ScreenBrightnessModeController.apply(modes, 2, () -> true));
        assertEquals(Arrays.asList("1=2", "2=2", "16=2"), modes.writes);
        modes.writes.clear();
        assertTrue(ScreenBrightnessModeController.apply(modes, 2, () -> true));
        assertTrue(modes.writes.isEmpty());
    }

    @Test
    public void unconfirmedModeMustNotAllowBrightnessCommands() throws Exception {
        Modes modes = new Modes(0);
        modes.ignoreWrites = true;
        assertFalse(ScreenBrightnessModeController.apply(modes, 1, () -> true));
    }

    @Test
    public void changedSettingCancelsRemainingModeCommands() throws Exception {
        Modes modes = new Modes(2);
        assertFalse(ScreenBrightnessModeController.apply(modes, 1, () -> modes.writes.isEmpty()));
        assertEquals(Arrays.asList("1=1"), modes.writes);
    }

    @Test
    public void failedRestorationCanBeRetried() throws Exception {
        Modes modes = new Modes(1);
        modes.ignoreWrites = true;
        assertFalse(ScreenBrightnessModeController.apply(modes, 2, () -> true));
        modes.ignoreWrites = false;
        assertTrue(ScreenBrightnessModeController.apply(modes, 2, () -> true));
    }

    private static final class Modes implements ScreenBrightnessModeController.Modes {
        final Map<Integer, Integer> values = new HashMap<>();
        final List<String> writes = new ArrayList<>();
        boolean ignoreWrites;

        Modes(int initial) {
            for (int screen : new int[] {1, 2, 16}) values.put(screen, initial);
        }

        @Override
        public int readMode(int screen) {
            return values.get(screen);
        }

        @Override
        public boolean writeMode(int screen, int mode) {
            writes.add(screen + "=" + mode);
            if (!ignoreWrites) values.put(screen, mode);
            return true;
        }
    }
}
