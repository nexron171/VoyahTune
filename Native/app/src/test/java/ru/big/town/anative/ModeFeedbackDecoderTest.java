package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ModeFeedbackDecoderTest {
    @Test
    public void decodesEverySupportedDriveModeIncludingIndividualAndSnow() {
        assertFeedback(ModeFeedbackDecoder.DRIVE_MODE_VSTATE_ID, 1, "driveMode", "ECO");
        assertFeedback(ModeFeedbackDecoder.DRIVE_MODE_VSTATE_ID, 2, "driveMode", "COMFORT");
        assertFeedback(ModeFeedbackDecoder.DRIVE_MODE_VSTATE_ID, 3, "driveMode", "SPORT");
        assertFeedback(ModeFeedbackDecoder.DRIVE_MODE_VSTATE_ID, 4, "driveMode", "OUTING");
        assertFeedback(ModeFeedbackDecoder.DRIVE_MODE_VSTATE_ID, 5, "driveMode", "INDIVIDUAL");
        assertFeedback(ModeFeedbackDecoder.DRIVE_MODE_VSTATE_ID, 6, "driveMode", "SNOW");
    }

    @Test
    public void decodesConfirmedEnergyAndRecuperationStates() {
        assertFeedback(ModeFeedbackDecoder.ENERGY_MODE_VSTATE_ID,
                VehicleRestorePolicy.SOC_EV, "energy", "EV");
        assertFeedback(ModeFeedbackDecoder.ENERGY_MODE_VSTATE_ID,
                VehicleRestorePolicy.SOC_REV, "energy", "REV");
        assertFeedback(ModeFeedbackDecoder.ENERGY_MODE_VSTATE_ID,
                VehicleRestorePolicy.SOC_SREV, "energy", "SREV");
        assertFeedback(ModeFeedbackDecoder.RECYCLE_MODE_VSTATE_ID,
                VehicleRestorePolicy.REGEN_LOW, "recycle", "LOW");
        assertFeedback(ModeFeedbackDecoder.RECYCLE_MODE_VSTATE_ID,
                VehicleRestorePolicy.REGEN_MEDIUM, "recycle", "MEDIUM");
        assertFeedback(ModeFeedbackDecoder.RECYCLE_MODE_VSTATE_ID,
                VehicleRestorePolicy.REGEN_HIGH, "recycle", "HIGH");
    }

    @Test
    public void ignoresUnknownIdsAndTransitionalValues() {
        assertNull(ModeFeedbackDecoder.decode(999_999, 1));
        assertNull(ModeFeedbackDecoder.decode(ModeFeedbackDecoder.DRIVE_MODE_VSTATE_ID, 0));
        assertNull(ModeFeedbackDecoder.decode(ModeFeedbackDecoder.ENERGY_MODE_VSTATE_ID, 1));
        assertNull(ModeFeedbackDecoder.decode(ModeFeedbackDecoder.RECYCLE_MODE_VSTATE_ID, 5));
    }

    private static void assertFeedback(int id, int state, String modeKey, String mode) {
        ModeFeedbackDecoder.Feedback feedback = ModeFeedbackDecoder.decode(id, state);
        assertEquals(modeKey, feedback.modeKey);
        assertEquals(mode, feedback.mode);
    }
}
