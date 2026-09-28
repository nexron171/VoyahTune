package ru.big.town.anative;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class SaveChargeSequenceTest {
    private static class Car implements SaveChargeSequence.Vehicle, SaveChargeSequence.Clock {
        long time, modeAt = Long.MAX_VALUE, levelAt = Long.MAX_VALUE, cancelAt = Long.MAX_VALUE;
        int mode = 2, level = 11, target;
        boolean modeResponds = true, targetResponds = true, available = true, writesAccepted = true;
        boolean modeConfirmed;
        final List<String> writes = new ArrayList<>();
        @Override public SaveChargeSequence.State read() {
            if (!available) return null;
            if (time >= modeAt) mode = 4;
            if (time >= levelAt) level = target;
            return new SaveChargeSequence.State(mode, level);
        }
        @Override public boolean selectSrev() {
            writes.add("mode");
            if (modeResponds) modeAt = time + 300;
            return writesAccepted;
        }
        @Override public boolean setLevel(int value) {
            assertEquals("Target must follow mode feedback", 4, mode);
            assertTrue(modeConfirmed);
            writes.add("level:" + value); target = value;
            if (targetResponds) levelAt = time + 200;
            return writesAccepted;
        }
        @Override public void modeConfirmed() { modeConfirmed = true; }
        @Override public long now() { return time; }
        @Override public void sleep(long ms) { time += ms; }
        SaveChargeSequence.Result run(int percent) {
            return SaveChargeSequence.run(percent, this, this, () -> time < cancelAt);
        }
    }

    @Test public void switchesModeBeforeWritingZeroFor25AndWaitsForBothFeedbacks() {
        Car car = new Car();
        SaveChargeSequence.Result result = car.run(25);
        assertEquals(SaveChargeSequence.Outcome.CONFIRMED, result.outcome);
        assertEquals(java.util.Arrays.asList("mode", "level:0"), car.writes);
        assertEquals(500, car.time);
        assertEquals(0, result.observed.level);
    }

    @Test public void alreadySrevDoesNotReselectModeAndAlreadyCorrectDoesNotWrite() {
        Car car = new Car(); car.mode = 4;
        assertEquals(SaveChargeSequence.Outcome.CONFIRMED, car.run(50).outcome);
        assertEquals(java.util.Collections.singletonList("level:5"), car.writes);
        car.writes.clear();
        assertEquals(SaveChargeSequence.Outcome.CONFIRMED, car.run(50).outcome);
        assertTrue(car.writes.isEmpty());
    }

    @Test public void acceptedBinderWithoutTargetFeedbackIsFailureAndDoesNotRetryWrites() {
        Car car = new Car(); car.targetResponds = false;
        SaveChargeSequence.Result result = car.run(25);
        assertEquals(SaveChargeSequence.Outcome.TARGET_UNCONFIRMED, result.outcome);
        assertTrue(result.modeConfirmed);
        assertEquals(11, result.observed.level);
        assertEquals(3300, car.time);
        assertEquals(2, car.writes.size());
    }

    @Test public void noModeFeedbackNeverWritesTarget() {
        Car car = new Car(); car.modeResponds = false;
        assertEquals(SaveChargeSequence.Outcome.MODE_UNCONFIRMED, car.run(25).outcome);
        assertEquals(java.util.Collections.singletonList("mode"), car.writes);
        assertFalse(car.modeConfirmed); assertEquals(2000, car.time);
    }

    @Test public void unavailableStateAndRejectedWriteAreNotSuccess() {
        Car car = new Car(); car.available = false;
        assertEquals(SaveChargeSequence.Outcome.UNAVAILABLE, car.run(25).outcome);
        assertTrue(car.writes.isEmpty());
        car.available = true; car.writesAccepted = false;
        assertEquals(SaveChargeSequence.Outcome.SEND_FAILED, car.run(25).outcome);
        assertEquals(1, car.writes.size());
    }

    @Test public void cancellationBeforeStartAndBetweenWritesPreventsLaterWrites() {
        Car car = new Car(); car.cancelAt = 0;
        assertEquals(SaveChargeSequence.Outcome.CANCELLED, car.run(25).outcome);
        assertTrue(car.writes.isEmpty());
        car.cancelAt = 200;
        assertEquals(SaveChargeSequence.Outcome.CANCELLED, car.run(25).outcome);
        assertEquals(java.util.Collections.singletonList("mode"), car.writes);
        assertFalse(car.modeConfirmed);
    }

    @Test public void cancellationDuringTargetWaitDoesNotReportSuccess() {
        Car car = new Car(); car.cancelAt = 400;
        assertEquals(SaveChargeSequence.Outcome.CANCELLED, car.run(25).outcome);
        assertTrue(car.modeConfirmed); assertEquals(2, car.writes.size());
    }

    @Test public void modeChangeDuringTargetWaitIsNotSuccess() {
        Car car = new Car() {
            @Override public void sleep(long ms) {
                super.sleep(ms);
                if (time >= 400) { mode = 2; modeAt = Long.MAX_VALUE; }
            }
        };
        assertEquals(SaveChargeSequence.Outcome.MODE_UNCONFIRMED, car.run(25).outcome);
    }

    @Test public void invalidTargetNeverTouchesTheVehicle() {
        Car car = new Car();
        for (int percent : new int[]{0, 24, 26, 81, 100}) {
            try { car.run(percent); fail("Invalid target accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        assertTrue(car.writes.isEmpty());
    }
}
