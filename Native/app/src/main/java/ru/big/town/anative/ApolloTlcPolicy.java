package ru.big.town.anative;

/**
 * Parcel-independent safety policy for the Apollo PLC/TLC bridge.
 *
 * <p>The OEM UI calls the user-facing triggered lane-change switch {@code PLC_SWITCH}.
 * {@code TLC_FUNC_ENABLE} and {@code PLC_FUNC_ENABLE_SA} are subscription capabilities and
 * are deliberately read-only here. Keeping this class free of Android types makes the pinned
 * profile and every write gate unit-testable on a host JVM.</p>
 */
final class ApolloTlcPolicy {
    static final int UNKNOWN = -1;
    static final int MODULE_OFF = 1;
    static final int MODULE_ON = 2;
    static final int PLC_STATUS_ERROR = 7;
    static final int GEAR_PARKING = 0;
    static final int PROFILE_MODE_UNSUPPORTED = 0;
    static final int PROFILE_MODE_STOCK_97C = 1;
    static final int PROFILE_MODE_DIRECT_H97X = 2;
    static final long PROFILE_HEARTBEAT_MAX_AGE_MS = 90_000L;

    static final String ERROR_NONE = "";
    static final String ERROR_UNSUPPORTED_LIGHT = "unsupported_light";
    static final String ERROR_PROFILE_UNSUPPORTED = "profile_unsupported";
    static final String ERROR_CAN_DISCONNECTED = "can_disconnected";
    static final String ERROR_MASTER_DISABLED = "master_disabled";
    static final String ERROR_WRITE_PENDING = "write_pending";
    static final String ERROR_STATE_READ_FAILED = "state_read_failed";
    static final String ERROR_WRITE_PERMISSION_MISSING = "write_permission_missing";
    static final String ERROR_NOT_PARKING = "gear_not_parking";
    static final String ERROR_INVALID_PLC_SWITCH = "invalid_plc_switch";
    static final String ERROR_INVALID_SWITCH_STATE = "invalid_switch_state";
    static final String ERROR_INVALID_PLC_STATUS = "invalid_plc_status";
    static final String ERROR_PLC_STATUS = "plc_status_error";
    static final String ERROR_INVALID_ANP_SWITCH = "invalid_anp_switch";
    static final String ERROR_INVALID_TLC_CAPABILITY = "invalid_tlc_capability";
    static final String ERROR_INVALID_PLC_CAPABILITY = "invalid_plc_capability_sa";
    static final String ERROR_CAPABILITY_DISABLED = "capability_disabled";
    static final String ERROR_ANP_MUST_BE_OFF = "anp_must_be_off";
    static final String ERROR_MASTER_NOT_USED_DIRECT = "master_not_used_direct_h97x";

    /** Ordinal and stable value from the allow-listed VehicleSetting.apk VehicleState enum. */
    enum Signal {
        TSR_SWITCH(208, 277),
        PLC_FUNCTION_STATUS(904, 1121),
        PLC_SWITCH(918, 1135),
        ANP_SWITCH(919, 1136),
        GLA_SWITCH(932, 1149),
        GLA_LIGHT_CHANGE_SWITCH(933, 1150),
        TLC_FUNC_ENABLE(953, 1170),
        PLC_FUNC_ENABLE_SA(962, 1179);

        final int ordinal;
        final int id;

        Signal(int ordinal, int id) {
            this.ordinal = ordinal;
            this.id = id;
        }

        static Signal fromId(int id) {
            for (Signal signal : values()) {
                if (signal.id == id) return signal;
            }
            return null;
        }
    }

    static final class Snapshot {
        final int plcSwitch;
        final int plcStatus;
        final int anpSwitch;
        final int tlcCapability;
        final int plcCapabilitySa;

        Snapshot(int plcSwitch, int plcStatus, int anpSwitch,
                 int tlcCapability, int plcCapabilitySa) {
            this.plcSwitch = plcSwitch;
            this.plcStatus = plcStatus;
            this.anpSwitch = anpSwitch;
            this.tlcCapability = tlcCapability;
            this.plcCapabilitySa = plcCapabilitySa;
        }
    }

    private ApolloTlcPolicy() {
    }

    static boolean profileSupported(boolean fullBuild, boolean vehicleSettingHashMatches,
                                    boolean canBusServiceHashMatches,
                                    boolean hookProfileSupported, boolean heartbeatFresh,
                                    boolean runtimeProfileValid,
                                    boolean writePermissionGranted) {
        return fullBuild && vehicleSettingHashMatches && canBusServiceHashMatches
                && hookProfileSupported && heartbeatFresh && runtimeProfileValid
                && writePermissionGranted;
    }

    static boolean binderProfilePinned(boolean fullBuild, boolean hashCheckComplete,
                                       boolean vehicleSettingHashMatches,
                                       boolean canBusServiceHashMatches,
                                       boolean writePermissionGranted) {
        return fullBuild && hashCheckComplete
                && vehicleSettingHashMatches && canBusServiceHashMatches
                && writePermissionGranted;
    }

    static boolean heartbeatFresh(long nowElapsed, long heartbeatElapsed) {
        if (heartbeatElapsed <= 0L) return false;
        long age = nowElapsed - heartbeatElapsed;
        return age >= 0L && age <= PROFILE_HEARTBEAT_MAX_AGE_MS;
    }

    static boolean effectiveMaster(boolean fullBuild, boolean profileSupported,
                                   boolean masterKnown, boolean persistedMaster,
                                   boolean masterForceDisabled,
                                   boolean writePermissionGranted) {
        return fullBuild && profileSupported && masterKnown && persistedMaster
                && !masterForceDisabled && writePermissionGranted;
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

    /** Generated AIDL encodes boolean callback registration results as exactly 0 or 1. */
    static boolean callbackRegistrationAccepted(int binderResult) {
        return binderResult == 1;
    }

    static boolean verificationResultCurrent(boolean fullBuild, boolean destroyed,
                                             int activeGeneration, int resultGeneration,
                                             boolean binderIdentityMatches) {
        return fullBuild && !destroyed && activeGeneration == resultGeneration
                && binderIdentityMatches;
    }

    static boolean isModuleState(int state) {
        return state == MODULE_OFF || state == MODULE_ON;
    }

    static boolean isPlcStatus(int state) {
        return state >= 0 && state <= PLC_STATUS_ERROR;
    }

    /** First fail-closed reason for a telemetry snapshot, or an empty string. */
    static String snapshotError(Snapshot snapshot) {
        if (!isModuleState(snapshot.plcSwitch)) return ERROR_INVALID_PLC_SWITCH;
        if (!isPlcStatus(snapshot.plcStatus)) return ERROR_INVALID_PLC_STATUS;
        if (snapshot.plcStatus == PLC_STATUS_ERROR) return ERROR_PLC_STATUS;
        if (!isModuleState(snapshot.anpSwitch)) return ERROR_INVALID_ANP_SWITCH;
        if (!isModuleState(snapshot.tlcCapability)) return ERROR_INVALID_TLC_CAPABILITY;
        if (!isModuleState(snapshot.plcCapabilitySa)) return ERROR_INVALID_PLC_CAPABILITY;
        return ERROR_NONE;
    }

    /** The direct path needs only the current PLC_SWITCH value for state/readback integrity. */
    static String operationalSnapshotError(Snapshot snapshot) {
        if (!isModuleState(snapshot.plcSwitch)) return ERROR_INVALID_PLC_SWITCH;
        return ERROR_NONE;
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

    /**
     * Returns an empty string only when one guarded TX58 for PLC_SWITCH may be emitted.
     * Enabling additionally requires both read-only subscription capabilities to be open.
     */
    static String writeBlockReason(boolean fullBuild, boolean profileSupported,
                                   boolean canConnected, boolean directTlcMode,
                                   boolean masterEnabled, boolean pending, int gear,
                                   Snapshot snapshot, boolean requestedEnabled) {
        if (!fullBuild) return ERROR_UNSUPPORTED_LIGHT;
        if (!profileSupported) return ERROR_PROFILE_UNSUPPORTED;
        if (!canConnected) return ERROR_CAN_DISCONNECTED;
        if (!directTlcMode && !masterEnabled) return ERROR_MASTER_DISABLED;
        if (pending) return ERROR_WRITE_PENDING;
        if (gear != GEAR_PARKING) return ERROR_NOT_PARKING;

        String snapshotError = directTlcMode
                ? operationalSnapshotError(snapshot) : snapshotError(snapshot);
        if (!snapshotError.isEmpty()) return snapshotError;

        if (!directTlcMode && requestedEnabled
                && (snapshot.tlcCapability != MODULE_ON
                || snapshot.plcCapabilitySa != MODULE_ON)) {
            return ERROR_CAPABILITY_DISABLED;
        }
        return ERROR_NONE;
    }

    /** Master ON unlocks TX58, so it uses the same live transport/gear/telemetry gates. */
    static String masterEnableBlockReason(boolean fullBuild, boolean profileSupported,
                                          boolean canReady, boolean refreshSucceeded,
                                          boolean pending, int gear, Snapshot snapshot) {
        if (!fullBuild) return ERROR_UNSUPPORTED_LIGHT;
        if (!profileSupported) return ERROR_PROFILE_UNSUPPORTED;
        if (!canReady) return ERROR_CAN_DISCONNECTED;
        if (!refreshSucceeded) return ERROR_STATE_READ_FAILED;
        if (pending) return ERROR_WRITE_PENDING;
        if (gear != GEAR_PARKING) return ERROR_NOT_PARKING;
        return snapshotError(snapshot);
    }
}
