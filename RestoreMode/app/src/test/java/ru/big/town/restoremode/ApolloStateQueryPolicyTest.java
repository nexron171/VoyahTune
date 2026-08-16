package ru.big.town.restoremode;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ApolloStateQueryPolicyTest {
    @Test
    public void boundMessengerDeliveryDoesNotBroadcastDuplicateQuery() {
        assertFalse(AdvanceActivity.shouldUseApolloBroadcastFallback(true, true));
    }

    @Test
    public void messengerFailureFallsBackToBroadcast() {
        assertTrue(AdvanceActivity.shouldUseApolloBroadcastFallback(true, false));
    }

    @Test
    public void unavailableMessengerUsesBroadcast() {
        assertTrue(AdvanceActivity.shouldUseApolloBroadcastFallback(false, false));
    }
}
