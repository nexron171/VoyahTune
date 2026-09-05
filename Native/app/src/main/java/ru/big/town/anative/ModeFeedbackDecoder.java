package ru.big.town.anative;

/** Android-free mapping from stable H97C VehicleState IDs/values to VoyahTune mode keys. */
final class ModeFeedbackDecoder {
    // DRIVING_MODE_SET distinguishes Individual=5 and Snow=6. DRIVING_MODE_SET_FB (787) is only
    // the steering/chassis parameter, so an Individual profile may look indistinguishable from Sport.
    static final int DRIVE_MODE_VSTATE_ID = 545;
    static final int ENERGY_MODE_VSTATE_ID = VehicleRestorePolicy.SOC_MODE_ID;
    static final int RECYCLE_MODE_VSTATE_ID = VehicleRestorePolicy.REGEN_LEVEL_ID;

    static final class Feedback {
        final String modeKey;
        final String mode;

        Feedback(String modeKey, String mode) {
            this.modeKey = modeKey;
            this.mode = mode;
        }
    }

    private ModeFeedbackDecoder() {
    }

    /** Unknown and transitional states are intentionally not guessed or persisted. */
    static Feedback decode(int id, int state) {
        if (id == DRIVE_MODE_VSTATE_ID) {
            switch (state) {
                case 1: return new Feedback("driveMode", "ECO");
                case 2: return new Feedback("driveMode", "COMFORT");
                case 3: return new Feedback("driveMode", "SPORT");
                case 4: return new Feedback("driveMode", "OUTING");
                case 5: return new Feedback("driveMode", "INDIVIDUAL");
                case 6: return new Feedback("driveMode", "SNOW");
                default: return null;
            }
        }
        if (id == ENERGY_MODE_VSTATE_ID) {
            switch (state) {
                // SMART=1 is intentionally omitted until confirmed on the target trim/firmware.
                case VehicleRestorePolicy.SOC_EV: return new Feedback("energy", "EV");
                case VehicleRestorePolicy.SOC_REV: return new Feedback("energy", "REV");
                case VehicleRestorePolicy.SOC_SREV: return new Feedback("energy", "SREV");
                default: return null;
            }
        }
        if (id == RECYCLE_MODE_VSTATE_ID) {
            switch (state) {
                case VehicleRestorePolicy.REGEN_LOW: return new Feedback("recycle", "LOW");
                case VehicleRestorePolicy.REGEN_MEDIUM: return new Feedback("recycle", "MEDIUM");
                case VehicleRestorePolicy.REGEN_HIGH: return new Feedback("recycle", "HIGH");
                default: return null;
            }
        }
        return null;
    }
}
