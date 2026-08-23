package ru.big.town.anative;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CanBusEventRouterTest {
    @Test
    public void routesOnlyRequestedKindsAndVehicleIds() {
        CanBusEventRouter router = new CanBusEventRouter();
        ManualExecutor executor = new ManualExecutor();
        List<CanBusEvent> delivered = new ArrayList<>();
        router.subscribe(CanBusEventRouter.INTEREST_DOOR
                        | CanBusEventRouter.INTEREST_VEHICLE_STATE,
                new int[]{545}, executor, delivered::add);

        router.dispatch(door(1, 1));
        router.dispatch(gear(2, 3));
        router.dispatch(vehicle(3, 957, 2));
        router.dispatch(vehicle(4, 545, 5));

        assertEquals(1, executor.size());
        executor.runAll();
        assertEquals(2, delivered.size());
        assertEquals(CanBusEvent.Kind.DOOR, delivered.get(0).kind);
        assertEquals(545, delivered.get(1).first);
    }

    @Test
    public void orderedTransitionsArePreservedWithOnePendingTask() {
        CanBusEventRouter router = new CanBusEventRouter();
        ManualExecutor executor = new ManualExecutor();
        List<Integer> gears = new ArrayList<>();
        router.subscribe(CanBusEventRouter.INTEREST_GEAR, null, executor,
                event -> gears.add(event.first));

        router.dispatch(gear(1, 0));
        router.dispatch(gear(2, 3));
        router.dispatch(gear(3, 0));
        router.dispatch(gear(4, 0));

        assertEquals(1, executor.size());
        executor.runAll();
        assertEquals(Arrays.asList(0, 3, 0), gears);
    }

    @Test
    public void connectionIsDeliveredAgainForANewEpoch() {
        CanBusEventRouter router = new CanBusEventRouter();
        ManualExecutor executor = new ManualExecutor();
        List<Long> epochs = new ArrayList<>();
        router.subscribe(CanBusEventRouter.INTEREST_CONNECTION, null, executor,
                event -> epochs.add(event.connectionEpoch));

        router.dispatch(CanBusEvent.connection(1, 1, 1));
        router.dispatch(CanBusEvent.connection(1, 2, 2));
        executor.runAll();
        router.dispatch(CanBusEvent.connection(2, 3, 3));
        executor.runAll();
        assertEquals(Arrays.asList(1L, 2L), epochs);
    }

    @Test
    public void levelSignalsKeepLatestValuePerKey() {
        CanBusEventRouter router = new CanBusEventRouter();
        ManualExecutor executor = new ManualExecutor();
        List<CanBusEvent> delivered = new ArrayList<>();
        router.subscribe(CanBusEventRouter.INTEREST_VEHICLE_STATE,
                new int[]{545, 957}, executor, delivered::add);

        router.dispatch(vehicle(1, 545, 1));
        router.dispatch(vehicle(2, 957, 2));
        router.dispatch(vehicle(3, 545, 5));

        assertEquals(1, executor.size());
        executor.runAll();
        assertEquals(2, delivered.size());
        assertEquals(957, delivered.get(0).first);
        assertEquals(545, delivered.get(1).first);
        assertEquals(5, delivered.get(1).second);
    }

    @Test
    public void closeDropsQueuedAndFutureEvents() {
        CanBusEventRouter router = new CanBusEventRouter();
        ManualExecutor executor = new ManualExecutor();
        List<CanBusEvent> delivered = new ArrayList<>();
        CanBusEventRouter.Subscription subscription = router.subscribe(
                CanBusEventRouter.INTEREST_DOOR, null, executor, delivered::add);

        router.dispatch(door(1, 1));
        subscription.close();
        subscription.close();
        router.dispatch(door(2, 0));
        executor.runAll();

        assertTrue(delivered.isEmpty());
        assertEquals(0, router.subscriberCount());
    }

    @Test
    public void rejectedExecutorCanBeScheduledAgain() {
        CanBusEventRouter router = new CanBusEventRouter();
        RejectOnceExecutor executor = new RejectOnceExecutor();
        List<CanBusEvent> delivered = new ArrayList<>();
        router.subscribe(CanBusEventRouter.INTEREST_DOOR, null, executor, delivered::add);

        router.dispatch(door(1, 1));
        assertTrue(delivered.isEmpty());
        router.dispatch(door(2, 1)); // duplicate retries scheduling of the queued edge
        executor.runAll();

        assertEquals(1, delivered.size());
        assertEquals(1, delivered.get(0).first);
    }

    @Test
    public void boundedMailboxDropsOldestTransition() {
        CanBusEventRouter router = new CanBusEventRouter();
        ManualExecutor executor = new ManualExecutor();
        List<Integer> doors = new ArrayList<>();
        router.subscribe(CanBusEventRouter.INTEREST_DOOR, null, executor,
                event -> doors.add(event.first), 2);

        router.dispatch(door(1, 0));
        router.dispatch(door(2, 1));
        router.dispatch(door(3, 0));
        executor.runAll();

        assertEquals(Arrays.asList(1, 0), doors);
    }

    @Test
    public void listenerFailureDoesNotStopMailbox() {
        CanBusEventRouter router = new CanBusEventRouter();
        ManualExecutor executor = new ManualExecutor();
        List<Integer> delivered = new ArrayList<>();
        router.subscribe(CanBusEventRouter.INTEREST_GEAR, null, executor, event -> {
            if (event.first == 0) throw new IllegalStateException("boom");
            delivered.add(event.first);
        });

        router.dispatch(gear(1, 0));
        router.dispatch(gear(2, 3));
        executor.runAll();
        assertEquals(Arrays.asList(3), delivered);
    }

    private static CanBusEvent door(long sequence, int value) {
        return CanBusEvent.door(CanBusEvent.Origin.LIVE, 1, sequence, sequence, value);
    }

    private static CanBusEvent gear(long sequence, int value) {
        return CanBusEvent.gear(CanBusEvent.Origin.LIVE, 1, sequence, sequence, value);
    }

    private static CanBusEvent vehicle(long sequence, int id, int value) {
        return CanBusEvent.vehicleState(
                CanBusEvent.Origin.LIVE, 1, sequence, sequence, id, value);
    }

    private static class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        int size() {
            return tasks.size();
        }

        void runAll() {
            while (!tasks.isEmpty()) tasks.removeFirst().run();
        }
    }

    private static final class RejectOnceExecutor extends ManualExecutor {
        private boolean rejected;

        @Override
        public void execute(Runnable command) {
            if (!rejected) {
                rejected = true;
                throw new IllegalStateException("rejected");
            }
            super.execute(command);
        }
    }
}
