package ru.big.town.restoremode.passenger;

import ru.big.town.restoremode.dashboard.DashboardActivity;
import ru.big.town.restoremode.dashboard.DashboardProfile;

/**
 * Passenger entry point with its own task affinity. The explicit profile keeps passenger layout
 * preferences in use even if the activity is opened outside display 1.
 */
public final class PassengerDashboardActivity extends DashboardActivity {
    @Override
    protected DashboardProfile resolveDashboardProfile(int displayId) {
        return DashboardProfile.passenger();
    }
}
