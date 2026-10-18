package ru.big.town.restoremode.settings.sections.passenger;

import ru.big.town.restoremode.dashboard.DashboardProfile;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.sections.main.MainSettingsFragment;

/** Reuses the main dashboard rows against the passenger layout preferences. */
public final class PassengerSettingsFragment extends MainSettingsFragment {
    @Override
    public SettingsSection section() {
        return SettingsSection.PASSENGER;
    }

    @Override
    protected DashboardProfile profile() {
        return DashboardProfile.passenger();
    }
}
