package ru.big.town.restoremode;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class SystemWidgetTest {
    @Test public void windowContainsExactlyTwoMinutesIncludingBothEndpoints() {
        SystemLoadHistory h = new SystemLoadHistory();
        for (int i = 0; i < 30; i++) assertTrue(h.add(i * 5000L, i, 70, i * 5000L));
        assertEquals(25, h.points().size());
        assertEquals(25_000, h.points().get(0).elapsed);
        assertEquals(145_000, h.points().get(24).elapsed);
        h.prune(300_000);
        assertTrue(h.points().isEmpty());
    }
    @Test public void missingSamplesAndSleepNeverFabricateContinuityOrFreshValues() {
        SystemLoadHistory h = new SystemLoadHistory();
        h.add(0, Float.NaN, 50, 0);
        h.add(5000, 0, 100, 5000);
        h.add(20000, 30, 60, 20000);
        assertTrue(Float.isNaN(h.points().get(0).cpu));
        assertEquals(0, h.points().get(1).cpu, 0);
        assertTrue(SystemLoadHistory.joins(h.points().get(0), h.points().get(1)));
        assertFalse(SystemLoadHistory.joins(h.points().get(1), h.points().get(2)));
        assertEquals(30, h.current(true, 34999), 0);
        assertTrue(Float.isNaN(h.current(true, 35000)));
        assertTrue(Float.isNaN(h.current(false, 19000)));
    }
    @Test public void rejectDuplicateFutureStaleAndInvalidServiceData() {
        SystemLoadHistory h = new SystemLoadHistory();
        assertFalse(h.add(-1, 20, 20, 100));
        assertFalse(h.add(101, 20, 20, 100));
        assertFalse(h.add(0, 20, 20, 20000));
        assertTrue(h.add(100, Float.POSITIVE_INFINITY, -1, 100));
        assertFalse(h.add(100, 20, 20, 100));
        assertFalse(h.add(99, 20, 20, 100));
        assertTrue(Float.isNaN(h.current(true, 100)));
        assertTrue(Float.isNaN(h.current(false, 100)));
    }
    @Test public void sizesPersistIndependentlyAndStayInsideApprovedRanges() {
        Map<String, Object> saved = new HashMap<>();
        SharedPreferences prefs = prefs(saved);
        for (String id : new String[]{SystemWidgetLayout.CPU, SystemWidgetLayout.RAM, SystemWidgetLayout.CLEAR}) {
            assertArrayEquals(new int[]{1, 1}, TileSizeStore.dimensions(prefs, id, 1, 1));
            TileSizeStore.setWidth(prefs, id, 12);
            TileSizeStore.setHeight(prefs, id, 5);
            assertArrayEquals(new int[]{SystemWidgetLayout.CLEAR.equals(id) ? 1 : 2, 1},
                    TileSizeStore.dimensions(prefs(saved), id, 1, 1));
        }
        TileSizeStore.setWidth(prefs, SystemWidgetLayout.CPU, -1);
        assertEquals(1, TileSizeStore.width(prefs, SystemWidgetLayout.CPU, 1));
        assertEquals(2, TileSizeStore.width(prefs, SystemWidgetLayout.RAM, 1));
    }
    private SharedPreferences prefs(Map<String, Object> values) {
        final Object[] editor = new Object[1];
        editor[0] = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class}, (p,m,a) -> {
            if (m.getName().startsWith("put")) { values.put((String) a[0], a[1]); return editor[0]; }
            return null;
        });
        return (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class}, (p,m,a) -> {
            if (m.getName().equals("edit")) return editor[0];
            if (m.getName().startsWith("get") && a.length == 2) return values.getOrDefault(a[0], a[1]);
            return null;
        });
    }
}
