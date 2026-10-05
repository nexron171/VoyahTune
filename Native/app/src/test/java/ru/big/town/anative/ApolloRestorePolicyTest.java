package ru.big.town.anative;

import static org.junit.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class ApolloRestorePolicyTest {
    @Test
    public void eachOptionIsSentWithoutAnyGlobalActivationAndSurvivesOtherFailure() {
        for (int mask = 0; mask < 16; mask++) {
            final int selection = mask;
            int[] sent = {0};
            CanRestorePlan.Builder builder = new CanRestorePlan.Builder();
            builder.addOnce("unrelated unavailable setting",
                    () -> CanRestorePlan.OperationResult.TRANSIENT_FAILURE);
            ApolloRestorePolicy.appendPlan(builder, (mask & 1) != 0, (mask & 2) != 0,
                    (mask & 4) != 0, (mask & 8) != 0, (capabilities, switches) -> {
                        sent[0]++;
                        assertEquals((selection & 11) != 0 ? 18 : 0, capabilities.size());
                        assertEquals(Integer.valueOf((selection & 1) != 0 ? 2 : 1), switches.get("PLC_SWITCH"));
                        assertEquals(Integer.valueOf((selection & 2) != 0 ? 2 : 1), switches.get("GLA_SWITCH"));
                        assertEquals(Integer.valueOf((selection & 6) == 6 ? 2 : 1), switches.get("GLA_LIGHT_CHANGE_SWITCH"));
                        assertEquals(Integer.valueOf((selection & 8) != 0 ? 1 : 2), switches.get("TSR_SWITCH"));
                        return true;
                    });
            CanRestorePlan plan = builder.build();
            assertEquals(CanRestorePlan.AttemptResult.TRANSIENT_FAILURE,
                    plan.sendPending((frames, label) -> false));
            plan.sendPending((frames, label) -> false);
            assertEquals(1, sent[0]);
        }
    }

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
