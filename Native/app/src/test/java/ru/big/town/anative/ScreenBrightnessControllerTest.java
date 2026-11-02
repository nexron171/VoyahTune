package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

import ru.big.town.common.ScreenBrightnessSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ScreenBrightnessControllerTest {
    private final List<ScreenBrightnessSettings.Rule> defaults =
            ScreenBrightnessSettings.decode(ScreenBrightnessSettings.DEFAULT_RULES);

    @Test
    public void exampleCoversEverySensorLevelAndHonorsBoundaries() {
        int[] targets = {20, 20, 50, 80, 80, 100, 100, 100};
        for (int level = 0; level <= 7; level++) {
            assertEquals(
                    targets[level], ScreenBrightnessSettings.matchingRule(defaults, level).percent);
        }
        assertNull(ScreenBrightnessSettings.matchingRule(defaults, -1));
        assertNull(ScreenBrightnessSettings.matchingRule(defaults, 8));
    }

    @Test
    public void firstMatchingRuleWinsAndOrderSurvivesRoundTrip() {
        List<ScreenBrightnessSettings.Rule> rules =
                ScreenBrightnessSettings.decode("first,ge,2,40\nsecond,ge,5,90\nlast,lt,2,10");
        assertEquals(40, ScreenBrightnessSettings.matchingRule(rules, 6).percent);
        assertEquals(
                "first,ge,2,40\nsecond,ge,5,90\nlast,lt,2,10",
                ScreenBrightnessSettings.encode(rules));
    }

    @Test
    public void supportsStrictAndInclusiveComparisons() {
        String[] operators = {"gt", "ge", "lt", "le", "eq"};
        boolean[][] expected = {
            {false, false, true},
            {false, true, true},
            {true, false, false},
            {true, true, false},
            {false, true, false}
        };
        for (int index = 0; index < operators.length; index++) {
            ScreenBrightnessSettings.Rule rule =
                    new ScreenBrightnessSettings.Rule("rule", operators[index], 3, 75);
            for (int level = 2; level <= 4; level++) {
                assertEquals(expected[index][level - 2], rule.matches(level));
            }
        }
    }

    @Test
    public void doesNotLimitNumberOfRules() {
        List<ScreenBrightnessSettings.Rule> rules = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            rules.add(new ScreenBrightnessSettings.Rule("rule" + index, "ge", 5, 50));
        }
        assertEquals(
                500,
                ScreenBrightnessSettings.decode(ScreenBrightnessSettings.encode(rules)).size());
    }

    @Test
    public void rejectsCorruptConfigurationInsteadOfPartiallyApplyingIt() {
        for (String invalid :
                Arrays.asList(
                        "r,ge,8,20",
                        "r,ge,-1,20",
                        "r,ge,2,0",
                        "r,ge,2,101",
                        "r,unknown,2,20",
                        "r,ge,2,20\nr,lt,2,10",
                        "r,ge,2,20\n")) {
            assertThrows(
                    IllegalArgumentException.class, () -> ScreenBrightnessSettings.decode(invalid));
        }
    }

    @Test
    public void synchronizesToPrimaryBeforeApplyingSensorAndAvoidsDuplicateWrites()
            throws Exception {
        FakeScreens screens = new FakeScreens();
        ScreenBrightnessController controller = new ScreenBrightnessController();
        assertEquals(60, controller.reconcile(screens, defaults, 7, () -> true).target);
        assertEquals(Arrays.asList("2=60", "16=60"), screens.writes);
        ScreenBrightnessController.Result result =
                controller.reconcile(screens, defaults, 7, () -> true);
        assertEquals(100, result.target);
        assertEquals(3, result.confirmed);
        screens.writes.clear();
        controller.reconcile(screens, defaults, 7, () -> true);
        assertTrue(screens.writes.isEmpty());
    }

    @Test
    public void unavailableOrUncoveredSensorRetainsLastTarget() throws Exception {
        FakeScreens screens = new FakeScreens();
        ScreenBrightnessController controller = new ScreenBrightnessController();
        controller.reconcile(screens, defaults, -1, () -> true);
        assertEquals(60, controller.reconcile(screens, defaults, -1, () -> true).target);
        assertEquals(60, controller.reconcile(screens, new ArrayList<>(), 7, () -> true).target);
        assertEquals(20, controller.reconcile(screens, defaults, 1, () -> true).target);
        assertEquals(20, controller.reconcile(screens, defaults, 255, () -> true).target);
    }

    @Test
    public void doesNotClaimSuccessForRejectedInstrumentPanel() throws Exception {
        FakeScreens screens = new FakeScreens();
        screens.rejectCluster = true;
        ScreenBrightnessController.Result result =
                new ScreenBrightnessController().reconcile(screens, defaults, 7, () -> true);
        assertEquals(2, result.confirmed);
        assertEquals(Integer.valueOf(30), screens.values.get(16));
    }

    @Test
    public void disablingBetweenScreensStopsRemainingWrites() throws Exception {
        FakeScreens screens = new FakeScreens();
        new ScreenBrightnessController()
                .reconcile(screens, defaults, 7, () -> screens.writes.isEmpty());
        assertEquals(Arrays.asList("2=60"), screens.writes);
        assertEquals(Integer.valueOf(30), screens.values.get(16));
    }

    @Test
    public void missingInitialPrimaryDoesNotWriteAndRecoversWhenItReturns() throws Exception {
        FakeScreens screens = new FakeScreens();
        screens.values.put(1, -1);
        ScreenBrightnessController controller = new ScreenBrightnessController();
        assertEquals(-1, controller.reconcile(screens, defaults, 7, () -> true).target);
        assertTrue(screens.writes.isEmpty());
        screens.values.put(1, 60);
        assertEquals(60, controller.reconcile(screens, defaults, 7, () -> true).target);
    }

    @Test
    public void sensorRulesUpdateAfterEditingWithoutServiceRestart() throws Exception {
        FakeScreens screens = new FakeScreens();
        ScreenBrightnessController controller = new ScreenBrightnessController();
        controller.reconcile(screens, defaults, 3, () -> true);
        assertEquals(80, controller.reconcile(screens, defaults, 3, () -> true).target);
        assertEquals(
                35,
                controller.reconcile(
                                screens,
                                ScreenBrightnessSettings.decode("edited,ge,3,35"),
                                3,
                                () -> true)
                        .target);
    }

    @Test
    public void nightRoundTripPreservesPercentAndOldRulesRemainDay() {
        List<ScreenBrightnessSettings.Rule> rules =
                ScreenBrightnessSettings.decode("night,lt,2,75,night\nday,ge,2,45");
        assertTrue(rules.get(0).night);
        assertEquals(75, rules.get(0).percent);
        assertFalse(rules.get(1).night);
        assertEquals("night,lt,2,75,night\nday,ge,2,45", ScreenBrightnessSettings.encode(rules));
        assertSame(rules.get(0), ScreenBrightnessSettings.matchingRule(rules, 1));
        assertEquals("Датчик < 2 → 75% · ☾ Ночь", rules.get(0).label());
        assertEquals("Датчик ≥ 2 → 45%", rules.get(1).label());
        assertThrows(
                IllegalArgumentException.class,
                () -> ScreenBrightnessSettings.decode("bad,ge,2,75,unknown"));
    }

    @Test
    public void nightAppliesPercentRetainsTargetDuringOutageAndReturnsToDay() throws Exception {
        FakeScreens screens = new FakeScreens();
        List<ScreenBrightnessSettings.Rule> rules =
                ScreenBrightnessSettings.decode("night,lt,2,75,night\nday,ge,2,45");
        ScreenBrightnessController controller = new ScreenBrightnessController();
        ScreenBrightnessController.Result night =
                controller.reconcile(screens, rules, 1, () -> true);
        assertEquals(75, night.target);
        assertTrue(night.night);
        assertEquals(3, night.confirmed);
        assertEquals(Arrays.asList("1=75", "2=75", "16=75"), screens.writes);
        assertEquals(Arrays.asList("1=0", "2=0", "16=0"), screens.modeWrites);
        screens.writes.clear();
        screens.modeWrites.clear();
        ScreenBrightnessController.Result unavailable =
                controller.reconcile(screens, rules, -1, () -> true);
        assertEquals(75, unavailable.target);
        assertTrue(unavailable.night);
        ScreenBrightnessController.Result uncovered =
                controller.reconcile(screens, new ArrayList<>(), 5, () -> true);
        assertEquals(75, uncovered.target);
        assertFalse(uncovered.night);
        assertTrue(screens.writes.isEmpty());
        assertEquals(Arrays.asList("1=1", "2=1", "16=1"), screens.modeWrites);
        ScreenBrightnessController.Result day = controller.reconcile(screens, rules, 3, () -> true);
        assertEquals(45, day.target);
        assertFalse(day.night);
        assertEquals(3, day.confirmed);
        assertEquals(Arrays.asList("1=1", "2=1", "16=1"), screens.modeWrites);
        assertEquals(Arrays.asList("1=45", "2=45", "16=45"), screens.writes);
    }

    @Test
    public void editedNightPercentIsAppliedWithoutChangingMode() throws Exception {
        FakeScreens screens = new FakeScreens();
        ScreenBrightnessController controller = new ScreenBrightnessController();
        controller.reconcile(
                screens, ScreenBrightnessSettings.decode("night,ge,0,75,night"), 1, () -> true);
        screens.writes.clear();
        screens.modeWrites.clear();

        ScreenBrightnessController.Result result =
                controller.reconcile(
                        screens,
                        ScreenBrightnessSettings.decode("night,ge,0,30,night"),
                        1,
                        () -> true);

        assertEquals(30, result.target);
        assertTrue(result.night);
        assertEquals(3, result.confirmed);
        assertTrue(screens.modeWrites.isEmpty());
        assertEquals(Arrays.asList("1=30", "2=30", "16=30"), screens.writes);
    }

    @Test
    public void equalPercentDoesNotHideDayNightTransition() throws Exception {
        FakeScreens screens = new FakeScreens();
        List<ScreenBrightnessSettings.Rule> rules =
                ScreenBrightnessSettings.decode("night,lt,2,60,night\nday,ge,2,60");
        ScreenBrightnessController controller = new ScreenBrightnessController();
        controller.reconcile(screens, rules, 3, () -> true);
        screens.writes.clear();

        assertTrue(controller.reconcile(screens, rules, 1, () -> true).night);
        assertFalse(controller.reconcile(screens, rules, 3, () -> true).night);
        assertEquals(Arrays.asList("1=0", "2=0", "16=0", "1=1", "2=1", "16=1"), screens.modeWrites);
        assertTrue(screens.writes.isEmpty());
    }

    @Test
    public void nightRequiresPercentReadbackBeforeConfirmingScreen() throws Exception {
        FakeScreens screens = new FakeScreens();
        screens.rejectCluster = true;
        ScreenBrightnessController.Result result =
                new ScreenBrightnessController()
                        .reconcile(
                                screens,
                                ScreenBrightnessSettings.decode("night,ge,0,75,night"),
                                1,
                                () -> true);

        assertTrue(result.night);
        assertEquals(2, result.confirmed);
        assertEquals(Integer.valueOf(0), screens.modes.get(16));
        assertEquals(Integer.valueOf(30), screens.values.get(16));
    }

    @Test
    public void enablingAlsoEnablesOemSynchronizationWithoutRepeatedWrites() throws Exception {
        FakeScreens screens = new FakeScreens();
        ScreenBrightnessController controller = new ScreenBrightnessController();

        assertTrue(controller.reconcile(screens, defaults, 7, () -> true).synchronizationConfirmed);
        assertTrue(screens.synchronizationEnabled);
        assertEquals(1, screens.synchronizationWrites);
        assertTrue(controller.reconcile(screens, defaults, 7, () -> true).synchronizationConfirmed);
        assertEquals(1, screens.synchronizationWrites);
    }

    @Test
    public void synchronizationFailureDoesNotBlockScreensAndIsRetried() throws Exception {
        FakeScreens screens = new FakeScreens();
        screens.rejectSynchronization = true;
        ScreenBrightnessController controller = new ScreenBrightnessController();

        ScreenBrightnessController.Result rejected =
                controller.reconcile(screens, defaults, 7, () -> true);
        assertFalse(rejected.synchronizationConfirmed);
        assertEquals(3, rejected.confirmed);

        screens.rejectSynchronization = false;
        assertTrue(controller.reconcile(screens, defaults, 7, () -> true).synchronizationConfirmed);
        assertEquals(2, screens.synchronizationWrites);
    }

    @Test
    public void unavailableSynchronizationDoesNotBlockScreens() throws Exception {
        FakeScreens screens = new FakeScreens();
        screens.synchronizationUnavailable = true;

        ScreenBrightnessController.Result result =
                new ScreenBrightnessController().reconcile(screens, defaults, 7, () -> true);

        assertFalse(result.synchronizationConfirmed);
        assertEquals(3, result.confirmed);
        assertEquals(0, screens.synchronizationWrites);
    }

    @Test
    public void disabledControllerDoesNotEnableSynchronizationOrWriteScreens() throws Exception {
        FakeScreens screens = new FakeScreens();
        new ScreenBrightnessController().reconcile(screens, defaults, 7, () -> false);

        assertEquals(0, screens.synchronizationWrites);
        assertTrue(screens.writes.isEmpty());
        assertTrue(screens.modeWrites.isEmpty());
    }

    @Test
    public void disablingDuringSynchronizationStopsScreenWrites() throws Exception {
        FakeScreens screens = new FakeScreens();
        new ScreenBrightnessController()
                .reconcile(screens, defaults, 7, () -> screens.synchronizationWrites == 0);

        assertEquals(1, screens.synchronizationWrites);
        assertTrue(screens.writes.isEmpty());
        assertTrue(screens.modeWrites.isEmpty());
    }

    @Test
    public void rejectedModeDoesNotWritePercentOrClaimConfirmation() throws Exception {
        FakeScreens screens = new FakeScreens();
        screens.rejectModes = true;
        ScreenBrightnessController controller = new ScreenBrightnessController();
        ScreenBrightnessController.Result result =
                controller.reconcile(
                        screens,
                        ScreenBrightnessSettings.decode("night,ge,0,75,night"),
                        1,
                        () -> true);
        assertEquals(0, result.confirmed);
        assertTrue(screens.writes.isEmpty());
        screens.modes.put(1, 0);
        screens.modes.put(2, 0);
        screens.modes.put(16, 0);
        result = controller.reconcile(screens, defaults, 3, () -> true);
        assertEquals(0, result.confirmed);
        assertTrue(screens.writes.isEmpty());
    }

    @Test
    public void manualSelectionBlocksSensorWritesUntilAutoResumesEnabledRules() throws Exception {
        FakeScreens screens = new FakeScreens();
        ScreenBrightnessController controller = new ScreenBrightnessController();
        controller.reconcile(screens, defaults, 7, () -> true);
        screens.writes.clear();
        screens.modeWrites.clear();
        for (int manual : new int[] {0, 1}) {
            ScreenBrightnessModeController.apply(screens, manual, () -> true);
            screens.modeWrites.clear();
            controller.reconcile(screens, defaults, 0,
                    () -> ru.big.town.common.BrightnessWidgetProtocol.rulesActive(true, manual));
            assertTrue(screens.writes.isEmpty());
            assertTrue(screens.modeWrites.isEmpty());
            assertEquals(Integer.valueOf(manual), screens.modes.get(16));
        }
        ScreenBrightnessController.Result resumed = controller.reconcile(screens, defaults, 0,
                () -> ru.big.town.common.BrightnessWidgetProtocol.rulesActive(true, 2));
        assertEquals(20, resumed.target);
        assertEquals(3, resumed.confirmed);
        assertEquals(Arrays.asList("1=20", "2=20", "16=20"), screens.writes);
    }

    @Test
    public void independentNightThresholdAppliesWithoutMatchingBrightnessRule() throws Exception {
        FakeScreens screens = new FakeScreens();
        ScreenBrightnessController controller = new ScreenBrightnessController();
        ScreenBrightnessController.Result night = controller.reconcile(screens,
                ScreenBrightnessSettings.decode("only,eq,7,80"), 3, 3, () -> true);
        assertTrue(night.night);
        assertEquals(60, night.target);
        assertEquals(Integer.valueOf(0), screens.modes.get(1));
        assertEquals(Integer.valueOf(0), screens.modes.get(16));
        ScreenBrightnessController.Result day = controller.reconcile(screens,
                ScreenBrightnessSettings.decode("only,eq,7,80"), 4, 3, () -> true);
        assertFalse(day.night);
        assertEquals(60, day.target);
        assertEquals(Integer.valueOf(1), screens.modes.get(1));
        assertEquals(Integer.valueOf(1), screens.modes.get(16));
    }

    private static final class FakeScreens implements ScreenBrightnessController.Screens {
        final Map<Integer, Integer> values = new HashMap<>();
        final List<String> writes = new ArrayList<>();
        final Map<Integer, Integer> modes = new HashMap<>();
        final List<String> modeWrites = new ArrayList<>();
        boolean rejectModes;
        boolean rejectCluster;
        boolean synchronizationEnabled;
        boolean rejectSynchronization;
        boolean synchronizationUnavailable;
        int synchronizationWrites;

        FakeScreens() {
            for (int screen : new int[] {1, 2, 16}) modes.put(screen, 1);
            values.put(1, 60);
            values.put(2, 40);
            values.put(16, 30);
        }

        @Override
        public boolean isSynchronizationEnabled() {
            if (synchronizationUnavailable) {
                throw new IllegalStateException("OEM settings unavailable");
            }
            return synchronizationEnabled;
        }

        @Override
        public void enableSynchronization() {
            synchronizationWrites++;
            if (!rejectSynchronization) synchronizationEnabled = true;
        }

        @Override
        public int readMode(int screen) {
            return modes.get(screen);
        }

        @Override
        public boolean writeMode(int screen, int mode) {
            modeWrites.add(screen + "=" + mode);
            if (!rejectModes) modes.put(screen, mode);
            return !rejectModes;
        }

        @Override
        public int read(int screen) {
            return values.get(screen);
        }

        @Override
        public boolean write(int screen, int percent) {
            writes.add(screen + "=" + percent);
            if (screen == 16 && rejectCluster) return false;
            values.put(screen, percent);
            return true;
        }
    }
}
