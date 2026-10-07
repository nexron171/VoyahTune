package ru.big.town.restoremode.widgets.energy;

import static org.junit.Assert.*;

import android.content.SharedPreferences;

import org.junit.Test;

import ru.big.town.common.EnergyWidgetSettings;
import ru.big.town.restoremode.dashboard.tiles.TileSizeStore;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public class EnergyWidgetTest {
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
                            if (m.getName().startsWith("get") && a.length == 2) {
                                return values.getOrDefault((String) a[0], a[1]);
                            }
                            return null;
                        });
    }

    @Test
    public void approvedSizesClampLegacySettingsAndSurviveRecreation() {
        Map<String, Object> saved = new HashMap<>();
        SharedPreferences prefs = prefs(saved);
        for (String id :
                new String[] {
                    "energyWidget",
                    "energyConsumptionWidget",
                    "energyTripWidget",
                    "tirePressureWidget",
                    "odometerWidget"
                }) {
            TileSizeStore.setWidth(prefs, id, 1);
            TileSizeStore.setHeight(prefs, id, 1);
            assertEquals(EnergyWidgetLayout.minWidth(id), TileSizeStore.width(prefs(saved), id, 1));
            assertEquals(
                    EnergyWidgetLayout.minHeight(id, EnergyWidgetLayout.minWidth(id)),
                    TileSizeStore.height(prefs(saved), id, 1));
            TileSizeStore.setWidth(prefs, id, 12);
            TileSizeStore.setHeight(prefs, id, 5);
            assertEquals(EnergyWidgetLayout.maxWidth(id), TileSizeStore.width(prefs(saved), id, 1));
            assertEquals(
                    EnergyWidgetLayout.maxHeight(id, EnergyWidgetLayout.maxWidth(id)),
                    TileSizeStore.height(prefs(saved), id, 1));
        }
        assertEquals(286, EnergyWidgetLayout.pixelsWide(2));
        assertEquals(580, EnergyWidgetLayout.pixelsWide(4));
        assertEquals(1168, EnergyWidgetLayout.pixelsWide(8));
        assertEquals(373, EnergyWidgetLayout.pixelsHigh("tirePressureWidget", 3));
    }

    @Test
    public void energyWidthsReserveSpaceForLegibleNumbers() {
        Map<String, Object> saved = new HashMap<>();
        SharedPreferences prefs = prefs(saved);
        for (int columns : new int[] {3}) {
            TileSizeStore.setWidth(prefs, "energyWidget", columns);
            assertEquals(4, saved.get("tileHeight_energyWidget"));
            assertArrayEquals(
                    new int[] {columns, 4},
                    TileSizeStore.dimensions(prefs(saved), "energyWidget", 8, 3));
            TileSizeStore.setHeight(prefs, "energyWidget", 3);
            assertEquals(4, TileSizeStore.height(prefs(saved), "energyWidget", 3));
            for (int wide = 4; wide <= 8; wide++) {
                TileSizeStore.setWidth(prefs, "energyWidget", wide);
                assertArrayEquals(
                        new int[] {wide, wide <= 6 ? 5 : 4},
                        TileSizeStore.dimensions(prefs(saved), "energyWidget", 8, 3));
                assertEquals(wide <= 6 ? 5 : 4, saved.get("tileHeight_energyWidget"));
            }
        }
        assertEquals(627, EnergyWidgetLayout.pixelsHigh("energyWidget", 5));
    }

    @Test
    public void staleEnergyHeightIsNormalizedFromStoredWidth() {
        Map<String, Object> saved = new HashMap<>();
        saved.put("tileWidth_energyWidget", 2);
        saved.put("tileHeight_energyWidget", 3);
        assertArrayEquals(
                new int[] {3, 4}, TileSizeStore.dimensions(prefs(saved), "energyWidget", 8, 3));
        saved.put("tileWidth_energyWidget", 3);
        saved.put("tileHeight_energyWidget", 1);
        assertArrayEquals(
                new int[] {3, 4}, TileSizeStore.dimensions(prefs(saved), "energyWidget", 8, 3));
        saved.put("tileWidth_energyWidget", 6);
        saved.put("tileHeight_energyWidget", 5);
        assertArrayEquals(
                new int[] {6, 5}, TileSizeStore.dimensions(prefs(saved), "energyWidget", 8, 3));
        saved.remove("tileWidth_energyWidget");
        assertArrayEquals(
                new int[] {8, 4}, TileSizeStore.dimensions(prefs(saved), "energyWidget", 8, 3));
    }

    @Test
    public void bothThreeColumnEnergyHeightsAndAllFiveShortChartSizesPersist() {
        Map<String, Object> saved = new HashMap<>();
        SharedPreferences prefs = prefs(saved);
        TileSizeStore.setWidth(prefs, "energyWidget", 3);
        for (int rows : new int[] {4, 5}) {
            TileSizeStore.setHeight(prefs, "energyWidget", rows);
            assertArrayEquals(
                    new int[] {3, rows},
                    TileSizeStore.dimensions(prefs(saved), "energyWidget", 8, 4));
        }
        for (int[] size : new int[][] {{1, 1}, {2, 1}, {2, 2}, {3, 1}, {3, 2}}) {
            int columns = size[0], rows = size[1];
            TileSizeStore.setWidth(prefs, "energyConsumptionWidget", columns);
            TileSizeStore.setHeight(prefs, "energyConsumptionWidget", rows);
            assertArrayEquals(
                    new int[] {columns, rows},
                    TileSizeStore.dimensions(prefs(saved), "energyConsumptionWidget", 2, 2));
            assertArrayEquals(
                    new int[] {3, 5}, TileSizeStore.dimensions(prefs(saved), "energyWidget", 8, 4));
        }
    }

    @Test
    public void narrowEnergyRulesDoNotChangeTireOrOdometerSizes() {
        Map<String, Object> saved = new HashMap<>();
        SharedPreferences prefs = prefs(saved);
        TileSizeStore.setWidth(prefs, "tirePressureWidget", 2);
        TileSizeStore.setHeight(prefs, "tirePressureWidget", 4);
        TileSizeStore.setWidth(prefs, "odometerWidget", 2);
        assertArrayEquals(
                new int[] {2, 4},
                TileSizeStore.dimensions(prefs(saved), "tirePressureWidget", 4, 4));
        assertArrayEquals(
                new int[] {2, 1}, TileSizeStore.dimensions(prefs(saved), "odometerWidget", 4, 1));
    }

    @Test
    public void narrowTripSupportsFourAndFiveRows() {
        Map<String, Object> saved = new HashMap<>();
        SharedPreferences prefs = prefs(saved);
        for (int columns : new int[] {3}) {
            TileSizeStore.setWidth(prefs, "energyTripWidget", columns);
            assertArrayEquals(
                    new int[] {columns, 4},
                    TileSizeStore.dimensions(prefs(saved), "energyTripWidget", 8, 2));
            TileSizeStore.setHeight(prefs, "energyTripWidget", 5);
            TileSizeStore.setWidth(prefs, "energyTripWidget", columns == 2 ? 3 : 2);
            assertArrayEquals(
                    new int[] {3, 5},
                    TileSizeStore.dimensions(prefs(saved), "energyTripWidget", 8, 2));
            TileSizeStore.setHeight(prefs, "energyTripWidget", 3);
            assertEquals(4, TileSizeStore.height(prefs(saved), "energyTripWidget", 2));
            TileSizeStore.setHeight(prefs, "energyTripWidget", 8);
            assertEquals(5, TileSizeStore.height(prefs(saved), "energyTripWidget", 2));
            TileSizeStore.setWidth(prefs, "energyTripWidget", 6);
            assertArrayEquals(
                    new int[] {6, 5},
                    TileSizeStore.dimensions(prefs(saved), "energyTripWidget", 8, 2));
        }
    }

    @Test
    public void storedTripHeightIsConstrainedByWidthWithoutLosingValidFiveRows() {
        Map<String, Object> saved = new HashMap<>();
        saved.put("tileWidth_energyTripWidget", 2);
        saved.put("tileHeight_energyTripWidget", 2);
        assertArrayEquals(
                new int[] {3, 4}, TileSizeStore.dimensions(prefs(saved), "energyTripWidget", 8, 2));
        saved.put("tileHeight_energyTripWidget", 5);
        assertArrayEquals(
                new int[] {3, 5}, TileSizeStore.dimensions(prefs(saved), "energyTripWidget", 8, 2));
        saved.put("tileWidth_energyTripWidget", 3);
        assertArrayEquals(
                new int[] {3, 5}, TileSizeStore.dimensions(prefs(saved), "energyTripWidget", 8, 2));
        saved.put("tileWidth_energyTripWidget", 8);
        assertArrayEquals(
                new int[] {8, 5}, TileSizeStore.dimensions(prefs(saved), "energyTripWidget", 8, 2));
    }

    @Test
    public void tripDrawingHeightMatchesSelectedRows() {
        Map<String, Object> saved = new HashMap<>();
        SharedPreferences prefs = prefs(saved);
        for (int columns = 3; columns <= 8; columns++) {
            for (int rows : new int[] {4, 5}) {
                TileSizeStore.setWidth(prefs, "energyTripWidget", columns);
                TileSizeStore.setHeight(prefs, "energyTripWidget", rows);
                assertArrayEquals(
                        new int[] {columns, rows},
                        TileSizeStore.dimensions(prefs(saved), "energyTripWidget", 8, 2));
            }
        }
        assertEquals(245, EnergyWidgetLayout.pixelsHigh("energyTripWidget", 2));
        assertEquals(500, EnergyWidgetLayout.pixelsHigh("energyTripWidget", 4));
        assertEquals(627, EnergyWidgetLayout.pixelsHigh("energyTripWidget", 5));
        assertTrue(EnergyWidgetLayout.vertical("energyTripWidget", 2));
        assertTrue(EnergyWidgetLayout.vertical("energyTripWidget", 3));
        assertFalse(EnergyWidgetLayout.vertical("energyTripWidget", 4));
    }

    @Test
    public void capacitiesDefaultsDecimalsAndInvalidStoredValues() {
        Map<String, Object> saved = new HashMap<>();
        assertEquals(
                43,
                EnergyWidgetPreferences.capacity(
                        prefs(saved), EnergyWidgetSettings.BATTERY_KEY, 43),
                0);
        saved.put(EnergyWidgetSettings.BATTERY_KEY, 50.5f);
        assertEquals(
                50.5,
                EnergyWidgetPreferences.capacity(
                        prefs(saved), EnergyWidgetSettings.BATTERY_KEY, 43),
                0);
        saved.put(EnergyWidgetSettings.BATTERY_KEY, Float.NaN);
        assertEquals(
                43,
                EnergyWidgetPreferences.capacity(
                        prefs(saved), EnergyWidgetSettings.BATTERY_KEY, 43),
                0);
        assertEquals(50.5, EnergyWidgetSettings.parseCapacity("50,5"), 0);
        for (String s : new String[] {"0", "-43", "NaN", "", "2.55"}) {
            assertTrue(Float.isNaN(EnergyWidgetSettings.parseCapacity(s)));
        }
    }

    @Test
    public void scaleMigrationAndDefaultAreDefined() {
        assertEquals(50, EnergyWidgetSettings.window(5));
        assertEquals(100, EnergyWidgetSettings.window(15));
        assertEquals(150, EnergyWidgetSettings.window(30));
        assertEquals(100, EnergyWidgetSettings.window(-1));
        assertEquals(50, EnergyWidgetSettings.window(25));
        assertEquals(100, EnergyWidgetSettings.window(75));
        for (int w : new int[] {50, 100, 150}) {
            assertEquals(w, EnergyWidgetSettings.window(w));
        }
    }

    @Test
    public void odometerAxisCountsBackwardsInWholeKilometersAtEveryScale() {
        for (int window : new int[] {50, 100, 150}) {
            EnergyChartAxis axis = new EnergyChartAxis(180, window, 15417.6f);
            for (int i = 0; i <= 5; i++) {
                assertEquals(15418 - window * i / 5d, axis.tick(i), .001);
            }
            assertEquals(1, axis.position(180), .00001);
            assertEquals(0, axis.position(180 - window), .00001);
            assertEquals(15418 - window / 2d, axis.odometerAt(axis.distanceAt(.5f)), .001);
        }
    }

    @Test
    public void shortHistoryStaysOnRightWithoutInventingOdometer() {
        EnergyChartAxis axis = new EnergyChartAxis(5, 25, 15417);
        assertEquals(.9, axis.position(0), .00001);
        assertEquals(1, axis.position(5), .00001);
        assertEquals(15412, axis.odometerAt(0), .001);
        assertEquals(-45, axis.distanceAt(0), .001);
        for (float unavailable : new float[] {Float.NaN, Float.POSITIVE_INFINITY, 0, -1}) {
            assertTrue(Double.isNaN(new EnergyChartAxis(5, 25, unavailable).tick(0)));
        }
        assertTrue(Double.isNaN(new EnergyChartAxis(0, 25, 10).tick(5)));
    }

    @Test
    public void selectedWindowUsesCumulativeDropsAndActualObservedDistance() {
        float[] x = {0, 50, 100, 150};
        double[] drop = {0, 4, 6, 10}, distance = {0, 50, 100, 150};
        EnergyPeriodEstimate all =
                EnergyPeriodEstimate.calculate(150, x, drop, drop, distance, distance, 43, 56);
        assertEquals(430f / 150, all.battery, .001);
        assertEquals(560f / 150, all.fuel, .001);
        EnergyPeriodEstimate partial =
                EnergyPeriodEstimate.calculate(75, x, drop, drop, distance, distance, 43, 56);
        assertEquals(50, partial.batteryKm, 0);
        assertEquals(4 * 43f / 50, partial.battery, .001);
        // Refill does not subtract an earlier decrease; coalescing a parked point preserves
        // counters.
        double[] withParkedDrop = {0, 4, 6, 11};
        assertEquals(
                5 * 43f / 50,
                EnergyPeriodEstimate.calculate(
                                75, x, withParkedDrop, drop, distance, distance, 43, 56)
                        .battery,
                .001);
        double[] observed = {0, 40, 80, 100};
        assertEquals(
                4 * 56f / 20,
                EnergyPeriodEstimate.calculate(75, x, drop, drop, observed, observed, 43, 56).fuel,
                .001);
    }

    @Test
    public void windowAveragesSubtractRecoveryAndKeepSignedResultsForElectricityOnly() {
        float[] x = {0, 25, 50};
        double[] ev = {0, 1, .5}, fuel = {0, 1, 2}, km = {0, 25, 50};
        EnergyPeriodEstimate all = EnergyPeriodEstimate.calculate(75, x, ev, fuel, km, km, 43, 56);
        assertEquals(.43, all.battery, .00001);
        assertEquals(2.24, all.fuel, .00001);
        EnergyPeriodEstimate recovery =
                EnergyPeriodEstimate.calculate(25, x, ev, fuel, km, km, 43, 56);
        assertEquals(-.86, recovery.battery, .00001);
        assertEquals(2.24, recovery.fuel, .00001);
        assertEquals(-2.15, EnergyWidgetSettings.electricityAverage(-.5, 10, 43), .00001);
        assertTrue(Float.isNaN(EnergyWidgetSettings.electricityAverage(-.5, .5, 43)));
        assertTrue(Float.isNaN(EnergyWidgetSettings.average(-.5, 10, 56)));
    }

    @Test
    public void unavailableShortAndMalformedHistoryDoesNotBecomeZero() {
        float[] x = {0, .5f};
        double[] drops = {0, 1}, km = {0, .5};
        assertTrue(
                Float.isNaN(
                        EnergyPeriodEstimate.calculate(25, x, drops, drops, km, km, 43, 56)
                                .battery));
        assertTrue(
                Float.isNaN(
                        EnergyPeriodEstimate.calculate(25, x, null, drops, km, km, 43, 56).fuel));
        assertTrue(
                Float.isNaN(
                        EnergyPeriodEstimate.calculate(
                                        25, new float[0], drops, drops, km, km, 43, 56)
                                .fuel));
    }

    @Test
    public void consumptionInsideFirstBucketIsNotErasedByParkedUpdates() {
        float[] x = {0, 10};
        double[] drops = {1, 2}, km = {0, 10}, startDrops = {0, 2};
        EnergyPeriodEstimate result =
                EnergyPeriodEstimate.calculate(
                        25, x, drops, drops, km, km, 43, 56, startDrops, startDrops, km, km);
        assertEquals(8.6, result.battery, .001);
        assertEquals(11.2, result.fuel, .001);
    }

    @Test
    public void twentyTwoKmAndTenPercentUseAgreesBetweenTripAndWindowAverages() {
        float trip = EnergyWidgetSettings.electricityAverage(10, 22, 43);
        float[] x = {0, 22};
        double[] ev = {0, 10}, fuel = {0, 0}, km = {0, 22};
        for (int window : EnergyWidgetSettings.WINDOWS) {
            EnergyPeriodEstimate period =
                    EnergyPeriodEstimate.calculate(window, x, ev, fuel, km, km, 43, 56);
            assertEquals(19.54545, period.battery, .0001);
            assertEquals(trip, period.battery, 0);
            assertEquals(22, period.batteryKm, 0);
            assertEquals(0, period.fuel, 0);
        }
    }

    @Test
    public void olderWindowConsumptionCanExceedThisTripWithoutChangingItsAverage() {
        float[] x = {0, 53, 75};
        double[] ev = {0, 45, 55}, fuel = {0, 0, 0}, km = {0, 53, 75};
        EnergyPeriodEstimate lastTrip =
                EnergyPeriodEstimate.calculate(25, x, ev, fuel, km, km, 43, 56);
        EnergyPeriodEstimate fullWindow =
                EnergyPeriodEstimate.calculate(75, x, ev, fuel, km, km, 43, 56);
        assertEquals(19.54545, lastTrip.battery, .0001);
        assertEquals(22, lastTrip.batteryKm, 0);
        assertEquals(31.53333, fullWindow.battery, .0001);
        assertEquals(75, fullWindow.batteryKm, 0);
    }

    @Test
    public void averageUsesTheDistanceObservedForEachSensorRatherThanTripOrWindowLength() {
        float[] x = {0, 22};
        double[] ev = {0, 10}, fuel = {0, 2};
        double[] evKm = {0, 15}, fuelKm = {0, 22};
        EnergyPeriodEstimate period =
                EnergyPeriodEstimate.calculate(25, x, ev, fuel, evKm, fuelKm, 43, 56);
        assertEquals(28.66667, period.battery, .0001);
        assertEquals(15, period.batteryKm, 0);
        assertEquals(5.09091, period.fuel, .0001);
        assertEquals(22, period.fuelKm, 0);
        assertEquals(EnergyWidgetSettings.electricityAverage(10, 15, 43), period.battery, 0);
    }

    @Test
    public void fixedAverageUsesTheLastHundredKmRegardlessOfTheLevelsScale() {
        float[] x = {0, 50, 100, 150};
        double[] ev = {0, 10, 15, 17}, fuel = {0, 1, 2, 4}, km = {0, 50, 100, 150};
        EnergyPeriodEstimate average = EnergyPeriodEstimate.recent(x, ev, fuel, km, km, 43, 56);
        assertEquals(100, average.batteryKm, 0);
        assertEquals(100, average.fuelKm, 0);
        assertEquals(3.01, average.battery, .00001);
        assertEquals(1.68, average.fuel, .00001);
        assertTrue(
                Math.abs(
                                average.battery
                                        - EnergyPeriodEstimate.calculate(
                                                        50, x, ev, fuel, km, km, 43, 56)
                                                .battery)
                        > .1);
        assertTrue(
                Math.abs(
                                average.battery
                                        - EnergyPeriodEstimate.calculate(
                                                        150, x, ev, fuel, km, km, 43, 56)
                                                .battery)
                        > .1);
        float[] partialX = {0, 22};
        double[] partialDrop = {0, 10}, partialKm = {0, 22};
        EnergyPeriodEstimate partial =
                EnergyPeriodEstimate.recent(
                        partialX, partialDrop, partialDrop, partialKm, partialKm, 43, 56);
        assertEquals(22, partial.batteryKm, 0);
        assertEquals(19.54545, partial.battery, .0001);
    }
}
