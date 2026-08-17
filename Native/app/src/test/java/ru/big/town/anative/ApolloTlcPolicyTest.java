package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ApolloTlcPolicyTest {
    @Test
    public void pinnedSignalMappingMatchesAllowListedProfile() {
        assertEquals(277, ApolloTlcPolicy.Signal.TSR_SWITCH.id);
        assertEquals(1121, ApolloTlcPolicy.Signal.PLC_FUNCTION_STATUS.id);
        assertEquals(1135, ApolloTlcPolicy.Signal.PLC_SWITCH.id);
        assertEquals(1136, ApolloTlcPolicy.Signal.ANP_SWITCH.id);
        assertEquals(1149, ApolloTlcPolicy.Signal.GLA_SWITCH.id);
        assertEquals(1150, ApolloTlcPolicy.Signal.GLA_LIGHT_CHANGE_SWITCH.id);
        assertEquals(1170, ApolloTlcPolicy.Signal.TLC_FUNC_ENABLE.id);
        assertEquals(1179, ApolloTlcPolicy.Signal.PLC_FUNC_ENABLE_SA.id);
    }

    @Test
    public void compositeEntitlementVectorPreservesIndependentFeatures() {
        ApolloTlcPolicy.Entitlement[] values = ApolloTlcPolicy.Entitlement.values();
        assertEquals(18, values.length);
        for (int i = 0; i < values.length; i++) {
            ApolloTlcPolicy.Entitlement entitlement = values[i];
            assertEquals(1166 + i, entitlement.id);
        }
        assertEquals(0, enabledEntitlementCount(false, false));
        assertEquals(2, enabledEntitlementCount(true, false));
        assertEquals(2, enabledEntitlementCount(false, true));
        assertEquals(4, enabledEntitlementCount(true, true));

        assertEquals(ApolloTlcPolicy.MODULE_ON,
                ApolloTlcPolicy.Entitlement.TLC_FUNC_ENABLE.compositeValue(true, false));
        assertEquals(ApolloTlcPolicy.MODULE_ON,
                ApolloTlcPolicy.Entitlement.PLC_FUNC_ENABLE_SA.compositeValue(true, false));
        assertEquals(ApolloTlcPolicy.MODULE_OFF,
                ApolloTlcPolicy.Entitlement.GLC_FUNC_ENABLE.compositeValue(true, false));
        assertEquals(ApolloTlcPolicy.MODULE_OFF,
                ApolloTlcPolicy.Entitlement.TLA_FUNC_ENABLE_SA.compositeValue(true, false));
        assertEquals(ApolloTlcPolicy.MODULE_OFF,
                ApolloTlcPolicy.Entitlement.TLC_FUNC_ENABLE.compositeValue(false, true));
        assertEquals(ApolloTlcPolicy.MODULE_OFF,
                ApolloTlcPolicy.Entitlement.PLC_FUNC_ENABLE_SA.compositeValue(false, true));
        assertEquals(ApolloTlcPolicy.MODULE_ON,
                ApolloTlcPolicy.Entitlement.GLC_FUNC_ENABLE.compositeValue(false, true));
        assertEquals(ApolloTlcPolicy.MODULE_ON,
                ApolloTlcPolicy.Entitlement.TLA_FUNC_ENABLE_SA.compositeValue(false, true));
        assertEquals(ApolloTlcPolicy.MODULE_OFF,
                ApolloTlcPolicy.Entitlement.ACC_FUNC_ENABLE_SA.compositeValue(true, true));
        assertEquals(ApolloTlcPolicy.MODULE_OFF,
                ApolloTlcPolicy.Entitlement.ICA_FUNC_ENABLE_SA.compositeValue(true, true));
    }

    private static int enabledEntitlementCount(boolean tlc, boolean trafficLight) {
        int enabled = 0;
        for (ApolloTlcPolicy.Entitlement entitlement
                : ApolloTlcPolicy.Entitlement.values()) {
            if (entitlement.compositeValue(tlc, trafficLight)
                    == ApolloTlcPolicy.MODULE_ON) {
                enabled++;
            }
        }
        return enabled;
    }

    @Test
    public void compositeVectorRequiresBothLiveSwitchStates() {
        assertTrue(ApolloTlcPolicy.compositeSwitchStatesValid(1, 1));
        assertTrue(ApolloTlcPolicy.compositeSwitchStatesValid(2, 2));
        assertFalse(ApolloTlcPolicy.compositeSwitchStatesValid(-1, 2));
        assertFalse(ApolloTlcPolicy.compositeSwitchStatesValid(2, 10));
    }

    @Test
    public void reportedMasterValueRequiresKnownBit() {
        assertTrue(ApolloTlcPolicy.reportedMasterEnabled(true, true));
        assertFalse(ApolloTlcPolicy.reportedMasterEnabled(true, false));
        assertFalse(ApolloTlcPolicy.reportedMasterEnabled(false, true));
    }

    @Test
    public void binderLifecycleRequiresCompletedMatchingSchema() {
        assertTrue(ApolloTlcPolicy.binderProfilePinned(true, true, true, true));
        assertFalse(ApolloTlcPolicy.binderProfilePinned(false, true, true, true));
        assertFalse(ApolloTlcPolicy.binderProfilePinned(true, false, true, true));
        assertFalse(ApolloTlcPolicy.binderProfilePinned(true, true, false, true));
        assertFalse(ApolloTlcPolicy.binderProfilePinned(true, true, true, false));
    }

    @Test
    public void requestMapsToOemModuleValues() {
        assertEquals(2, ApolloTlcPolicy.requestedPlcState(true));
        assertEquals(1, ApolloTlcPolicy.requestedPlcState(false));
        assertEquals(1, ApolloTlcPolicy.requestedTsrState(true));
        assertEquals(2, ApolloTlcPolicy.requestedTsrState(false));
    }

    @Test
    public void canBusVerificationRejectsStaleDestroyedAndDifferentBinderResults() {
        assertTrue(ApolloTlcPolicy.verificationResultCurrent(
                true, false, 4, 4, true));
        assertFalse(ApolloTlcPolicy.verificationResultCurrent(
                false, false, 4, 4, true));
        assertFalse(ApolloTlcPolicy.verificationResultCurrent(
                true, true, 4, 4, true));
        assertFalse(ApolloTlcPolicy.verificationResultCurrent(
                true, false, 5, 4, true));
        assertFalse(ApolloTlcPolicy.verificationResultCurrent(
                true, false, 4, 4, false));
    }

    @Test
    public void staleConnectionEpochsAreRejected() {
        assertTrue(ApolloTlcPolicy.connectionEventCurrent(
                false, 7, 7, true));
        assertFalse(ApolloTlcPolicy.connectionEventCurrent(
                true, 7, 7, true));
        assertFalse(ApolloTlcPolicy.connectionEventCurrent(
                false, 8, 7, true));
        assertFalse(ApolloTlcPolicy.connectionEventCurrent(
                false, 7, 7, false));
    }

    @Test
    public void writeSessionRequiresCurrentWriteAndBindingGenerations() {
        assertTrue(ApolloTlcPolicy.writeSessionCurrent(
                false, true, 4, 4, 9, 9));
        assertFalse(ApolloTlcPolicy.writeSessionCurrent(
                true, true, 4, 4, 9, 9));
        assertFalse(ApolloTlcPolicy.writeSessionCurrent(
                false, false, 4, 4, 9, 9));
        assertFalse(ApolloTlcPolicy.writeSessionCurrent(
                false, true, 5, 4, 9, 9));
        assertFalse(ApolloTlcPolicy.writeSessionCurrent(
                false, true, 4, 4, 10, 9));
    }

    @Test
    public void validEnablePassesAllGates() {
        assertEquals("", ApolloTlcPolicy.directTlcBlockReason(
                true, true, true, false, 0, ApolloTlcPolicy.MODULE_ON));
    }

    @Test
    public void everyTransportAndSafetyGateFailsClosed() {
        assertEquals("unsupported_light", ApolloTlcPolicy.directTlcBlockReason(
                false, true, true, false, 0, ApolloTlcPolicy.MODULE_ON));
        assertEquals("profile_unsupported", ApolloTlcPolicy.directTlcBlockReason(
                true, false, true, false, 0, ApolloTlcPolicy.MODULE_ON));
        assertEquals("can_disconnected", ApolloTlcPolicy.directTlcBlockReason(
                true, true, false, false, 0, ApolloTlcPolicy.MODULE_ON));
        assertEquals("write_pending", ApolloTlcPolicy.directTlcBlockReason(
                true, true, true, true, 0, ApolloTlcPolicy.MODULE_ON));
        assertEquals("gear_not_parking", ApolloTlcPolicy.directTlcBlockReason(
                true, true, true, false, 3, ApolloTlcPolicy.MODULE_ON));
    }

    @Test
    public void directTlcRejectsUnknownSwitchState() {
        assertEquals("invalid_plc_switch", ApolloTlcPolicy.directTlcBlockReason(
                true, true, true, false, 0, ApolloTlcPolicy.UNKNOWN));
        assertEquals("invalid_plc_switch",
                ApolloTlcPolicy.directTlcStateError(ApolloTlcPolicy.UNKNOWN));
        assertEquals("", ApolloTlcPolicy.directTlcStateError(ApolloTlcPolicy.MODULE_OFF));
    }

    @Test
    public void trafficLightSwitchDoesNotRequireParking() {
        assertEquals("", ApolloTlcPolicy.directSwitchBlockReason(
                true, true, true, false, 1));
        assertEquals("write_pending", ApolloTlcPolicy.directSwitchBlockReason(
                true, true, true, true, 1));
        assertEquals("invalid_switch_state", ApolloTlcPolicy.directSwitchBlockReason(
                true, true, true, false, -1));
    }
}
