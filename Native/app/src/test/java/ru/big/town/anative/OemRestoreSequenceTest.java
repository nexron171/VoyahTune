package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class OemRestoreSequenceTest {
    private static Map<OemVehicleStateTransport.StateKey, Integer> field(String name, int id, int value) {
        return Collections.singletonMap(new OemVehicleStateTransport.StateKey(name, id), value);
    }

    @Test public void modeMustBeSubmittedBeforeEnableAndFailureStopsLaterPhases() {
        Map<OemVehicleStateTransport.StateKey, Integer> capabilities = field("ISA_FUNC_ENABLE_SA", 1181, 2);
        Map<OemVehicleStateTransport.StateKey, Integer> mode = field("ISA_ISLC_MODE", 1142, 4);
        Map<OemVehicleStateTransport.StateKey, Integer> enable = field("ISA_ISLC_SWITCH", 1141, 1);
        List<Map<OemVehicleStateTransport.StateKey, Integer>> expected = Arrays.asList(capabilities, mode, enable);
        for (int failAt = 0; failAt <= 3; failAt++) {
            final int failure = failAt;
            List<String> stages = new ArrayList<>();
            OemVehicleStateTransport.Result result = OemVehicleStateTransport.sendOrderedBundles(
                    capabilities, mode, enable, (values, stage) -> {
                        int index = stages.size();
                        assertSame(expected.get(index), values);
                        stages.add(stage);
                        return index == failure ? OemVehicleStateTransport.Result.TRANSIENT_FAILURE
                                : OemVehicleStateTransport.Result.ACCEPTED_UNCONFIRMED;
                    });
            assertEquals(Arrays.asList("primary", "preparation", "trailing")
                    .subList(0, Math.min(failAt + 1, 3)), stages);
            assertEquals(failAt < 3 ? OemVehicleStateTransport.Result.TRANSIENT_FAILURE
                    : OemVehicleStateTransport.Result.ACCEPTED_UNCONFIRMED, result);
        }
    }

    @Test public void existingTwoPhaseRestoresSkipEmptyPreparation() {
        List<String> stages = new ArrayList<>();
        OemVehicleStateTransport.sendOrderedBundles(field("TLC_FUNC_ENABLE", 1170, 2),
                Collections.emptyMap(), field("PLC_SWITCH", 1135, 2), (values, stage) -> {
                    stages.add(stage);
                    return OemVehicleStateTransport.Result.ACCEPTED_UNCONFIRMED;
                });
        assertEquals(Arrays.asList("primary", "trailing"), stages);
    }
}
