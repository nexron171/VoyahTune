package ru.big.town.restoremode.vehicle;

import static org.junit.Assert.*;

import android.content.SharedPreferences;

import org.junit.Test;

import ru.big.town.common.DriveSelectionPolicy;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public class DriveSelectionPreferencesTest {
    private SharedPreferences prefs(Map<String, Object> values) {
        final Object[] editor = new Object[1];
        editor[0] =
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {SharedPreferences.Editor.class},
                        (p, m, a) -> {
                            if (m.getName().startsWith("put")) {
                                values.put((String) a[0], a[1]);
                                return editor[0];
                            }
                            if (m.getName().equals("commit")) {
                                return true;
                            }
                            return null;
                        });
        return (SharedPreferences)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {SharedPreferences.class},
                        (p, m, a) -> {
                            if (m.getName().equals("edit")) {
                                return editor[0];
                            }
                            if (m.getName().startsWith("get") && a != null && a.length == 2) {
                                return values.getOrDefault((String) a[0], a[1]);
                            }
                            return null;
                        });
    }

    @Test
    public void recuperationRemembersExplicitSelectionButHonorsOptOut() {
        for (boolean remember : new boolean[] {true, false}) {
            Map<String, Object> data = new HashMap<>();
            data.put("recycle", "LOW");
            data.put("recycleRememberLast", remember);
            assertTrue(DriveSelectionPreferences.selectRecycle(prefs(data), "HIGH"));
            assertEquals("HIGH", DriveSelectionPreferences.recycle(prefs(data)));
            assertEquals(remember ? "HIGH" : "LOW", data.get("recycle"));
            assertFalse(DriveSelectionPreferences.selectRecycle(prefs(data), "invalid"));
        }
    }

    @Test
    public void piRecuperationKeepsConfiguredTargetAndNativeRestoreDoesNotClearOdTripState() {
        Map<String, Object> data = new HashMap<>();
        data.put("recycle", "LOW");
        data.put("currentTripRecycle", "HIGH");
        assertEquals("LOW", DriveSelectionPreferences.recycle(prefs(data), false));
        assertTrue(DriveSelectionPreferences.beginNativeRestore(prefs(data)));
        assertEquals("LOW", DriveSelectionPreferences.recycle(prefs(data), false));
        assertEquals("HIGH", data.get("currentTripRecycle"));
    }

    @Test
    public void currentDriveSurvivesProviderRecreationWithoutReplacingPinnedMode() {
        Map<String, Object> data = new HashMap<>();
        data.put("driveMode", "COMFORT");
        data.put("driveRememberLast", false);
        assertTrue(
                DriveSelectionPreferences.select(
                        prefs(data), "ECO", DriveSelectionPolicy.EXPLICIT));
        assertEquals("ECO", DriveSelectionPreferences.read(prefs(data)).effective());
        assertEquals("COMFORT", DriveSelectionPreferences.read(prefs(data)).nextTrip().effective());
        assertFalse(
                DriveSelectionPreferences.select(
                        prefs(data), "SPORT", DriveSelectionPolicy.FEEDBACK));
        assertEquals("ECO", DriveSelectionPreferences.read(prefs(data)).effective());
    }

    @Test
    public void userSelectionSupersedesStartupAndPersistsMediumAcrossProviderReads() {
        for (String startup : new String[] {"pending", "claimed", "submitted", "uncertain"}) {
            for (boolean remember : new boolean[] {false, true}) {
                Map<String, Object> data = new HashMap<>();
                data.put("driveMode", "ECO");
                data.put("driveRememberLast", remember);
                data.put("driveStartup", startup);
                data.put("driveRevision", 8L);
                data.put(DriveSelectionPolicy.OVERRIDE, "SPORT");
                assertTrue(
                        DriveSelectionPreferences.select(
                                prefs(data), "COMFORT", DriveSelectionPolicy.EXPLICIT));
                assertEquals("selected", data.get("driveStartup"));
                assertEquals(9L, data.get("driveRevision"));
                DriveSelectionPolicy state = DriveSelectionPreferences.read(prefs(data));
                assertEquals("COMFORT", state.effective());
                assertEquals("", state.override);
                assertEquals(remember ? "COMFORT" : "ECO", state.configured);
                assertTrue(
                        DriveSelectionPreferences.select(
                                prefs(data), "SPORT", DriveSelectionPolicy.WIDGET));
                assertEquals("COMFORT", DriveSelectionPreferences.read(prefs(data)).medium);
                assertFalse(
                        DriveSelectionPreferences.select(
                                prefs(data), "ECO", DriveSelectionPolicy.FEEDBACK));
                assertEquals("COMFORT", DriveSelectionPreferences.read(prefs(data)).medium);
            }
        }
    }

    @Test
    public void energyOptOutPreservesPinnedSettingButRemembersCurrentTrip() {
        Map<String, Object> data = new HashMap<>();
        data.put("energy", "SREV");
        data.put("energyRememberLast", false);
        assertTrue(DriveSelectionPreferences.selectEnergy(prefs(data), "EV", false));
        assertEquals("SREV", data.get("energy"));
        assertEquals("EV", DriveSelectionPreferences.energy(prefs(data)));
        assertEquals("selected", data.get("driveStartup"));
        assertTrue(DriveSelectionPreferences.selectEnergy(prefs(data), "REV", true));
        assertEquals("REV", data.get("energy"));
    }

    @Test
    public void forcedEvDoesNotReplaceNormalEnergySelection() {
        Map<String, Object> data = new HashMap<>();
        data.put("energy", "SREV");
        DriveSelectionPreferences.selectEnergy(prefs(data), "FORCE_EV", false);
        assertEquals("SREV", data.get("energy"));
        assertEquals(true, data.get("forcedEv"));
        assertEquals("FORCE_EV", DriveSelectionPreferences.energy(prefs(data)));
        DriveSelectionPreferences.selectEnergy(prefs(data), "EV", false);
        assertEquals(false, data.get("forcedEv"));
        assertEquals("EV", data.get("energy"));
    }

    @Test
    public void nativeRestoreClearsOnlyCurrentTripAndRetainsPinnedWidgetTargets() {
        Map<String, Object> data = new HashMap<>();
        data.put("driveMode", "COMFORT");
        data.put("energy", "SREV");
        data.put("driveRememberLast", false);
        data.put("energyRememberLast", false);
        DriveSelectionPreferences.select(prefs(data), "SPORT", DriveSelectionPolicy.WIDGET);
        DriveSelectionPreferences.selectEnergy(prefs(data), "EV", false);
        long revision = (long) data.get("driveRevision");
        assertTrue(DriveSelectionPreferences.beginNativeRestore(prefs(data)));
        assertEquals(revision + 1, data.get("driveRevision"));
        assertEquals("SPORT", DriveSelectionPreferences.read(prefs(data)).effective());
        assertEquals("COMFORT", DriveSelectionPreferences.read(prefs(data)).configured);
        assertEquals("SREV", DriveSelectionPreferences.energy(prefs(data)));
        assertEquals("", data.get(DriveSelectionPolicy.CURRENT));
    }
}
