package ru.big.town.anative;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PowerHoldStatusTrackerTest {
    @Test
    public void seedPublishesInactiveAndRunsOncePerConnectionEpoch() {
        Fixture fixture = new Fixture();

        fixture.tracker.acceptEvent(connection(1));
        assertEquals(1, fixture.seedLoader.loadCount);
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.UNKNOWN);

        fixture.seedLoader.complete(0, 0, 0);
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.INACTIVE);

        fixture.tracker.acceptEvent(connection(1));
        assertEquals(1, fixture.seedLoader.loadCount);
        fixture.tracker.acceptEvent(connection(2));
        assertEquals(2, fixture.seedLoader.loadCount);
    }

    @Test
    public void liveSwitchDiscardsOlderSeedCompletion() {
        Fixture fixture = new Fixture();
        fixture.tracker.acceptEvent(connection(4));
        fixture.tracker.acceptEvent(vehicle(4,
                PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID, 1));
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.ACTIVE);

        fixture.seedLoader.complete(0, 0, 0);
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.ACTIVE);
    }

    @Test
    public void acceptedRequestWaitsForFeedbackThenTimesOutExactlyOnce() {
        Fixture fixture = connectedInactiveFixture();
        long generation = fixture.beginActivation();
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.ACTIVATING);

        fixture.tracker.finishActivation(generation, PowerHoldPolicy.Outcome.ACCEPTED);
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.ACTIVATING);
        assertEquals(PowerHoldPolicy.Outcome.ACCEPTED, fixture.last().outcome);
        assertEquals(1, fixture.scheduler.delayedCount());

        fixture.scheduler.fireDelayed();
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.FAILED);
        assertEquals(0, fixture.scheduler.delayedCount());
        fixture.scheduler.fireDelayed();
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.FAILED);
    }

    @Test
    public void lateFeedbackAfterTimeoutStillConfirmsActiveAndExitReason() {
        Fixture fixture = connectedInactiveFixture();
        long generation = fixture.beginActivation();
        fixture.tracker.finishActivation(generation, PowerHoldPolicy.Outcome.ACCEPTED);
        fixture.scheduler.fireDelayed();

        fixture.tracker.acceptEvent(vehicle(1,
                PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID, 1));
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.ACTIVE);

        fixture.tracker.acceptEvent(vehicle(1,
                PowerHoldPolicy.POWER_HOLD_MODE_WARNING_ID, 2));
        fixture.tracker.acceptEvent(vehicle(1,
                PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID, 0));
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.INACTIVE);
        assertEquals(PowerHoldStatusPolicy.ExitReason.TIME_UP,
                fixture.last().snapshot.exitReason);
    }

    @Test
    public void genericExitAndLateWarningAreReportedWithoutDuplicatePayloads() {
        Fixture fixture = connectedInactiveFixture();
        fixture.tracker.acceptEvent(vehicle(1,
                PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID, 1));
        fixture.tracker.acceptEvent(vehicle(1,
                PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID, 1));
        int afterActive = fixture.publications.size();
        fixture.tracker.acceptEvent(vehicle(1,
                PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID, 0));
        assertEquals(PowerHoldStatusPolicy.ExitReason.COMMON,
                fixture.last().snapshot.exitReason);
        fixture.tracker.acceptEvent(vehicle(1,
                PowerHoldPolicy.POWER_HOLD_MODE_WARNING_ID, 1));
        assertEquals(PowerHoldStatusPolicy.ExitReason.LOW_BATTERY,
                fixture.last().snapshot.exitReason);
        assertEquals(afterActive + 2, fixture.publications.size());
    }

    @Test
    public void connectionLossCancelsTimeoutAndReconnectSeedsAgain() {
        Fixture fixture = connectedInactiveFixture();
        long generation = fixture.beginActivation();
        fixture.tracker.finishActivation(generation, PowerHoldPolicy.Outcome.ACCEPTED);
        assertEquals(1, fixture.scheduler.delayedCount());

        fixture.tracker.acceptEvent(CanBusEvent.connectionLost(2, 10, 10, 1));
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.UNKNOWN);
        assertEquals(0, fixture.scheduler.delayedCount());

        fixture.tracker.acceptEvent(connection(3));
        assertEquals(2, fixture.seedLoader.loadCount);
        fixture.seedLoader.complete(1, 1, 0);
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.ACTIVE);
    }

    @Test
    public void failedPreconditionPublishesOutcomeAndRestoresInactive() {
        Fixture fixture = connectedInactiveFixture();
        long generation = fixture.beginActivation();
        fixture.tracker.finishActivation(generation, PowerHoldPolicy.Outcome.NOT_IN_PARK);

        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.INACTIVE);
        assertEquals(PowerHoldPolicy.Outcome.NOT_IN_PARK, fixture.last().outcome);
        assertEquals(0, fixture.scheduler.delayedCount());
    }

    @Test
    public void closeStopsSeedsTimersAndFuturePublication() {
        Fixture fixture = connectedInactiveFixture();
        long generation = fixture.beginActivation();
        fixture.tracker.finishActivation(generation, PowerHoldPolicy.Outcome.ACCEPTED);
        int beforeClose = fixture.publications.size();

        fixture.tracker.close();
        fixture.tracker.close();
        fixture.tracker.acceptEvent(vehicle(1,
                PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID, 1));
        fixture.scheduler.fireDelayed();

        assertTrue(fixture.seedLoader.closed);
        assertTrue(fixture.closeAction.closed);
        assertEquals(beforeClose, fixture.publications.size());
    }

    private static Fixture connectedInactiveFixture() {
        Fixture fixture = new Fixture();
        fixture.tracker.acceptEvent(connection(1));
        fixture.seedLoader.complete(0, 0, 0);
        return fixture;
    }

    private static CanBusEvent connection(long epoch) {
        return CanBusEvent.connection(epoch, epoch, epoch);
    }

    private static CanBusEvent vehicle(long epoch, int id, int value) {
        return CanBusEvent.vehicleState(
                CanBusEvent.Origin.LIVE, epoch, id, id, id, value);
    }

    private static void assertStatus(Publication publication,
                                     PowerHoldStatusPolicy.Status status) {
        assertEquals(status, publication.snapshot.status);
    }

    private static final class Fixture {
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeSeedLoader seedLoader = new FakeSeedLoader();
        final FakeCloseAction closeAction = new FakeCloseAction();
        final List<Publication> publications = new ArrayList<>();
        final PowerHoldStatusTracker tracker = new PowerHoldStatusTracker(
                scheduler, seedLoader,
                (snapshot, outcome, force) ->
                        publications.add(new Publication(snapshot, outcome, force)),
                closeAction);

        long beginActivation() {
            final long[] generation = {0};
            tracker.beginActivation(value -> generation[0] = value);
            assertTrue(generation[0] > 0);
            return generation[0];
        }

        Publication last() {
            assertFalse(publications.isEmpty());
            return publications.get(publications.size() - 1);
        }
    }

    private static final class Publication {
        final PowerHoldStatusPolicy.Snapshot snapshot;
        final PowerHoldPolicy.Outcome outcome;
        final boolean force;

        Publication(PowerHoldStatusPolicy.Snapshot snapshot,
                    PowerHoldPolicy.Outcome outcome, boolean force) {
            this.snapshot = snapshot;
            this.outcome = outcome;
            this.force = force;
        }
    }

    private static final class FakeScheduler implements PowerHoldStatusTracker.Scheduler {
        private Runnable delayed;

        @Override
        public boolean post(Runnable runnable) {
            runnable.run();
            return true;
        }

        @Override
        public boolean postDelayed(Runnable runnable, long delayMs) {
            assertEquals(PowerHoldStatusTracker.ACTIVATION_TIMEOUT_MS, delayMs);
            assertNull(delayed);
            delayed = runnable;
            return true;
        }

        @Override
        public void removeCallbacks(Runnable runnable) {
            if (delayed == runnable) delayed = null;
        }

        int delayedCount() {
            return delayed == null ? 0 : 1;
        }

        void fireDelayed() {
            Runnable current = delayed;
            delayed = null;
            if (current != null) current.run();
        }
    }

    private static final class FakeSeedLoader implements PowerHoldStatusTracker.SeedLoader {
        final List<Long> epochs = new ArrayList<>();
        final List<PowerHoldStatusTracker.SeedCallback> callbacks = new ArrayList<>();
        int loadCount;
        boolean closed;

        @Override
        public void load(long epoch, PowerHoldStatusTracker.SeedCallback callback) {
            loadCount++;
            epochs.add(epoch);
            callbacks.add(callback);
        }

        void complete(int index, Integer switchValue, Integer warningValue) {
            callbacks.get(index).onResult(epochs.get(index), switchValue, warningValue);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeCloseAction implements PowerHoldStatusTracker.CloseAction {
        boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }
}
