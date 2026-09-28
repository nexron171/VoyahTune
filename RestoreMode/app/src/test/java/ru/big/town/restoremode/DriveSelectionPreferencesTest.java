package ru.big.town.restoremode;

import android.content.SharedPreferences;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import ru.big.town.common.DriveSelectionPolicy;
import static org.junit.Assert.*;

public class DriveSelectionPreferencesTest {
    private SharedPreferences prefs(Map<String, Object> values) {
        final Object[] editor = new Object[1];
        editor[0] = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                (p, m, a) -> {
                    if (m.getName().startsWith("put")) { values.put((String) a[0], a[1]); return editor[0]; }
                    if (m.getName().equals("commit")) return true;
                    return null;
                });
        return (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (p, m, a) -> {
                    if (m.getName().equals("edit")) return editor[0];
                    if (m.getName().startsWith("get") && a != null && a.length == 2)
                        return values.getOrDefault((String) a[0], a[1]);
                    return null;
                });
    }
    @Test public void currentDriveSurvivesProviderRecreationWithoutReplacingPinnedMode() {
        Map<String, Object> data = new HashMap<>(); data.put("driveMode", "COMFORT"); data.put("driveRememberLast", false);
        assertTrue(DriveSelectionPreferences.select(prefs(data), "ECO", DriveSelectionPolicy.EXPLICIT));
        assertEquals("ECO", DriveSelectionPreferences.read(prefs(data)).effective());
        assertEquals("COMFORT", DriveSelectionPreferences.read(prefs(data)).nextTrip().effective());
        assertFalse(DriveSelectionPreferences.select(prefs(data), "SPORT", DriveSelectionPolicy.FEEDBACK));
        assertEquals("ECO", DriveSelectionPreferences.read(prefs(data)).effective());
    }
    @Test public void energyOptOutPreservesPinnedSettingButRemembersCurrentTrip() {
        Map<String, Object> data = new HashMap<>(); data.put("energy", "SREV"); data.put("energyRememberLast", false);
        assertTrue(DriveSelectionPreferences.selectEnergy(prefs(data), "EV", false));
        assertEquals("SREV", data.get("energy"));
        assertEquals("EV", DriveSelectionPreferences.energy(prefs(data)));
        assertTrue(DriveSelectionPreferences.selectEnergy(prefs(data), "REV", true));
        assertEquals("REV", data.get("energy"));
    }
    @Test public void forcedEvDoesNotReplaceNormalEnergySelection() {
        Map<String, Object> data = new HashMap<>(); data.put("energy", "SREV");
        DriveSelectionPreferences.selectEnergy(prefs(data), "FORCE_EV", false);
        assertEquals("SREV", data.get("energy")); assertEquals(true, data.get("forcedEv"));
        assertEquals("FORCE_EV", DriveSelectionPreferences.energy(prefs(data)));
        DriveSelectionPreferences.selectEnergy(prefs(data), "EV", false);
        assertEquals(false, data.get("forcedEv")); assertEquals("EV", data.get("energy"));
    }
}
