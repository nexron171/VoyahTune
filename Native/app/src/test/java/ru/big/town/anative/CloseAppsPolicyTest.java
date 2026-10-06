package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;

public class CloseAppsPolicyTest {
    @Test public void clearPreservesSystemHomeAndVoyahTuneComponents() {
        for (String pkg : new String[]{"ru.big.town.restoremode", "ru.big.town.anative",
                "ru.big.town.updater", "big.town.runyn", "com.qinggan.launcher",
                "com.android.car.systemui", "third.party.home"}) {
            assertFalse(pkg, CloseAppsPolicy.canStop(pkg, false, "third.party.home"));
        }
        assertFalse(CloseAppsPolicy.canStop("some.system.app", true, null));
        assertFalse(CloseAppsPolicy.canStop(null, false, null));
        assertTrue(CloseAppsPolicy.canStop("com.example.player", false, "third.party.home"));
    }
}
