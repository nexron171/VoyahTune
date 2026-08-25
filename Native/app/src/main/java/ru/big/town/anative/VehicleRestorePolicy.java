package ru.big.town.anative;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Android-free OEM VehicleState mapping for the non-drive portions of the restore snapshot. */
final class VehicleRestorePolicy {
    static final String SOC_MODE = "IVI_SOC_MODESET";
    static final int SOC_MODE_ID = 957;
    static final int SOC_SMART = 1;
    static final int SOC_EV = 2;
    static final int SOC_REV = 3;
    static final int SOC_SREV = 4;
    static final int SOC_FORCE_EV = 5;

    static final String REGEN_LEVEL = "HUM_ENERGY_PTREGEN_LEVL";
    static final int REGEN_LEVEL_ID = 619;
    static final int REGEN_LOW = 2;
    static final int REGEN_MEDIUM = 3;
    static final int REGEN_HIGH = 4;

    static final String PEDESTRIAN_SOUND = "HUM_VSP_FUNCTION_SW";
    static final int PEDESTRIAN_SOUND_ID = 665;
    static final int PEDESTRIAN_SOUND_DISABLED = 1;
    static final int PEDESTRIAN_SOUND_ENABLED = 2;

    private VehicleRestorePolicy() {
    }

    /**
     * Appends only explicit user-owned restore fields. Shared charge/chassis/ambient fields are
     * deliberately absent and will be filled by the Android 11 OEM CanBusService from its cache.
     */
    static void appendPrimaryTo(Map<String, Integer> target,
                                boolean energyEnabled, String energy,
                                boolean forcedEv) {
        if (target == null) throw new IllegalArgumentException("Target bundle is null");
        if (energyEnabled) target.put(SOC_MODE, requireEnergy(energy));
        // Forced EV intentionally wins over the ordinary energy selection in the same OEM bundle.
        if (forcedEv) target.put(SOC_MODE, SOC_FORCE_EV);
    }

    /**
     * Recuperation is sent as a second TX77 outside Snow. H97C converts each Bundle to an unordered
     * HashMap, while its Snow branch owns the minimum level. VoyahTune therefore omits the field for
     * Snow entirely and otherwise keeps the explicit target in a deterministic following OEM task.
     */
    static void appendRecuperationTo(Map<String, Integer> target,
                                     boolean recycleEnabled, String recycle,
                                     String driveMode) {
        if (target == null) throw new IllegalArgumentException("Target bundle is null");
        if (recycleEnabled && allowsRecuperationRestore(driveMode)) {
            target.put(REGEN_LEVEL, requireRecycle(recycle));
        }
    }

    /** Snow owns the minimum recuperation level as a vehicle-safety decision. */
    static boolean allowsRecuperationRestore(String driveMode) {
        return !"SNOW".equals(driveMode);
    }

    /** H97C handles this state through TX58; TX77 would only fall back to the same setter. */
    static int pedestrianSoundState(boolean disabled) {
        return disabled ? PEDESTRIAN_SOUND_DISABLED : PEDESTRIAN_SOUND_ENABLED;
    }

    static int requireEnergy(String mode) {
        if ("SMART".equals(mode) || "Smart".equals(mode)) return SOC_SMART;
        if ("EV".equals(mode)) return SOC_EV;
        if ("REV".equals(mode)) return SOC_REV;
        if ("SREV".equals(mode)) return SOC_SREV;
        throw new IllegalArgumentException("Unsupported energy mode: " + mode);
    }

    static int requireRecycle(String mode) {
        if ("LOW".equals(mode)) return REGEN_LOW;
        if ("MEDIUM".equals(mode)) return REGEN_MEDIUM;
        if ("HIGH".equals(mode)) return REGEN_HIGH;
        throw new IllegalArgumentException("Unsupported recuperation mode: " + mode);
    }

    static Map<String, Integer> stableIds() {
        LinkedHashMap<String, Integer> ids = new LinkedHashMap<>();
        ids.put(SOC_MODE, SOC_MODE_ID);
        ids.put(REGEN_LEVEL, REGEN_LEVEL_ID);
        ids.put(PEDESTRIAN_SOUND, PEDESTRIAN_SOUND_ID);
        return Collections.unmodifiableMap(ids);
    }
}
