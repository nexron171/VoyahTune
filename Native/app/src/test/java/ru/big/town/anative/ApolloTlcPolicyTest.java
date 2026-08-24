package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ApolloTlcPolicyTest {
    @Test
    public void readOnlySignalMappingContainsNoEntitlementOrWriteOnlyState() {
        ApolloTlcPolicy.Signal[] signals = ApolloTlcPolicy.Signal.values();
        assertEquals(4, signals.length);
        assertEquals(277, ApolloTlcPolicy.Signal.TSR_SWITCH.id);
        assertEquals(1135, ApolloTlcPolicy.Signal.PLC_SWITCH.id);
        assertEquals(1149, ApolloTlcPolicy.Signal.GLA_SWITCH.id);
        assertEquals(1150, ApolloTlcPolicy.Signal.GLA_LIGHT_CHANGE_SWITCH.id);
    }

    @Test
    public void binderLifecycleRequiresCompletedMatchingSchemaAndPermission() {
        assertTrue(ApolloTlcPolicy.binderProfilePinned(true, true, true, true));
        assertFalse(ApolloTlcPolicy.binderProfilePinned(false, true, true, true));
        assertFalse(ApolloTlcPolicy.binderProfilePinned(true, false, true, true));
        assertFalse(ApolloTlcPolicy.binderProfilePinned(true, true, false, true));
        assertFalse(ApolloTlcPolicy.binderProfilePinned(true, true, true, false));
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
        assertTrue(ApolloTlcPolicy.connectionEventCurrent(false, 7, 7, true));
        assertFalse(ApolloTlcPolicy.connectionEventCurrent(true, 7, 7, true));
        assertFalse(ApolloTlcPolicy.connectionEventCurrent(false, 8, 7, true));
        assertFalse(ApolloTlcPolicy.connectionEventCurrent(false, 7, 7, false));
    }
}
