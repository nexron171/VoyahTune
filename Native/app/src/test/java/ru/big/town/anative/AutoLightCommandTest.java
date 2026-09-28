package ru.big.town.anative;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import static ru.big.town.anative.HeadlightCanPolicy.Command.*;

public class AutoLightCommandTest {
    @Test public void readsCurrentSwitchBeforeSendingEvenWhenCallbackSaysOff() {
        List<String> calls = new ArrayList<>();
        assertEquals(AUTO_LAMP_SWITCH, AutoLightCommand.send(2, true, LOW_BEAM,
                () -> { calls.add("read"); return 1; }, () -> true,
                target -> { calls.add("send " + target); return true; }));
        assertEquals(Arrays.asList("read", "send AUTO_LAMP_SWITCH"), calls);
    }

    @Test public void currentOffOverridesCachedAutoAndIsReadOnEverySend() {
        int[] reads = {0};
        for (int reason : new int[]{2, 4}) {
            assertEquals(LOW_BEAM, AutoLightCommand.send(reason, true, AUTO_LAMP_SWITCH,
                    () -> { reads[0]++; return 2; }, () -> true,
                    target -> { assertEquals(LOW_BEAM, target); return true; }));
        }
        assertEquals(2, reads[0]);
    }

    @Test public void missingReadingDoesNotReuseCachedAuto() {
        assertEquals(LOW_BEAM, AutoLightCommand.send(4, true, AUTO_LAMP_SWITCH,
                () -> null, () -> true, target -> true));
    }

    @Test public void manualIntentDuringReadCancelsSend() {
        AtomicBoolean current = new AtomicBoolean(true);
        assertNull(AutoLightCommand.send(2, true, LOW_BEAM,
                () -> { current.set(false); return 1; }, current::get,
                target -> { fail("stale command sent"); return true; }));
    }

    @Test public void legacyDoesNotReadOrChangeItsRequestedTarget() {
        assertEquals(LOW_BEAM, AutoLightCommand.send(2, false, LOW_BEAM,
                () -> { fail("legacy must not read IHBC"); return null; },
                () -> true, target -> true));
    }

    @Test public void failedSendDoesNotBecomeCommittedTarget() {
        assertNull(AutoLightCommand.send(2, true, LOW_BEAM,
                () -> 1, () -> true, target -> false));
    }
}
