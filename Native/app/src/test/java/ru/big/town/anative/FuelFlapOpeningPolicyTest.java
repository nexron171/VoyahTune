package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

public class FuelFlapOpeningPolicyTest {
    private CanBusEvent state(long epoch, int value, CanBusEvent.Origin origin) {
        return CanBusEvent.vehicleState(origin, epoch, 1, 1, 780, value);
    }

    @Test
    public void onlyClosedToOpenLiveTransitionNotifies() {
        FuelFlapOpeningPolicy policy = new FuelFlapOpeningPolicy();
        assertFalse(policy.accept(state(1, 2, CanBusEvent.Origin.LIVE)));
        assertFalse(policy.accept(state(1, 1, CanBusEvent.Origin.LIVE)));
        assertTrue(policy.accept(state(1, 2, CanBusEvent.Origin.LIVE)));
        assertFalse(policy.accept(state(1, 2, CanBusEvent.Origin.LIVE)));
        assertFalse(policy.accept(state(1, 1, CanBusEvent.Origin.LIVE)));
        assertTrue(policy.accept(state(1, 2, CanBusEvent.Origin.LIVE)));
    }

    @Test
    public void replayReconnectAndUnknownStatesDoNotBecomeOpenings() {
        FuelFlapOpeningPolicy policy = new FuelFlapOpeningPolicy();
        policy.accept(state(1, 1, CanBusEvent.Origin.LIVE));
        assertFalse(policy.accept(state(1, 2, CanBusEvent.Origin.REPLAY)));
        policy.accept(state(1, 1, CanBusEvent.Origin.LIVE));
        assertFalse(policy.accept(state(2, 2, CanBusEvent.Origin.LIVE)));
        assertFalse(policy.accept(state(1, 1, CanBusEvent.Origin.LIVE)));
        assertFalse(policy.accept(state(2, 2, CanBusEvent.Origin.LIVE)));
        policy.accept(state(2, 1, CanBusEvent.Origin.LIVE));
        policy.accept(state(2, 4, CanBusEvent.Origin.LIVE));
        assertFalse(policy.accept(state(2, 2, CanBusEvent.Origin.LIVE)));
    }

    @Test
    public void estimateMatchesVoiceCalculationAndMissingDataStaysUnknown() {
        assertEquals(
                "Лючок бензобака открыт. Можно заправить примерно 28 л",
                FuelFlapOpeningPolicy.message(FuelRefillEstimate.liters(56, 50)));
        assertEquals(
                "Лючок бензобака открыт. Объём заправки недоступен",
                FuelFlapOpeningPolicy.message(FuelRefillEstimate.liters(0, 50)));
    }
}
