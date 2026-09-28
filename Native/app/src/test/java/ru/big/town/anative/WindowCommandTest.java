package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;

public class WindowCommandTest {
    private void maps(String action, String field, int value) {
        WindowCommand c = WindowCommand.parse(action);
        assertNotNull(action, c); assertTrue(WindowCommand.handles(action));
        assertEquals(action, field, c.field); assertEquals(action, value, c.value);
    }

    @Test public void all24ActionsMapToOneCorrectFieldAndValue() {
        String[][] fields = {{"", "ALL_WINDOW_CONTROL"}, {"driver:", "DRIVER_WINDOW_CONTROL"},
                {"passenger:", "PAS_WIDOW_CONTROL"}, {"rear_left:", "LEFT_BACK_WINDOW_CONTROL"},
                {"rear_right:", "RIGHT_BACK_WINDOW_CONTROL"}, {"front:", "ALL_FRONT_WINDOW_CONTROL"},
                {"rear:", "ALL_REAR_WINDOW_CONTROL"}, {"left:", "ALL_LEFT_WINDOW_CONTROL"}, {"right:", "ALL_RIGHT_WINDOW_CONTROL"}};
        for (String[] f : fields) {
            maps("windows:" + f[0] + "open", f[1], 3);
            maps("windows:" + f[0] + "close", f[1], 1);
        }
        maps("windows:vent", "ALL_WINDOW_CONTROL", 6);
        maps("sunroof:open", "SunroofControl", 1);
        maps("sunroof:close", "SunroofControl", 2);
        maps("sunroof:vent", "SunroofControl", 4);
        maps("sunshade:open", "SunroofControl", 5);
        maps("sunshade:close", "SunroofControl", 6);
    }

    @Test public void arbitraryFieldsValuesTargetsAndUnsupportedOperationsAreRejected() {
        for (String s : new String[]{null, "", "windows", "windows::open", "windows:all:open", "windows:unknown:open",
                "windows:driver:vent", "windows:rear:vent", "windows:toggle", "windows:stop", "windows:50",
                "windows:ALL_WINDOW_CONTROL:3", "windows:DRIVER_WINDOW_VENTILATE_STS:1", "windows:driver:open:",
                "sunroof:open:1", "sunroof:3", "sunroof:stop", "sunshade:vent", "sunshade:toggle", "sunshade:7",
                "sunroof:open ", "windows:OPEN", "windows:front:left:open"}) assertNull(s, WindowCommand.parse(s));
    }
}
