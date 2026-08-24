package ru.big.town.anative;

/** Android-free lifecycle policy for the read-only Apollo diagnostics bridge. */
final class ApolloTlcPolicy {
    static final int UNKNOWN = -1;

    static final String ERROR_NONE = "";
    static final String ERROR_UNSUPPORTED_LIGHT = "unsupported_light";
    static final String ERROR_PROFILE_UNSUPPORTED = "profile_unsupported";
    static final String ERROR_CAN_DISCONNECTED = "can_disconnected";
    static final String ERROR_STATE_READ_FAILED = "state_read_failed";
    static final String ERROR_CAN_PERMISSION_MISSING = "can_permission_missing";

    /** Stable IDs read by TX57; ordinals are resolved from the installed OEM APK at runtime. */
    enum Signal {
        TSR_SWITCH(277),
        PLC_SWITCH(1135),
        GLA_SWITCH(1149),
        GLA_LIGHT_CHANGE_SWITCH(1150);

        final int id;

        Signal(int id) {
            this.id = id;
        }
    }

    private ApolloTlcPolicy() {
    }

    static boolean binderProfilePinned(boolean fullBuild, boolean schemaCheckComplete,
                                       boolean canBusSchemaMatches,
                                       boolean canBusPermissionGranted) {
        return fullBuild && schemaCheckComplete && canBusSchemaMatches
                && canBusPermissionGranted;
    }

    static boolean verificationResultCurrent(boolean fullBuild, boolean destroyed,
                                             int activeGeneration, int resultGeneration,
                                             boolean binderIdentityMatches) {
        return fullBuild && !destroyed && activeGeneration == resultGeneration
                && binderIdentityMatches;
    }

    static boolean epochCurrent(boolean destroyed, int activeEpoch, int eventEpoch) {
        return !destroyed && activeEpoch == eventEpoch;
    }

    static boolean connectionEventCurrent(boolean destroyed,
                                          int activeEpoch, int eventEpoch,
                                          boolean connectionIdentityMatches) {
        return epochCurrent(destroyed, activeEpoch, eventEpoch)
                && connectionIdentityMatches;
    }
}
