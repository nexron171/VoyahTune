package ru.big.town.restoremode.dashboard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DashboardProfileTest {
    @Test
    public void displayOneSelectsThePassengerProfile() {
        assertTrue(DashboardProfile.forDisplay(1).isPassenger());
        assertFalse(DashboardProfile.forDisplay(0).isPassenger());
        assertFalse(DashboardProfile.forDisplay(7).isPassenger());
    }

    @Test
    public void passengerWidgetIdsAreIsolatedFromDriverIds() {
        DashboardProfile driver = DashboardProfile.driver();
        DashboardProfile passenger = DashboardProfile.passenger();
        assertTrue(driver.ownsWidgetId("3f97f7d2-98e6-4cb5-9d9c-d53b9bf1c3b5"));
        assertFalse(driver.ownsWidgetId("passenger-3f97f7d2-98e6-4cb5-9d9c-d53b9bf1c3b5"));
        assertTrue(passenger.ownsWidgetId("passenger-3f97f7d2-98e6-4cb5-9d9c-d53b9bf1c3b5"));
        assertFalse(passenger.ownsWidgetId("3f97f7d2-98e6-4cb5-9d9c-d53b9bf1c3b5"));
    }

    @Test
    public void passengerCardsStartHiddenUntilEnabled() {
        assertFalse(DashboardProfile.passenger().defaultVisible(true));
        assertTrue(DashboardProfile.driver().defaultVisible(true));
        assertFalse(DashboardProfile.driver().defaultVisible(false));
    }
}
