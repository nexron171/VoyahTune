package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayThemePolicyTest {
    @Test fun vehicleLightControlsOnlyItsExplicitMode() {
        assertTrue(projectionNight(CarPlayNightMode.VEHICLE, true, false))
        assertFalse(projectionNight(CarPlayNightMode.VEHICLE, false, true))
        for (mode in CarPlayNightMode.entries.filter { it != CarPlayNightMode.VEHICLE }) {
            assertFalse(projectionNight(mode, true, false))
            assertTrue(projectionNight(mode, false, true))
        }
    }

    @Test fun unavailableVehicleLightUsesTheLocalFallback() {
        assertTrue(projectionNight(CarPlayNightMode.VEHICLE, null, true))
        assertFalse(projectionNight(CarPlayNightMode.VEHICLE, null, false))
    }

    @Test fun vehicleProjectionDoesNotEnterTheAppAppearance() {
        for (systemNight in listOf(false, true)) {
            assertEquals(systemNight, resolveAppNight(AppAppearance.AUTO, CarPlayNightMode.VEHICLE,
                CarPlayNightSchedule(), systemNight, 12 * 60, null))
            assertEquals(systemNight, resolveAppNight(AppAppearance.AUTO, CarPlayNightMode.VEHICLE,
                CarPlayNightSchedule(), !systemNight, 12 * 60, systemNight))
        }
        assertTrue(resolveAppNight(AppAppearance.DARK, CarPlayNightMode.VEHICLE,
            CarPlayNightSchedule(), false, 12 * 60, false))
        assertFalse(resolveAppNight(AppAppearance.LIGHT, CarPlayNightMode.VEHICLE,
            CarPlayNightSchedule(), true, 12 * 60, true))
    }

    @Test fun vehicleModeHasAStablePersistenceKey() {
        assertEquals(CarPlayNightMode.VEHICLE, CarPlayNightMode.fromKey("voyah_light"))
        assertEquals(CarPlayNightMode.SYSTEM, CarPlayNightMode.fromKey(null))
    }
}
