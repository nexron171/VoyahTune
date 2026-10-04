package ru.big.town.anative;

import org.junit.Test;
import ru.big.town.common.DriveSelectionPolicy;
import static org.junit.Assert.*;
import static ru.big.town.common.DriveSelectionPolicy.*;

public class DriveSelectionPolicyTest {
    private DriveSelectionPolicy initial() {
        return new DriveSelectionPolicy("INDIVIDUAL", "", "COMFORT");
    }
    @Test public void widgetOutingWinsOverPinnedIndividualAtDriveWithRememberOff() {
        DriveSelectionPolicy state = initial().select("OUTING", WIDGET, false);
        assertEquals("INDIVIDUAL", state.configured);
        assertEquals("OUTING", state.effective());
        assertEquals("COMFORT", state.medium);
        // Re-reading stored preferences after a door, D or process restart preserves the override.
        state = new DriveSelectionPolicy(state.configured, state.override, state.medium);
        assertEquals("OUTING", state.effective());
        assertSame(state, state.select("OUTING", FEEDBACK, false));
    }
    @Test public void originFreeFeedbackCannotChangeAnySelection() {
        DriveSelectionPolicy state = initial().select("OUTING", WIDGET, true);
        assertSame(state, state.select("ECO", FEEDBACK, true));
        assertEquals("OUTING", state.nextTrip().effective());
    }
    @Test public void widgetAlsoSavesLastModeWhenRememberOnBeforeFirstDrive() {
        DriveSelectionPolicy state = initial().select("OUTING", WIDGET, true);
        assertEquals("OUTING", state.configured);
        assertEquals("OUTING", state.effective());
        assertEquals("COMFORT", state.medium);
    }
    @Test public void everyOtherSelectionReleasesOverrideWithOrWithoutRemembering() {
        for (boolean remember : new boolean[]{false, true}) {
            for (String source : new String[]{EXPLICIT}) {
                DriveSelectionPolicy state = initial().select("OUTING", WIDGET, remember)
                        .select("SNOW", source, remember);
                assertEquals("", state.override);
                assertEquals("SNOW", state.effective());
                assertEquals(remember ? "SNOW" : "INDIVIDUAL", state.nextTrip().effective());
                assertEquals("SNOW", state.medium);
            }
        }
    }
    @Test public void explicitSelectionOfSameModeAlsoReleasesWidgetOverride() {
        DriveSelectionPolicy state = initial().select("SPORT", WIDGET, false)
                .select("SPORT", EXPLICIT, false);
        assertEquals("SPORT", state.effective());
        assertEquals("INDIVIDUAL", state.nextTrip().effective());
        assertEquals("", state.override);
    }
    @Test public void historySurvivesSportAndOutingAndDoesNotDependOnRememberFlag() {
        for (boolean remember : new boolean[]{false, true}) {
            for (String mode : new String[]{"ECO", "COMFORT", "SNOW", "INDIVIDUAL"}) {
                DriveSelectionPolicy state = initial().select(mode, EXPLICIT, remember)
                        .select("SPORT", WIDGET, remember).select("OUTING", WIDGET, remember);
                assertEquals(mode, state.medium);
                state = state.select(state.medium, WIDGET, remember);
                assertEquals(mode, state.effective());
            }
        }
    }
    @Test public void cabinChoiceThenLowAndMediumRestoresEveryMediumProfile() {
        for (boolean remember : new boolean[]{false, true}) {
            for (String mode : new String[]{"ECO", "COMFORT", "INDIVIDUAL", "SNOW"}) {
                DriveSelectionPolicy state = new DriveSelectionPolicy("ECO", "SPORT", "ECO")
                        .select(mode, EXPLICIT, remember);
                assertEquals("", state.override);
                state = state.select(SuspensionWidgetPolicy.driveMode(1, value(state.medium)), WIDGET, remember);
                assertEquals("SPORT", state.effective());
                state = new DriveSelectionPolicy(state.configured, state.override, state.medium, state.current);
                String target = SuspensionWidgetPolicy.driveMode(2, value(state.medium));
                assertEquals(mode, target);
                state = state.select(target, WIDGET, remember);
                assertEquals(mode, state.nextTrip().effective());
                assertSame(state, state.select("ECO", FEEDBACK, remember));
            }
        }
    }
    @Test public void entryKeepsCurrentModeAndSettingsAlwaysReleaseOverride() {
        DriveSelectionPolicy state = initial().select("SPORT", WIDGET, false);
        state = state.select("SPORT", WIDGET, false); // entry preserves the freshly read mode
        assertEquals("SPORT", state.effective());
        state = state.select("ECO", SETTINGS, false);
        assertEquals("ECO", state.configured);
        assertEquals("ECO", state.effective());
        assertEquals("ECO", state.medium);
    }
    @Test public void invalidFeedbackCannotReleaseOverrideOrCorruptHistory() {
        DriveSelectionPolicy state = initial().select("OUTING", WIDGET, true);
        assertSame(state, state.select(null, FEEDBACK, true));
        assertSame(state, state.select("UNKNOWN", FEEDBACK, true));
        assertSame(state, state.select("SPORT", "invalid-source", true));
        assertEquals("INDIVIDUAL", new DriveSelectionPolicy("INDIVIDUAL", "", null).medium);
        assertEquals("ECO", new DriveSelectionPolicy("SPORT", "", null).medium);
    }
    @Test public void driveFeedbackIsGatedDuringAccRestoreAndAvailableAfterUserCommand() {
        ModeSyncPolicy policy = new ModeSyncPolicy();
        policy.updateRememberLast("driveMode", false);
        long restore = policy.beginRestore();
        assertFalse(policy.canAcceptDriveSelection());
        policy.completeRestore(restore);
        assertTrue(policy.canRememberSelection());
        assertTrue(policy.canAcceptDriveSelection());
        long command = policy.cancelRestore();
        assertFalse(policy.canAcceptDriveSelection());
        policy.completeUserCommand(command);
        assertTrue(policy.canAcceptDriveSelection());
        policy.freeze();
        assertFalse(policy.canAcceptDriveSelection());
    }
}
