package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

import ru.big.town.common.FragranceWidgetProtocol;

import java.util.LinkedHashMap;
import java.util.Map;

public class FragranceVehicleTest {
    private final Map<String, Integer> observed = new LinkedHashMap<>();
    private final Map<String, Integer> sent = new LinkedHashMap<>();
    private final OemVehicleStateTransport.Session vehicle =
            new OemVehicleStateTransport.Session() {
                public OemVehicleStateTransport.GearStatus readGearStatus() {
                    throw new AssertionError();
                }

                public Integer readVehicleState(OemVehicleStateTransport.StateKey key) {
                    return observed.get(key.name);
                }

                public OemVehicleStateTransport.Result sendVehicleState(
                        OemVehicleStateTransport.StateValue state, String label) {
                    sent.put(state.key.name, state.value);
                    return OemVehicleStateTransport.Result.ACCEPTED_UNCONFIRMED;
                }

                public OemVehicleStateTransport.Result sendBundle(
                        Map<OemVehicleStateTransport.StateKey, Integer> values, String label) {
                    values.forEach((key, value) -> sent.put(key.name, value));
                    return OemVehicleStateTransport.Result.ACCEPTED_UNCONFIRMED;
                }
            };

    @Test
    public void selectingWhileOffEnablesOnlyRequestedScentAndWaitsForFeedback() {
        observed.put("FCM_SW_REQ", 1);
        observed.put("IVI_FRAG_TASTE", 1);
        assertTrue(FragranceVehicle.apply(vehicle, FragranceWidgetProtocol.SCENT, 3));
        assertEquals(Map.of("FCM_SW_REQ", 2, "IVI_FRAG_TASTE", 3), sent);
        assertFalse(FragranceVehicle.read(vehicle).matches(FragranceWidgetProtocol.SCENT, 3));
        observed.putAll(sent);
        assertTrue(FragranceVehicle.read(vehicle).matches(FragranceWidgetProtocol.SCENT, 3));
    }

    @Test
    public void changingActiveScentOrIntensityLeavesOtherSettingsAlone() {
        observed.put("FCM_SW_REQ", 2);
        observed.put("IVI_FRAG_TASTE", 1);
        observed.put("IVI_FRAG_CONCERNTION", 99);
        assertFalse(FragranceVehicle.apply(vehicle, FragranceWidgetProtocol.LEVEL, 3));
        assertTrue(sent.isEmpty());
        assertTrue(FragranceVehicle.apply(vehicle, FragranceWidgetProtocol.SCENT, 2));
        assertEquals(Map.of("IVI_FRAG_TASTE", 2), sent);
        sent.clear();
        observed.put("IVI_FRAG_CONCERNTION", 2);
        assertTrue(FragranceVehicle.apply(vehicle, FragranceWidgetProtocol.LEVEL, 3));
        assertEquals(Map.of("IVI_FRAG_CONCERNTION", 3), sent);
        observed.putAll(sent);
        sent.clear();
        assertTrue(FragranceVehicle.apply(vehicle, FragranceWidgetProtocol.LEVEL, 3));
        assertTrue(sent.isEmpty());
    }

    @Test
    public void offAndTimerAreIndependentAndInvalidOrUnobservedCommandsDoNothing() {
        assertFalse(FragranceVehicle.apply(vehicle, FragranceWidgetProtocol.POWER, 2));
        observed.put("FCM_SW_REQ", 2);
        observed.put("IVI_FRAG_TASTE", 2);
        observed.put("FCM_DURATION_CONTROL", 2);
        assertFalse(FragranceVehicle.apply(vehicle, FragranceWidgetProtocol.TIMER, 3));
        assertTrue(sent.isEmpty());
        assertTrue(FragranceVehicle.apply(vehicle, FragranceWidgetProtocol.TIMER, 0));
        assertEquals(Map.of("FCM_DURATION_CONTROL", 0), sent);
        sent.clear();
        assertTrue(FragranceVehicle.apply(vehicle, FragranceWidgetProtocol.POWER, 1));
        assertEquals(Map.of("FCM_SW_REQ", 1), sent);
        observed.putAll(sent);
        sent.clear();
        assertFalse(FragranceVehicle.apply(vehicle, FragranceWidgetProtocol.LEVEL, 2));
        assertTrue(sent.isEmpty());
    }
}
