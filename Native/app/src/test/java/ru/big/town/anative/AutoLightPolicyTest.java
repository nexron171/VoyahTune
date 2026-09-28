package ru.big.town.anative;

import org.junit.Test;

import static org.junit.Assert.*;
import static ru.big.town.anative.HeadlightCanPolicy.Command.*;

public class AutoLightPolicyTest {
    @Test public void extendedTableWithHighBeamEnabled() {
        HeadlightCanPolicy.Command[] expected = {
                OUT_LAMP_OFF, OUT_LAMP_OFF, AUTO_LAMP_SWITCH, LOW_BEAM,
                AUTO_LAMP_SWITCH, LOW_BEAM, LOW_BEAM, LOW_BEAM};
        for (int reason = 0; reason < expected.length; reason++) {
            assertEquals("reason=" + reason, expected[reason], AutoLightPolicy.target(reason, true, 1));
        }
    }

    @Test public void disabledOrUnknownHighBeamNeverSelectsAuto() {
        for (int ihbc : new int[]{2, -1, 0, 10}) {
            for (int reason = 0; reason <= 7; reason++) {
                assertEquals("reason=" + reason + " ihbc=" + ihbc,
                        reason <= 1 ? OUT_LAMP_OFF : LOW_BEAM,
                        AutoLightPolicy.target(reason, true, ihbc));
            }
        }
    }

    @Test public void legacyTableIsIndependentOfHighBeam() {
        for (int ihbc : new int[]{-1, 1, 2, 10}) {
            for (int reason = -1; reason <= 8; reason++) {
                assertEquals(reason == 0 ? OUT_LAMP_OFF
                                : reason >= 2 && reason <= 4 ? LOW_BEAM : null,
                        AutoLightPolicy.target(reason, false, ihbc));
            }
        }
    }

    @Test public void invalidReasonDoesNotMeanDay() {
        for (int reason : new int[]{-1, 8, 255}) {
            assertNull(AutoLightPolicy.target(reason, true, 1));
        }
    }

    @Test public void switchChangesTargetWithoutAnotherReasonEvent() {
        assertEquals(LOW_BEAM, AutoLightPolicy.target(2, false, 1));
        assertEquals(AUTO_LAMP_SWITCH, AutoLightPolicy.target(2, true, 1));
        assertEquals(LOW_BEAM, AutoLightPolicy.target(2, true, 2));
        assertNull(AutoLightPolicy.target(1, false, 1));
        assertEquals(OUT_LAMP_OFF, AutoLightPolicy.target(1, true, 1));
    }

    @Test public void automaticAutoTargetMustNotTriggerLowBeamRecovery() {
        assertFalse(AutoLightPolicy.shouldRestoreLowBeam(AUTO_LAMP_SWITCH));
        assertFalse(AutoLightPolicy.shouldRestoreLowBeam(OUT_LAMP_OFF));
        assertTrue(AutoLightPolicy.shouldRestoreLowBeam(LOW_BEAM));
    }
}
