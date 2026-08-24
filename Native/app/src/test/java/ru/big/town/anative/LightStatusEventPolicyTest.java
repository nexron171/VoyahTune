package ru.big.town.anative;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LightStatusEventPolicyTest {
    private static final long GUARD_MS = 2_500L;

    @Test
    public void bufferedStatusPredatingDecisionCannotCancelIt() {
        assertEquals(LightStatusEventPolicy.Decision.IGNORE,
                LightStatusEventPolicy.classifyExternalOff(
                        0, 0, 900L, 1_000L, 0L, false, false, GUARD_MS));
    }

    @Test
    public void delayedDeliveryUsesEventTimeForCommandEcho() {
        assertEquals(LightStatusEventPolicy.Decision.IGNORE,
                LightStatusEventPolicy.classifyExternalOff(
                        0, 0, 1_200L, 900L, 1_000L, false, false, GUARD_MS));
    }

    @Test
    public void laterOffOutsideCommandGuardIsExternal() {
        assertEquals(LightStatusEventPolicy.Decision.CONFIRM,
                LightStatusEventPolicy.classifyExternalOff(
                        0, 0, 4_000L, 900L, 1_000L, false, false, GUARD_MS));
    }

    @Test
    public void postDecisionOffBeforeAnyFrameCanCancelPendingWork() {
        assertEquals(LightStatusEventPolicy.Decision.CONFIRM,
                LightStatusEventPolicy.classifyExternalOff(
                        0, 0, 1_100L, 1_000L, 0L, false, false, GUARD_MS));
    }

    @Test
    public void bufferedOffCapturedBeforeFrameIsNotThatFramesEcho() {
        assertEquals(LightStatusEventPolicy.Decision.CONFIRM,
                LightStatusEventPolicy.classifyExternalOff(
                        0, 0, 1_100L, 1_000L, 1_200L, true, false, GUARD_MS));
    }

    @Test
    public void bufferedWakeResetInsideProtectedPreFrameIntervalIsIgnored() {
        assertEquals(LightStatusEventPolicy.Decision.IGNORE,
                LightStatusEventPolicy.classifyExternalOff(
                        0, 0, 1_100L, 1_000L, 1_200L, true, true, GUARD_MS));
    }

    @Test
    public void wakeResetBeforeFirstFrameCannotCancelQueuedReassert() {
        assertEquals(LightStatusEventPolicy.Decision.IGNORE,
                LightStatusEventPolicy.classifyExternalOff(
                        0, 0, 1_100L, 1_000L, 0L, false, true, GUARD_MS));
    }

    @Test
    public void offAfterLowBeamAttemptInsideGuardNeedsFiniteAdjudication() {
        assertEquals(LightStatusEventPolicy.Decision.DEFER,
                LightStatusEventPolicy.classifyExternalOff(
                        0, 0, 1_100L, 900L, 1_000L, true, false, GUARD_MS));
    }

    @Test
    public void nonOffStatusNeverCreatesManualFence() {
        assertEquals(LightStatusEventPolicy.Decision.IGNORE,
                LightStatusEventPolicy.classifyExternalOff(
                        1, 0, 4_000L, 900L, 1_000L, false, false, GUARD_MS));
        assertEquals(LightStatusEventPolicy.Decision.IGNORE,
                LightStatusEventPolicy.classifyExternalOff(
                        0, 1, 4_000L, 900L, 1_000L, false, false, GUARD_MS));
    }
}
