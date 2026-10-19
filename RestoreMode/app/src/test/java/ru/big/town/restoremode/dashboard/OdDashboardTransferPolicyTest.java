package ru.big.town.restoremode.dashboard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.big.town.common.CarPlayTarget;
import ru.big.town.common.DashboardTarget;
import ru.big.town.common.InfrastructureProfile;

public class OdDashboardTransferPolicyTest {
    @Test
    public void dashboardsAndSettingsArePinnedOnlyOnOd() {
        for (String activity : new String[] {
                DashboardTarget.DRIVER,
                DashboardTarget.PASSENGER,
                "ru.big.town.restoremode.AdvanceActivity",
                "ru.big.town.restoremode.TripHistoryActivity"
        }) {
            assertTrue(OdDashboardTransferPolicy.shouldPin(InfrastructureProfile.OD, activity));
            assertFalse(OdDashboardTransferPolicy.shouldPin(InfrastructureProfile.PI, activity));
        }
    }

    @Test
    public void carPlayEntryAndProjectionKeepTheirOwnMovePolicy() {
        for (String activity : new String[] {
                CarPlayTarget.LAUNCHER,
                CarPlayTarget.ENTRY,
                CarPlayTarget.ACTIVITY,
                CarPlayTarget.PROJECTION
        }) {
            assertFalse(OdDashboardTransferPolicy.shouldPin(InfrastructureProfile.OD, activity));
        }
    }
}
