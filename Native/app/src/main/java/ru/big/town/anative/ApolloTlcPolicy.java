package ru.big.town.anative;

/**
 * Parcel-independent safety policy for the direct Apollo PLC/TLC bridge.
 *
 * <p>The OEM UI calls the user-facing triggered lane-change switch {@code PLC_SWITCH}.
 * Its entitlement pair is {@code TLC_FUNC_ENABLE}/{@code PLC_FUNC_ENABLE_SA}; the traffic-light
 * pair is {@code GLC_FUNC_ENABLE}/{@code TLA_FUNC_ENABLE_SA}. Keeping this class free of Android
 * types makes the complete composite entitlement vector and active direct write gates
 * unit-testable on a host JVM.</p>
 */
final class ApolloTlcPolicy {
    static final int UNKNOWN = -1;
    static final int MODULE_OFF = 1;
    static final int MODULE_ON = 2;
    static final int GEAR_PARKING = 0;

    static final String ERROR_NONE = "";
    static final String ERROR_UNSUPPORTED_LIGHT = "unsupported_light";
    static final String ERROR_PROFILE_UNSUPPORTED = "profile_unsupported";
    static final String ERROR_CAN_DISCONNECTED = "can_disconnected";
    static final String ERROR_WRITE_PENDING = "write_pending";
    static final String ERROR_STATE_READ_FAILED = "state_read_failed";
    static final String ERROR_WRITE_PERMISSION_MISSING = "write_permission_missing";
    static final String ERROR_NOT_PARKING = "gear_not_parking";
    static final String ERROR_INVALID_PLC_SWITCH = "invalid_plc_switch";
    static final String ERROR_INVALID_SWITCH_STATE = "invalid_switch_state";
    static final String ERROR_ANP_MUST_BE_OFF = "anp_must_be_off";
    static final String ERROR_MASTER_NOT_USED_DIRECT = "master_not_used_direct_h97x";

    /** Stable VehicleState IDs; ordinals are resolved from the installed CanBusService at runtime. */
    enum Signal {
        TSR_SWITCH(277),
        PLC_FUNCTION_STATUS(1121),
        PLC_SWITCH(1135),
        ANP_SWITCH(1136),
        GLA_SWITCH(1149),
        GLA_LIGHT_CHANGE_SWITCH(1150),
        TLC_FUNC_ENABLE(1170),
        PLC_FUNC_ENABLE_SA(1179);

        final int id;

        Signal(int id) {
            this.id = id;
        }

    }

    /**
     * Complete OEM ADAS entitlement vector used by Binder TX77.
     *
     * <p>The H97C/H97X CanBus implementation builds one shared 18-bit command from this vector.
     * Omitting keys would therefore turn omitted bits into zero implicitly. Every key is sent:
     * TLC and PLC follow the TLC switch, while GLC and TLA follow traffic-light recognition.
     * Unrelated capabilities are not activated by this app.</p>
     */
    enum Entitlement {
        RPA_FUNC_ENABLE(1166, Feature.NONE),
        HPP_FUNC_ENABLE(1167, Feature.NONE),
        GLC_FUNC_ENABLE(1168, Feature.TRAFFIC_LIGHT),
        ISLC_FUNC_ENABLE(1169, Feature.NONE),
        TLC_FUNC_ENABLE(1170, Feature.TLC),
        NOA_FUNC_ENABLE(1171, Feature.NONE),
        ELK_FUNC_ENABLE(1172, Feature.NONE),
        ESA_FUNC_ENABLE(1173, Feature.NONE),
        APA_FUNC_ENABLE_SA(1174, Feature.NONE),
        RPA_FUNC_ENABLE_SA(1175, Feature.NONE),
        HAVP_FUNC_ENABLE_SA(1176, Feature.NONE),
        ACC_FUNC_ENABLE_SA(1177, Feature.NONE),
        ICA_FUNC_ENABLE_SA(1178, Feature.NONE),
        PLC_FUNC_ENABLE_SA(1179, Feature.TLC),
        HANP_FUNC_ENABLE_SA(1180, Feature.NONE),
        ISA_FUNC_ENABLE_SA(1181, Feature.NONE),
        ISLC_FUNC_ENABLE_SA(1182, Feature.NONE),
        TLA_FUNC_ENABLE_SA(1183, Feature.TRAFFIC_LIGHT);

        enum Feature {
            NONE,
            TLC,
            TRAFFIC_LIGHT
        }

        final int id;
        final Feature feature;

        Entitlement(int id, Feature feature) {
            this.id = id;
            this.feature = feature;
        }

        int compositeValue(boolean tlcEnabled, boolean trafficLightEnabled) {
            boolean enabled = (feature == Feature.TLC && tlcEnabled)
                    || (feature == Feature.TRAFFIC_LIGHT && trafficLightEnabled);
            return enabled ? MODULE_ON : MODULE_OFF;
        }
    }

    private ApolloTlcPolicy() {
    }

    static boolean binderProfilePinned(boolean fullBuild, boolean schemaCheckComplete,
                                       boolean canBusSchemaMatches,
                                       boolean writePermissionGranted) {
        return fullBuild && schemaCheckComplete && canBusSchemaMatches
                && writePermissionGranted;
    }

    /** Value bit paired with masterKnown; unknown must never look like a confirmed ON or OFF. */
    static boolean reportedMasterEnabled(boolean masterKnown, boolean persistedMaster) {
        return masterKnown && persistedMaster;
    }

    static int requestedPlcState(boolean enabled) {
        return enabled ? MODULE_ON : MODULE_OFF;
    }

    /** TSR uses the inverse OEM encoding: 1=enabled, 2=disabled. */
    static int requestedTsrState(boolean enabled) {
        return enabled ? MODULE_OFF : MODULE_ON;
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

    static boolean writeSessionCurrent(boolean destroyed, boolean pending,
                                       int activeWriteGeneration, int eventWriteGeneration,
                                       int activeBindEpoch, int eventBindEpoch) {
        return !destroyed && pending
                && activeWriteGeneration == eventWriteGeneration
                && activeBindEpoch == eventBindEpoch;
    }

    static boolean isModuleState(int state) {
        return state == MODULE_OFF || state == MODULE_ON;
    }

    static boolean compositeSwitchStatesValid(int plcSwitch, int glaSwitch) {
        return isModuleState(plcSwitch) && isModuleState(glaSwitch);
    }

    static String directTlcStateError(int plcSwitch) {
        return isModuleState(plcSwitch) ? ERROR_NONE : ERROR_INVALID_PLC_SWITCH;
    }

    /** Shared OEM-parity gates for the two traffic-light states; stock UI allows them outside P. */
    static String directSwitchBlockReason(boolean fullBuild, boolean supported,
                                          boolean canConnected, boolean pending,
                                          int currentState) {
        if (!fullBuild) return ERROR_UNSUPPORTED_LIGHT;
        if (!supported) return ERROR_PROFILE_UNSUPPORTED;
        if (!canConnected) return ERROR_CAN_DISCONNECTED;
        if (pending) return ERROR_WRITE_PENDING;
        if (!isModuleState(currentState)) return ERROR_INVALID_SWITCH_STATE;
        return ERROR_NONE;
    }

    /** Returns an empty string only when one direct TX58 for PLC_SWITCH may be emitted. */
    static String directTlcBlockReason(boolean fullBuild, boolean profileSupported,
                                       boolean canConnected, boolean pending,
                                       int gear, int plcSwitch) {
        if (!fullBuild) return ERROR_UNSUPPORTED_LIGHT;
        if (!profileSupported) return ERROR_PROFILE_UNSUPPORTED;
        if (!canConnected) return ERROR_CAN_DISCONNECTED;
        if (pending) return ERROR_WRITE_PENDING;
        if (gear != GEAR_PARKING) return ERROR_NOT_PARKING;
        return directTlcStateError(plcSwitch);
    }
}
