package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

class VoyahNavigationTest {
    private fun populate(state: VoyahNavigationState, owner: Any, type: Int = 1) {
        state.accept(owner, Iap2Messages.buildRaw(0x5202) {
            u16(1, 7); u8(3, type); string(4, "Test road"); u8(8, 0)
        })
        state.accept(owner, Iap2Messages.buildRaw(0x5201) {
            u8(1, 1); u32(10, 150); u16(13, 7)
        })
    }

    @Test fun routesAppleManeuverDistanceAndRoadWithoutKeepingEndedRoutes() {
        val state = VoyahNavigationState()
        val owner = Any()
        state.begin(owner)
        populate(state, owner)
        assertEquals(VoyahManeuver(7, 150, "Test road"), state.snapshot())
        state.accept(owner, Iap2Messages.buildRaw(0x5201) { u8(1, 0) })
        assertNull(state.snapshot())
    }

    @Test fun oldControllerCannotOverwriteOrClearTheReplacement() {
        val state = VoyahNavigationState()
        val old = Any(); val next = Any()
        state.begin(old); populate(state, old)
        state.begin(next)
        assertNull(state.snapshot())
        populate(state, next, 2)
        populate(state, old, 1)
        state.end(old)
        assertEquals(3, state.snapshot()!!.icon)
        state.end(next)
        assertNull(state.snapshot())
        populate(state, next)
        assertNull(state.snapshot())
    }

    @Test fun silentRouteExpiresAndUiTransferDoesNotClearIt() {
        var time = 1L
        val state = VoyahNavigationState { time }
        val owner = Any()
        state.begin(owner); populate(state, owner)
        state.begin(owner)
        assertNotNull(state.snapshot())
        time += 30_000_000_000L
        assertNull(state.snapshot())
    }

    @Test fun unverifiedManeuversDoNotInventAnArrow() {
        assertEquals(0, VoyahManeuverCodes.icon(255, 0))
        assertEquals(0, VoyahManeuverCodes.icon(4, 1))
        assertEquals(5, VoyahManeuverCodes.icon(4, 0))
        assertEquals(9, VoyahManeuverCodes.icon(28, 0))
    }
}
