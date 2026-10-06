package ru.big.town.anative;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Android-free VehicleState mapping for the Apollo targets restored by VoyahTune. */
final class ApolloRestorePolicy {
    static final String PLC_SWITCH = "PLC_SWITCH";
    static final int PLC_SWITCH_ID = 1135;
    static final String GLA_SWITCH = "GLA_SWITCH";
    static final int GLA_SWITCH_ID = 1149;
    static final String GLA_LIGHT_CHANGE_SWITCH = "GLA_LIGHT_CHANGE_SWITCH";
    static final int GLA_LIGHT_CHANGE_SWITCH_ID = 1150;
    static final String TSR_SWITCH = "TSR_SWITCH";
    static final int TSR_SWITCH_ID = 277;
    static final String ISA_ISLC_SWITCH = "ISA_ISLC_SWITCH";
    static final int ISA_ISLC_SWITCH_ID = 1141;
    static final String ISA_ISLC_MODE = "ISA_ISLC_MODE";
    static final int ISA_ISLC_MODE_ID = 1142;

    static final String ISA_ISLC_OVER_SPEED_WARNING_SWITCH = "ISA_ISLC_OVER_SPEED_WARNING_SWITCH";
    static final int ISA_ISLC_OVER_SPEED_WARNING_SWITCH_ID = 1143;

    static final String RPA_FUNC_ENABLE = "RPA_FUNC_ENABLE";
    static final int RPA_FUNC_ENABLE_ID = 1166;
    static final String HPP_FUNC_ENABLE = "HPP_FUNC_ENABLE";
    static final int HPP_FUNC_ENABLE_ID = 1167;
    static final String GLC_FUNC_ENABLE = "GLC_FUNC_ENABLE";
    static final int GLC_FUNC_ENABLE_ID = 1168;
    static final String ISLC_FUNC_ENABLE = "ISLC_FUNC_ENABLE";
    static final int ISLC_FUNC_ENABLE_ID = 1169;
    static final String TLC_FUNC_ENABLE = "TLC_FUNC_ENABLE";
    static final int TLC_FUNC_ENABLE_ID = 1170;
    static final String NOA_FUNC_ENABLE = "NOA_FUNC_ENABLE";
    static final int NOA_FUNC_ENABLE_ID = 1171;
    static final String ELK_FUNC_ENABLE = "ELK_FUNC_ENABLE";
    static final int ELK_FUNC_ENABLE_ID = 1172;
    static final String ESA_FUNC_ENABLE = "ESA_FUNC_ENABLE";
    static final int ESA_FUNC_ENABLE_ID = 1173;
    static final String APA_FUNC_ENABLE_SA = "APA_FUNC_ENABLE_SA";
    static final int APA_FUNC_ENABLE_SA_ID = 1174;
    static final String RPA_FUNC_ENABLE_SA = "RPA_FUNC_ENABLE_SA";
    static final int RPA_FUNC_ENABLE_SA_ID = 1175;
    static final String HAVP_FUNC_ENABLE_SA = "HAVP_FUNC_ENABLE_SA";
    static final int HAVP_FUNC_ENABLE_SA_ID = 1176;
    static final String ACC_FUNC_ENABLE_SA = "ACC_FUNC_ENABLE_SA";
    static final int ACC_FUNC_ENABLE_SA_ID = 1177;
    static final String ICA_FUNC_ENABLE_SA = "ICA_FUNC_ENABLE_SA";
    static final int ICA_FUNC_ENABLE_SA_ID = 1178;
    static final String ISA_FUNC_ENABLE_SA = "ISA_FUNC_ENABLE_SA";
    static final int ISA_FUNC_ENABLE_SA_ID = 1181;
    static final String ISLC_FUNC_ENABLE_SA = "ISLC_FUNC_ENABLE_SA";
    static final int ISLC_FUNC_ENABLE_SA_ID = 1182;
    static final String PLC_FUNC_ENABLE_SA = "PLC_FUNC_ENABLE_SA";
    static final int PLC_FUNC_ENABLE_SA_ID = 1179;
    static final String HANP_FUNC_ENABLE_SA = "HANP_FUNC_ENABLE_SA";
    static final int HANP_FUNC_ENABLE_SA_ID = 1180;
    static final String TLA_FUNC_ENABLE_SA = "TLA_FUNC_ENABLE_SA";
    static final int TLA_FUNC_ENABLE_SA_ID = 1183;

    private static final int ENABLED = 2;
    private static final int DISABLED = 1;

    private ApolloRestorePolicy() {
    }

    /**
     * Entitlements are submitted first, then the selected ISA mode, then user switches. Separate
     * OEM tasks keep feature activation behind both the capability frame and mode selection.
     */
    static void appendTo(Map<String, Integer> entitlements,
                         Map<String, Integer> modes,
                         Map<String, Integer> switches,
                         boolean tlc, boolean trafficLights,
                         boolean greenSound, boolean trafficSigns, boolean speedSigns) {
        // Existing PI callers retain recognition-only behaviour.
        appendBase(entitlements, switches, tlc, trafficLights, greenSound, trafficSigns, speedSigns);
        if (speedSigns) modes.put(ISA_ISLC_MODE, 4);
    }

    static void appendTo(Map<String, Integer> entitlements,
                         Map<String, Integer> modes,
                         Map<String, Integer> switches,
                         boolean tlc, boolean trafficLights,
                         boolean greenSound, boolean trafficSigns, boolean speedSigns,
                         int speedMode, boolean speedWarning) {
        appendBase(entitlements, switches, tlc, trafficLights, greenSound, trafficSigns, speedSigns);
        // Separate mode task precedes enablement. Off also clears any active correction mode.
        modes.put(ISA_ISLC_MODE, speedSigns ? normalizeSpeedMode(speedMode) : 4);
        switches.put(ISA_ISLC_OVER_SPEED_WARNING_SWITCH, state(speedSigns && speedWarning));
    }

    static int normalizeSpeedMode(int mode) {
        return mode == 2 || mode == 3 ? mode : 4;
    }

    private static void appendBase(Map<String, Integer> entitlements,
                                   Map<String, Integer> switches,
                                   boolean tlc, boolean trafficLights, boolean greenSound,
                                   boolean trafficSigns, boolean speedSigns) {
        if (entitlements == null || switches == null) {
            throw new IllegalArgumentException("Apollo target maps are null");
        }

        // H97X serializes these values into one zero-initialized 0x40A frame. Therefore the
        // capability snapshot must contain all 18 bits. Match the stock subscription manager:
        // enable the subscription while any effective Apollo feature is selected, and explicitly
        // disable it when the last feature is turned off. This shared vector also includes the
        // ACC/ICA/NOA entitlements. Green sound alone is inactive without traffic-light detection.
        putAllEntitlements(entitlements, state(tlc || trafficLights || trafficSigns || speedSigns));
        switches.put(PLC_SWITCH, state(tlc));

        switches.put(GLA_SWITCH, state(trafficLights));
        // Sound cannot remain enabled while the parent traffic-light function is disabled.
        switches.put(GLA_LIGHT_CHANGE_SWITCH, state(trafficLights && greenSound));

        // TSR uses inverse OEM encoding: 1=enabled, 2=disabled.
        switches.put(TSR_SWITCH, trafficSigns ? 1 : 2);
        switches.put(ISA_ISLC_SWITCH, speedSigns ? 1 : 2);
    }

    interface Sender {
        boolean send(Map<String, Integer> capabilities, Map<String, Integer> modes,
                     Map<String, Integer> switches);
    }

    static void appendPlan(CanRestorePlan.Builder plan, boolean tlc, boolean lights,
                           boolean sound, boolean signs, boolean speedSigns,
                           int speedMode, boolean speedWarning, Sender sender) {
        Map<String, Integer> capabilities = new LinkedHashMap<>();
        Map<String, Integer> modes = new LinkedHashMap<>();
        Map<String, Integer> switches = new LinkedHashMap<>();
        appendTo(capabilities, modes, switches, tlc, lights, sound, signs, speedSigns,
                speedMode, speedWarning);
        plan.addOnce("Apollo individual targets", () -> sender.send(capabilities, modes, switches)
                ? CanRestorePlan.OperationResult.ACCEPTED_UNCONFIRMED
                : CanRestorePlan.OperationResult.TRANSIENT_FAILURE);
    }

    private static void putAllEntitlements(Map<String, Integer> target, int value) {
        target.put(RPA_FUNC_ENABLE, value);
        target.put(HPP_FUNC_ENABLE, value);
        target.put(GLC_FUNC_ENABLE, value);
        target.put(ISLC_FUNC_ENABLE, value);
        target.put(TLC_FUNC_ENABLE, value);
        target.put(NOA_FUNC_ENABLE, value);
        target.put(ELK_FUNC_ENABLE, value);
        target.put(ESA_FUNC_ENABLE, value);
        target.put(APA_FUNC_ENABLE_SA, value);
        target.put(RPA_FUNC_ENABLE_SA, value);
        target.put(HAVP_FUNC_ENABLE_SA, value);
        target.put(ACC_FUNC_ENABLE_SA, value);
        target.put(ICA_FUNC_ENABLE_SA, value);
        target.put(PLC_FUNC_ENABLE_SA, value);
        target.put(HANP_FUNC_ENABLE_SA, value);
        target.put(ISA_FUNC_ENABLE_SA, value);
        target.put(ISLC_FUNC_ENABLE_SA, value);
        target.put(TLA_FUNC_ENABLE_SA, value);
    }

    static Map<String, Integer> stableIds() {
        LinkedHashMap<String, Integer> ids = new LinkedHashMap<>();
        ids.put(PLC_SWITCH, PLC_SWITCH_ID);
        ids.put(GLA_SWITCH, GLA_SWITCH_ID);
        ids.put(GLA_LIGHT_CHANGE_SWITCH, GLA_LIGHT_CHANGE_SWITCH_ID);
        ids.put(TSR_SWITCH, TSR_SWITCH_ID);
        ids.put(ISA_ISLC_SWITCH, ISA_ISLC_SWITCH_ID);
        ids.put(ISA_ISLC_MODE, ISA_ISLC_MODE_ID);
        ids.put(ISA_ISLC_OVER_SPEED_WARNING_SWITCH, ISA_ISLC_OVER_SPEED_WARNING_SWITCH_ID);
        ids.put(RPA_FUNC_ENABLE, RPA_FUNC_ENABLE_ID);
        ids.put(HPP_FUNC_ENABLE, HPP_FUNC_ENABLE_ID);
        ids.put(GLC_FUNC_ENABLE, GLC_FUNC_ENABLE_ID);
        ids.put(ISLC_FUNC_ENABLE, ISLC_FUNC_ENABLE_ID);
        ids.put(TLC_FUNC_ENABLE, TLC_FUNC_ENABLE_ID);
        ids.put(NOA_FUNC_ENABLE, NOA_FUNC_ENABLE_ID);
        ids.put(ELK_FUNC_ENABLE, ELK_FUNC_ENABLE_ID);
        ids.put(ESA_FUNC_ENABLE, ESA_FUNC_ENABLE_ID);
        ids.put(APA_FUNC_ENABLE_SA, APA_FUNC_ENABLE_SA_ID);
        ids.put(RPA_FUNC_ENABLE_SA, RPA_FUNC_ENABLE_SA_ID);
        ids.put(HAVP_FUNC_ENABLE_SA, HAVP_FUNC_ENABLE_SA_ID);
        ids.put(ACC_FUNC_ENABLE_SA, ACC_FUNC_ENABLE_SA_ID);
        ids.put(ICA_FUNC_ENABLE_SA, ICA_FUNC_ENABLE_SA_ID);
        ids.put(PLC_FUNC_ENABLE_SA, PLC_FUNC_ENABLE_SA_ID);
        ids.put(HANP_FUNC_ENABLE_SA, HANP_FUNC_ENABLE_SA_ID);
        ids.put(ISA_FUNC_ENABLE_SA, ISA_FUNC_ENABLE_SA_ID);
        ids.put(ISLC_FUNC_ENABLE_SA, ISLC_FUNC_ENABLE_SA_ID);
        ids.put(TLA_FUNC_ENABLE_SA, TLA_FUNC_ENABLE_SA_ID);
        return Collections.unmodifiableMap(ids);
    }

    private static int state(boolean enabled) {
        return enabled ? ENABLED : DISABLED;
    }
}
