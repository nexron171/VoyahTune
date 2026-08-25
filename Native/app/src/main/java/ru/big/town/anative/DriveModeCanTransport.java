package ru.big.town.anative;

import android.content.Context;
import android.util.Log;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** OEM VehicleState bundle transport for a side-effect-free drive-profile request. */
final class DriveModeCanTransport {
    private static final String TAG = "$$$ DriveModeCanTransport $$$";

    enum DispatchResult {
        /** H97C accepted TX77; its asynchronous ModeSettingTask has not reported CAN completion. */
        ACCEPTED_UNCONFIRMED,
        TRANSIENT_FAILURE;

        boolean accepted() {
            return this == ACCEPTED_UNCONFIRMED;
        }
    }

    private DriveModeCanTransport() {}

    /** Compatibility API for an explicit user command; true means accepted, not CAN-confirmed. */
    static boolean send(Context context, String mode) {
        return dispatch(context, mode).accepted();
    }

    /** Wake-restore compatibility API. */
    static boolean send(String mode) {
        return send(GlobalVars.SAVE_CONTEXT, mode);
    }

    static DispatchResult dispatch(Context context, String mode) {
        Map<OemVehicleStateTransport.StateKey, Integer> states = statesFor(context, mode);
        if (states == null) return DispatchResult.TRANSIENT_FAILURE;

        OemVehicleStateTransport.Result result = OemVehicleStateTransport.sendBundle(
                context, states, "drive mode: " + mode);
        if (result.accepted()) {
            Log.i(TAG, "OEM drive-mode bundle accepted-unconfirmed: " + mode);
            return DispatchResult.ACCEPTED_UNCONFIRMED;
        }
        return DispatchResult.TRANSIENT_FAILURE;
    }

    /** Validated drive-profile fields for merging into one wider OEM TX77 restore bundle. */
    static Map<OemVehicleStateTransport.StateKey, Integer> statesFor(
            Context context, String mode) {
        if (!DriveModeCanPolicy.isSupported(mode)) {
            Log.e(TAG, "Unsupported drive mode: " + mode);
            return null;
        }
        DriveModeCanPolicy.IndividualProfile individual = "INDIVIDUAL".equals(mode)
                ? OemIndividualDriveProfileReader.read(context) : null;
        DriveModeCanPolicy.Plan plan = DriveModeCanPolicy.planFor(mode, individual);
        if (plan == null) {
            Log.e(TAG, "Cannot build safe OEM plan for drive mode " + mode);
            return null;
        }

        Map<OemVehicleStateTransport.StateKey, Integer> states = new LinkedHashMap<>();
        for (Map.Entry<DriveModeCanPolicy.VehicleStateKey, Integer> entry
                : plan.values().entrySet()) {
            DriveModeCanPolicy.VehicleStateKey key = entry.getKey();
            states.put(new OemVehicleStateTransport.StateKey(key.name(), key.stableId),
                    entry.getValue());
        }
        return Collections.unmodifiableMap(states);
    }

    /** Appends a validated profile to a wider string-keyed TX77 snapshot. */
    static boolean appendStates(Context context, String mode,
                                Map<String, Integer> values,
                                Map<String, Integer> stableIds) {
        if (values == null || stableIds == null) {
            throw new IllegalArgumentException("Drive-mode target maps are null");
        }
        Map<OemVehicleStateTransport.StateKey, Integer> states = statesFor(context, mode);
        if (states == null) return false;
        for (Map.Entry<OemVehicleStateTransport.StateKey, Integer> entry
                : states.entrySet()) {
            values.put(entry.getKey().name, entry.getValue());
            stableIds.put(entry.getKey().name, entry.getKey().stableId);
        }
        return true;
    }
}
