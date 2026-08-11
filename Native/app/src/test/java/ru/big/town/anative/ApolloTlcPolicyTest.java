package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ApolloTlcPolicyTest {
    private static ApolloTlcPolicy.Snapshot valid(int anp) {
        return new ApolloTlcPolicy.Snapshot(1, 0, anp, 2, 2);
    }

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
        assertEquals(ApolloTlcPolicy.Signal.PLC_SWITCH,
                ApolloTlcPolicy.Signal.fromId(1135));
        assertNull(ApolloTlcPolicy.Signal.fromId(9999));
    }

    @Test
    public void selectiveTrafficLightEntitlementVectorIsCompleteAndIsolated() {
        ApolloTlcPolicy.Entitlement[] values = ApolloTlcPolicy.Entitlement.values();
        assertEquals(18, values.length);
        int enabled = 0;
        for (int i = 0; i < values.length; i++) {
            ApolloTlcPolicy.Entitlement entitlement = values[i];
            assertEquals(1166 + i, entitlement.id);
            int expected = entitlement == ApolloTlcPolicy.Entitlement.GLC_FUNC_ENABLE
                    || entitlement == ApolloTlcPolicy.Entitlement.TLA_FUNC_ENABLE_SA
                    ? ApolloTlcPolicy.MODULE_ON : ApolloTlcPolicy.MODULE_OFF;
            assertEquals(expected, entitlement.selectiveTrafficLightValue(true));
            assertEquals(ApolloTlcPolicy.MODULE_OFF,
                    entitlement.selectiveTrafficLightValue(false));
            if (entitlement.selectiveTrafficLightValue(true) == ApolloTlcPolicy.MODULE_ON) {
                enabled++;
            }
        }
        assertEquals(2, enabled);
    }

    @Test
    public void profileNeedsFullBothApkHashesHookAndRuntimeProfile() {
        assertTrue(ApolloTlcPolicy.profileSupported(
                true, true, true, true, true, true, true));
        assertFalse(ApolloTlcPolicy.profileSupported(
                false, true, true, true, true, true, true));
        assertFalse(ApolloTlcPolicy.profileSupported(
                true, false, true, true, true, true, true));
        assertFalse(ApolloTlcPolicy.profileSupported(
                true, true, false, true, true, true, true));
        assertFalse(ApolloTlcPolicy.profileSupported(
                true, true, true, false, true, true, true));
        assertFalse(ApolloTlcPolicy.profileSupported(
                true, true, true, true, false, true, true));
        assertFalse(ApolloTlcPolicy.profileSupported(
                true, true, true, true, true, false, true));
        assertFalse(ApolloTlcPolicy.profileSupported(
                true, true, true, true, true, true, false));
    }

    @Test
    public void heartbeatRejectsMissingFutureAndStaleValues() {
        long now = 1_000_000L;
        assertFalse(ApolloTlcPolicy.heartbeatFresh(now, -1L));
        assertFalse(ApolloTlcPolicy.heartbeatFresh(now, 0L));
        assertFalse(ApolloTlcPolicy.heartbeatFresh(now, now + 1L));
        assertTrue(ApolloTlcPolicy.heartbeatFresh(now, now));
        assertTrue(ApolloTlcPolicy.heartbeatFresh(
                now, now - ApolloTlcPolicy.PROFILE_HEARTBEAT_MAX_AGE_MS));
        assertFalse(ApolloTlcPolicy.heartbeatFresh(
                now, now - ApolloTlcPolicy.PROFILE_HEARTBEAT_MAX_AGE_MS - 1L));
    }

    @Test
    public void masterIsNeverEffectiveWithoutSupportedFullProfile() {
        assertTrue(ApolloTlcPolicy.effectiveMaster(true, true, true, true, false, true));
        assertFalse(ApolloTlcPolicy.effectiveMaster(false, true, true, true, false, true));
        assertFalse(ApolloTlcPolicy.effectiveMaster(true, false, true, true, false, true));
        assertFalse(ApolloTlcPolicy.effectiveMaster(true, true, false, true, false, true));
        assertFalse(ApolloTlcPolicy.effectiveMaster(true, true, true, false, false, true));
        // A failed/ambiguous ON write leaves this in-memory fail-safe asserted even if an old
        // persisted value still reads as 1.
        assertFalse(ApolloTlcPolicy.effectiveMaster(true, true, true, true, true, true));
        assertFalse(ApolloTlcPolicy.effectiveMaster(true, true, true, true, false, false));
        // The value bit remains truthful only when its accompanying known bit is true.
        assertTrue(ApolloTlcPolicy.reportedMasterEnabled(true, true));
        assertFalse(ApolloTlcPolicy.reportedMasterEnabled(true, false));
        assertFalse(ApolloTlcPolicy.reportedMasterEnabled(false, true));
    }

    @Test
    public void binderLifecycleRequiresFullAndBothCompletedHashPins() {
        assertTrue(ApolloTlcPolicy.binderProfilePinned(true, true, true, true, true));
        assertFalse(ApolloTlcPolicy.binderProfilePinned(false, true, true, true, true));
        assertFalse(ApolloTlcPolicy.binderProfilePinned(true, false, true, true, true));
        assertFalse(ApolloTlcPolicy.binderProfilePinned(true, true, false, true, true));
        assertFalse(ApolloTlcPolicy.binderProfilePinned(true, true, true, false, true));
        assertFalse(ApolloTlcPolicy.binderProfilePinned(true, true, true, true, false));
    }

    @Test
    public void masterOnRequiresLiveReadCallbackParkingAndValidTelemetry() {
        assertEquals("", ApolloTlcPolicy.masterEnableBlockReason(
                true, true, true, true, false, 0, valid(1)));
        assertEquals("can_disconnected", ApolloTlcPolicy.masterEnableBlockReason(
                true, true, false, true, false, 0, valid(1)));
        assertEquals("state_read_failed", ApolloTlcPolicy.masterEnableBlockReason(
                true, true, true, false, false, 0, valid(1)));
        assertEquals("write_pending", ApolloTlcPolicy.masterEnableBlockReason(
                true, true, true, true, true, 0, valid(1)));
        assertEquals("gear_not_parking", ApolloTlcPolicy.masterEnableBlockReason(
                true, true, true, true, false, 3, valid(1)));
        assertEquals("invalid_plc_switch", ApolloTlcPolicy.masterEnableBlockReason(
                true, true, true, true, false, 0,
                new ApolloTlcPolicy.Snapshot(-1, 0, 1, 2, 2)));
        assertEquals("invalid_tlc_capability", ApolloTlcPolicy.masterEnableBlockReason(
                true, true, true, true, false, 0,
                new ApolloTlcPolicy.Snapshot(1, 0, 1, -1, -1)));
        assertEquals("invalid_tlc_capability", ApolloTlcPolicy.masterEnableBlockReason(
                true, true, true, true, false, 0,
                new ApolloTlcPolicy.Snapshot(1, 0, 1, 10, -1)));
    }

    @Test
    public void requestMapsToOemModuleValues() {
        assertEquals(2, ApolloTlcPolicy.requestedPlcState(true));
        assertEquals(1, ApolloTlcPolicy.requestedPlcState(false));
        assertEquals(1, ApolloTlcPolicy.requestedTsrState(true));
        assertEquals(2, ApolloTlcPolicy.requestedTsrState(false));
    }

    @Test
    public void callbackRegistrationRequiresExactTrueAidlBoolean() {
        assertFalse(ApolloTlcPolicy.callbackRegistrationAccepted(0));
        assertTrue(ApolloTlcPolicy.callbackRegistrationAccepted(1));
        assertFalse(ApolloTlcPolicy.callbackRegistrationAccepted(-1));
        assertFalse(ApolloTlcPolicy.callbackRegistrationAccepted(2));
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
    public void validEnablePassesAllGates() {
        assertEquals("", ApolloTlcPolicy.writeBlockReason(
                true, true, true, false, true, false, 0, valid(2), true));
    }

    @Test
    public void everyTransportAndSafetyGateFailsClosed() {
        ApolloTlcPolicy.Snapshot snapshot = valid(1);
        assertEquals("unsupported_light", ApolloTlcPolicy.writeBlockReason(
                false, true, true, false, true, false, 0, snapshot, true));
        assertEquals("profile_unsupported", ApolloTlcPolicy.writeBlockReason(
                true, false, true, false, true, false, 0, snapshot, true));
        assertEquals("can_disconnected", ApolloTlcPolicy.writeBlockReason(
                true, true, false, false, true, false, 0, snapshot, true));
        assertEquals("master_disabled", ApolloTlcPolicy.writeBlockReason(
                true, true, true, false, false, false, 0, snapshot, true));
        assertEquals("write_pending", ApolloTlcPolicy.writeBlockReason(
                true, true, true, false, true, true, 0, snapshot, true));
        assertEquals("gear_not_parking", ApolloTlcPolicy.writeBlockReason(
                true, true, true, false, true, false, 3, snapshot, true));
    }

    @Test
    public void invalidAndErrorTelemetryBlocksWrite() {
        assertEquals("invalid_plc_switch", ApolloTlcPolicy.writeBlockReason(
                true, true, true, false, true, false, 0,
                new ApolloTlcPolicy.Snapshot(-1, 0, 1, 2, 2), true));
        assertEquals("plc_status_error", ApolloTlcPolicy.writeBlockReason(
                true, true, true, false, true, false, 0,
                new ApolloTlcPolicy.Snapshot(1, 7, 1, 2, 2), true));
        assertEquals("invalid_anp_switch", ApolloTlcPolicy.writeBlockReason(
                true, true, true, false, true, false, 0,
                new ApolloTlcPolicy.Snapshot(1, 0, -1, 2, 2), true));
        assertEquals("invalid_tlc_capability", ApolloTlcPolicy.writeBlockReason(
                true, true, true, false, true, false, 0,
                new ApolloTlcPolicy.Snapshot(1, 0, 1, -1, 2), true));
        assertEquals("invalid_plc_capability_sa", ApolloTlcPolicy.writeBlockReason(
                true, true, true, false, true, false, 0,
                new ApolloTlcPolicy.Snapshot(1, 0, 1, 2, -1), true));
    }

    @Test
    public void legacyEnableRequiresCapabilitiesButDisableDoesNotDependOnAnp() {
        assertEquals("capability_disabled", ApolloTlcPolicy.writeBlockReason(
                true, true, true, false, true, false, 0,
                new ApolloTlcPolicy.Snapshot(1, 0, 1, 1, 2), true));
        assertEquals("", ApolloTlcPolicy.writeBlockReason(
                true, true, true, false, true, false, 0, valid(2), false));
        assertEquals("", ApolloTlcPolicy.writeBlockReason(
                true, true, true, false, true, false, 0, valid(1), false));
    }

    @Test
    public void directTlcNeedsNoMasterCapabilitiesStatusOrAnp() {
        ApolloTlcPolicy.Snapshot h97x =
                new ApolloTlcPolicy.Snapshot(1, -1, -1, -1, -1);
        assertEquals("", ApolloTlcPolicy.writeBlockReason(
                true, true, true, true, false, false, 0, h97x, true));
        assertEquals("", ApolloTlcPolicy.writeBlockReason(
                true, true, true, true, false, false, 0, h97x, false));
        assertEquals("gear_not_parking", ApolloTlcPolicy.writeBlockReason(
                true, true, true, true, false, false, 3, h97x, true));
        assertEquals("invalid_plc_switch", ApolloTlcPolicy.writeBlockReason(
                true, true, true, true, false, false, 0,
                new ApolloTlcPolicy.Snapshot(-1, -1, -1, -1, -1), true));
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
