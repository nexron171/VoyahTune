package ru.big.town.anative;

import static org.junit.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class ApolloRestorePolicyTest {
    @Test
    public void enabledTargetsProduceEntitlementsBeforeSwitches() {
        Map<String, Integer> entitlements = new LinkedHashMap<>();
        Map<String, Integer> switches = new LinkedHashMap<>();

        ApolloRestorePolicy.appendTo(entitlements, switches, true, true, true, true);

        assertEquals(18, entitlements.size());
        for (Integer value : entitlements.values()) {
            assertEquals(Integer.valueOf(2), value);
        }
        assertEquals(Integer.valueOf(2), switches.get("PLC_SWITCH"));
        assertEquals(Integer.valueOf(2), switches.get("GLA_SWITCH"));
        assertEquals(Integer.valueOf(2), switches.get("GLA_LIGHT_CHANGE_SWITCH"));
        assertEquals(Integer.valueOf(1), switches.get("TSR_SWITCH"));
    }

    @Test
    public void disabledTargetsActivelyTurnFunctionsOff() {
        Map<String, Integer> entitlements = new LinkedHashMap<>();
        Map<String, Integer> switches = new LinkedHashMap<>();

        ApolloRestorePolicy.appendTo(entitlements, switches, false, false, true, false);

        // Never emit a full all-off capability frame: it would also disable unrelated ACC/ICA.
        assertEquals(0, entitlements.size());
        assertEquals(Integer.valueOf(1), switches.get("PLC_SWITCH"));
        assertEquals(Integer.valueOf(1), switches.get("GLA_SWITCH"));
        assertEquals(Integer.valueOf(1), switches.get("GLA_LIGHT_CHANGE_SWITCH"));
        assertEquals(Integer.valueOf(2), switches.get("TSR_SWITCH"));
    }

    @Test
    public void stableIdsMatchAndroid11VehicleStateAbi() {
        Map<String, Integer> ids = ApolloRestorePolicy.stableIds();
        assertEquals(Integer.valueOf(1135), ids.get("PLC_SWITCH"));
        assertEquals(Integer.valueOf(1149), ids.get("GLA_SWITCH"));
        assertEquals(Integer.valueOf(1150), ids.get("GLA_LIGHT_CHANGE_SWITCH"));
        assertEquals(Integer.valueOf(277), ids.get("TSR_SWITCH"));
        assertEquals(Integer.valueOf(1170), ids.get("TLC_FUNC_ENABLE"));
        assertEquals(Integer.valueOf(1179), ids.get("PLC_FUNC_ENABLE_SA"));
        assertEquals(Integer.valueOf(1166), ids.get("RPA_FUNC_ENABLE"));
        assertEquals(Integer.valueOf(1183), ids.get("TLA_FUNC_ENABLE_SA"));
        assertEquals(22, ids.size());
    }
}
