package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

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
        fixture.tracker.acceptEvent(vehicle(4, PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID, 1));
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.ACTIVE);

        fixture.seedLoader.complete(0, 0, 0);
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.ACTIVE);
    }

    @Test
    public void acceptedRequestWaitsForLockAndStockCancellation() {
        Fixture fixture = connectedInactiveFixture();
        long generation = fixture.beginActivation();
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.ACTIVATING);

        fixture.tracker.finishActivation(generation, PowerHoldPolicy.Outcome.ACCEPTED);
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.REQUESTED);
        assertEquals(PowerHoldPolicy.Outcome.ACCEPTED, fixture.last().outcome);
        fixture.tracker.acceptOemStatus(2);
        fixture.tracker.acceptEvent(vehicle(1, PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID, 0));
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.ARMED);
        fixture.tracker.acceptOemStatus(0);
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.INACTIVE);
    }

    @Test
    public void delayedSwitchConfirmsActiveAndExitReason() {
        Fixture fixture = connectedInactiveFixture();
        long generation = fixture.beginActivation();
        fixture.tracker.finishActivation(generation, PowerHoldPolicy.Outcome.ACCEPTED);

        fixture.tracker.acceptEvent(vehicle(1, PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID, 1));
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.ACTIVE);

        fixture.tracker.acceptEvent(vehicle(1, PowerHoldPolicy.POWER_HOLD_MODE_WARNING_ID, 2));
        fixture.tracker.acceptEvent(vehicle(1, PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID, 0));
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.INACTIVE);
        assertEquals(PowerHoldStatusPolicy.ExitReason.TIME_UP, fixture.last().snapshot.exitReason);
    }

    @Test
    public void genericExitAndLateWarningAreReportedWithoutDuplicatePayloads() {
        Fixture fixture = connectedInactiveFixture();
        fixture.tracker.acceptEvent(vehicle(1, PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID, 1));
        fixture.tracker.acceptEvent(vehicle(1, PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID, 1));
        int afterActive = fixture.publications.size();
        fixture.tracker.acceptEvent(vehicle(1, PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID, 0));
        assertEquals(PowerHoldStatusPolicy.ExitReason.COMMON, fixture.last().snapshot.exitReason);
        fixture.tracker.acceptEvent(vehicle(1, PowerHoldPolicy.POWER_HOLD_MODE_WARNING_ID, 1));
        assertEquals(
                PowerHoldStatusPolicy.ExitReason.LOW_BATTERY, fixture.last().snapshot.exitReason);
        assertEquals(afterActive + 2, fixture.publications.size());
    }

    @Test
    public void connectionLossClearsArmingAndReconnectSeedsAgain() {
        Fixture fixture = connectedInactiveFixture();
        long generation = fixture.beginActivation();
        fixture.tracker.finishActivation(generation, PowerHoldPolicy.Outcome.ACCEPTED);

        fixture.tracker.acceptEvent(CanBusEvent.connectionLost(2, 10, 10, 1));
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.UNKNOWN);

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
    }

    @Test
    public void closeStopsSeedsAndFuturePublication() {
        Fixture fixture = connectedInactiveFixture();
        long generation = fixture.beginActivation();
        fixture.tracker.finishActivation(generation, PowerHoldPolicy.Outcome.ACCEPTED);
        int beforeClose = fixture.publications.size();

        fixture.tracker.close();
        fixture.tracker.close();
        fixture.tracker.acceptEvent(vehicle(1, PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID, 1));

        assertTrue(fixture.seedLoader.closed);
        assertTrue(fixture.closeAction.closed);
        assertEquals(beforeClose, fixture.publications.size());
    }

    @Test
    public void seedRestoresStockArmingBeforeSwitchConfirmation() {
        Fixture fixture = new Fixture();
        fixture.tracker.acceptEvent(connection(1));
        fixture.seedLoader.complete(0, 0, 0, 2);
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.ARMED);
    }

    @Test
    public void seedStartedBeforeRequestCannotClearArming() {
        Fixture fixture = new Fixture();
        fixture.tracker.acceptEvent(connection(1));
        long generation = fixture.beginActivation();
        fixture.tracker.finishActivation(generation, PowerHoldPolicy.Outcome.ARMED);
        fixture.seedLoader.complete(0, 0, 0, 0);
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.ARMED);
    }

    @Test
    public void oldCompletionDoesNotPublishSuccessForNewRequest() {
        Fixture fixture = connectedInactiveFixture();
        long first = fixture.beginActivation();
        fixture.beginActivation();
        int publications = fixture.publications.size();
        fixture.tracker.finishActivation(first, PowerHoldPolicy.Outcome.ARMED);
        assertEquals(publications, fixture.publications.size());
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.ACTIVATING);
    }

    @Test
    public void cancellationSuppressesLateArmedNotification() {
        Fixture fixture = connectedInactiveFixture();
        long generation = fixture.beginActivation();
        fixture.tracker.acceptOemStatus(2);
        fixture.tracker.acceptOemStatus(0);
        fixture.tracker.finishActivation(generation, PowerHoldPolicy.Outcome.ARMED);
        assertStatus(fixture.last(), PowerHoldStatusPolicy.Status.INACTIVE);
        org.junit.Assert.assertNull(fixture.last().outcome);
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
        return CanBusEvent.vehicleState(CanBusEvent.Origin.LIVE, epoch, id, id, id, value);
    }

    private static void assertStatus(Publication publication, PowerHoldStatusPolicy.Status status) {
        assertEquals(status, publication.snapshot.status);
    }

    private static final class Fixture {
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeSeedLoader seedLoader = new FakeSeedLoader();
        final FakeCloseAction closeAction = new FakeCloseAction();
        final List<Publication> publications = new ArrayList<>();
        final PowerHoldStatusTracker tracker =
                new PowerHoldStatusTracker(
                        scheduler,
                        seedLoader,
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

        Publication(
                PowerHoldStatusPolicy.Snapshot snapshot,
                PowerHoldPolicy.Outcome outcome,
                boolean force) {
            this.snapshot = snapshot;
            this.outcome = outcome;
            this.force = force;
        }
    }

    private static final class FakeScheduler implements PowerHoldStatusTracker.Scheduler {
        @Override
        public boolean post(Runnable runnable) {
            runnable.run();
            return true;
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
            complete(index, switchValue, warningValue, null);
        }

        void complete(int index, Integer switchValue, Integer warningValue, Integer oemStatus) {
            callbacks.get(index).onResult(epochs.get(index), switchValue, warningValue, oemStatus);
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
