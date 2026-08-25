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
     * Entitlements are submitted first. The following OEM task then applies the user switches, so
     * enabling a previously unavailable function cannot race ahead of its ADCU capability frame.
     */
    static void appendTo(Map<String, Integer> entitlements,
                         Map<String, Integer> switches,
                         boolean tlc, boolean trafficLights,
                         boolean greenSound, boolean trafficSigns) {
        if (entitlements == null || switches == null) {
            throw new IllegalArgumentException("Apollo target maps are null");
        }

        // H97X serializes these values into one zero-initialized 0x40A frame. Therefore the
        // capability snapshot must contain all 18 bits: a partial bundle would silently disable
        // unrelated ACC/ICA/NOA capabilities. The stock subscription manager uses the same full
        // vector. We publish all-on only when at least one Apollo target is active and never emit
        // an all-off capability frame; individual user choices are applied by the switches below.
        if (tlc || trafficLights || trafficSigns) {
            putAllEntitlements(entitlements, ENABLED);
        }
        switches.put(PLC_SWITCH, state(tlc));

        switches.put(GLA_SWITCH, state(trafficLights));
        // Sound cannot remain enabled while the parent traffic-light function is disabled.
        switches.put(GLA_LIGHT_CHANGE_SWITCH, state(trafficLights && greenSound));

        // TSR uses inverse OEM encoding: 1=enabled, 2=disabled.
        switches.put(TSR_SWITCH, trafficSigns ? 1 : 2);
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
