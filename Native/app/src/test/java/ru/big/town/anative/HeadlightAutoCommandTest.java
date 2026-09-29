package ru.big.town.anative;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static ru.big.town.anative.HeadlightCanPolicy.Command.*;

public class HeadlightAutoCommandTest {
    @Test public void forceInitAndDriveDoNotToggleAutoBackToLow() {
        AtomicInteger actualAuto = new AtomicInteger(0);
        AtomicInteger sends = new AtomicInteger(0);
        AtomicInteger ihbcReads = new AtomicInteger(0);
        for (String source : new String[]{"reason", "force-init", "drive", "snapshot"}) {
            assertEquals(source, AUTO_LAMP_SWITCH, AutoLightCommand.send(4, true, LOW_BEAM,
                    () -> { ihbcReads.incrementAndGet(); return 1; }, () -> true,
                    target -> HeadlightAutoCommand.send(actualAuto::get, () -> {
                        actualAuto.set(1 - actualAuto.get()); // actual OEM toggle semantics
                        sends.incrementAndGet();
                        return true;
                    })));
            assertEquals(source, 1, actualAuto.get());
        }
        assertEquals(1, sends.get());
        assertEquals(4, ihbcReads.get());
    }

    @Test public void unknownStatusNeverSendsBlindToggle() {
        for (Integer state : new Integer[]{null, -1, 2}) {
            assertFalse(HeadlightAutoCommand.send(() -> state,
                    () -> { fail("unknown status must defer AUTO"); return true; }));
        }
    }

    @Test public void failedToggleRemainsRetryable() {
        assertFalse(HeadlightAutoCommand.send(() -> 0, () -> false));
        assertTrue(HeadlightAutoCommand.send(() -> 0, () -> true));
        assertTrue(HeadlightAutoCommand.send(() -> 1,
                () -> { fail("already AUTO"); return false; }));
    }
}
