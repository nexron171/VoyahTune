package ru.big.town.anative;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.Arrays;
import java.util.Map;

/**
 * Tracks exact Power Hold feedback through the shared CanBus callback.
 *
 * <p>All state transitions run through one serial scheduler. Snapshot reads use a separate IO
 * thread, but their completion is fenced by connection epoch and live-event revision before it is
 * applied on the serial scheduler.</p>
 */
final class PowerHoldStatusTracker implements AutoCloseable {
    private static final String TAG = "PowerHoldStatus";
    static final long ACTIVATION_TIMEOUT_MS = 10_000L;

    private static final OemVehicleStateTransport.StateKey SWITCH_KEY =
            new OemVehicleStateTransport.StateKey(
                    PowerHoldPolicy.POWER_HOLD_MODE_SWITCH,
                    PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID);
    private static final OemVehicleStateTransport.StateKey WARNING_KEY =
            new OemVehicleStateTransport.StateKey(
                    PowerHoldPolicy.POWER_HOLD_MODE_WARNING,
                    PowerHoldPolicy.POWER_HOLD_MODE_WARNING_ID);

    interface Scheduler {
        boolean post(Runnable runnable);

        boolean postDelayed(Runnable runnable, long delayMs);

        void removeCallbacks(Runnable runnable);
    }

    interface SeedCallback {
        void onResult(long epoch, Integer switchValue, Integer warningValue);
    }

    interface SeedLoader extends AutoCloseable {
        void load(long epoch, SeedCallback callback);

        @Override
        void close();
    }

    interface StatusListener {
        void onStatus(PowerHoldStatusPolicy.Snapshot snapshot,
                      PowerHoldPolicy.Outcome requestOutcome, boolean force);
    }

    interface ActivationReady {
        void onReady(long requestGeneration);
    }

    interface CloseAction {
        void close();
    }

    private final Scheduler scheduler;
    private final SeedLoader seedLoader;
    private final StatusListener listener;
    private final CloseAction closeAction;
    private final PowerHoldStatusPolicy.Machine machine =
            new PowerHoldStatusPolicy.Machine();

    private CanBusEventHub.Subscription subscription;
    private PowerHoldStatusPolicy.Snapshot lastPublished;
    private Runnable activationTimeout;
    private long activeEpoch;
    private long liveRevision;
    private volatile boolean closed;

    static PowerHoldStatusTracker create(Context context, StatusListener listener) {
        Context app = context.getApplicationContext();
        HandlerThread statusThread = new HandlerThread("PowerHoldStatus");
        HandlerThread seedThread = new HandlerThread("PowerHoldSeed");
        statusThread.start();
        seedThread.start();
        Handler statusHandler = new Handler(statusThread.getLooper());
        Handler seedHandler = new Handler(seedThread.getLooper());
        Scheduler scheduler = new Scheduler() {
            @Override
            public boolean post(Runnable runnable) {
                return statusHandler.post(runnable);
            }

            @Override
            public boolean postDelayed(Runnable runnable, long delayMs) {
                return statusHandler.postDelayed(runnable, delayMs);
            }

            @Override
            public void removeCallbacks(Runnable runnable) {
                statusHandler.removeCallbacks(runnable);
            }
        };
        SeedLoader seedLoader = new SeedLoader() {
            private volatile boolean stopped;

            @Override
            public void load(long epoch, SeedCallback callback) {
                if (stopped) return;
                if (!seedHandler.post(() -> {
                    Map<OemVehicleStateTransport.StateKey, Integer> values =
                            OemVehicleStateTransport.readVehicleStates(
                                    app, Arrays.asList(SWITCH_KEY, WARNING_KEY));
                    Integer switchValue = values == null ? null : values.get(SWITCH_KEY);
                    Integer warningValue = values == null ? null : values.get(WARNING_KEY);
                    callback.onResult(epoch, switchValue, warningValue);
                })) {
                    Log.w(TAG, "Power Hold seed was not queued");
                }
            }

            @Override
            public void close() {
                stopped = true;
                seedHandler.removeCallbacksAndMessages(null);
            }
        };
        PowerHoldStatusTracker tracker = new PowerHoldStatusTracker(
                scheduler, seedLoader, listener, () -> {
                    statusHandler.removeCallbacksAndMessages(null);
                    statusThread.quitSafely();
                    seedThread.quitSafely();
                });
        tracker.subscription = CanBusEventHub.get(app).subscribe(
                CanBusEventRouter.INTEREST_CONNECTION
                        | CanBusEventRouter.INTEREST_VEHICLE_STATE,
                new int[]{PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID,
                        PowerHoldPolicy.POWER_HOLD_MODE_WARNING_ID},
                statusHandler, tracker::acceptEventOnSerial);
        return tracker;
    }

    PowerHoldStatusTracker(Scheduler scheduler, SeedLoader seedLoader,
                           StatusListener listener, CloseAction closeAction) {
        if (scheduler == null || seedLoader == null || listener == null
                || closeAction == null) {
            throw new IllegalArgumentException("Power Hold tracker dependency is null");
        }
        this.scheduler = scheduler;
        this.seedLoader = seedLoader;
        this.listener = listener;
        this.closeAction = closeAction;
    }

    void acceptEvent(CanBusEvent event) {
        if (event == null || closed) return;
        scheduler.post(() -> acceptEventOnSerial(event));
    }

    private void acceptEventOnSerial(CanBusEvent event) {
        if (closed) return;
        switch (event.kind) {
            case CONNECTION:
                onConnection(event.connectionEpoch);
                break;
            case CONNECTION_LOST:
                onConnectionLost();
                break;
            case VEHICLE_STATE:
                if (event.connectionEpoch != activeEpoch) return;
                liveRevision++;
                if (event.first == PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID) {
                    publishIfChanged(machine.onSwitch(activeEpoch, event.second), null, false);
                    cancelTimeoutUnlessActivating();
                } else if (event.first == PowerHoldPolicy.POWER_HOLD_MODE_WARNING_ID) {
                    publishIfChanged(machine.onWarning(activeEpoch, event.second), null, false);
                }
                break;
            default:
                break;
        }
    }

    private void onConnection(long epoch) {
        if (epoch <= 0 || epoch == activeEpoch) return;
        activeEpoch = epoch;
        liveRevision = 0;
        cancelActivationTimeout();
        publishIfChanged(machine.onConnection(epoch), null, false);
        long seedRevision = liveRevision;
        seedLoader.load(epoch, (seedEpoch, switchValue, warningValue) ->
                scheduler.post(() -> finishSeed(
                        seedEpoch, seedRevision, switchValue, warningValue)));
    }

    private void finishSeed(long epoch, long seedRevision,
                            Integer switchValue, Integer warningValue) {
        if (closed || epoch != activeEpoch || seedRevision != liveRevision) return;
        if (warningValue != null) machine.onWarning(epoch, warningValue);
        if (switchValue != null) {
            publishIfChanged(machine.onSwitch(epoch, switchValue), null, false);
        }
    }

    private void onConnectionLost() {
        long closingEpoch = activeEpoch;
        activeEpoch = 0;
        liveRevision++;
        cancelActivationTimeout();
        publishIfChanged(machine.onConnectionLost(closingEpoch), null, false);
    }

    void beginActivation(ActivationReady ready) {
        if (ready == null || closed) return;
        scheduler.post(() -> {
            if (closed) return;
            cancelActivationTimeout();
            long generation = machine.beginActivation();
            publishIfChanged(machine.snapshot(), null, false);
            ready.onReady(generation);
        });
    }

    void finishActivation(long generation, PowerHoldPolicy.Outcome outcome) {
        if (outcome == null) outcome = PowerHoldPolicy.Outcome.TRANSPORT_FAILURE;
        PowerHoldPolicy.Outcome terminal = outcome;
        scheduler.post(() -> {
            if (closed) return;
            PowerHoldStatusPolicy.Snapshot snapshot =
                    machine.finishActivation(generation, terminal);
            publishIfChanged(snapshot, terminal, true);
            if (terminal == PowerHoldPolicy.Outcome.ACCEPTED
                    && snapshot.status == PowerHoldStatusPolicy.Status.ACTIVATING
                    && snapshot.requestGeneration == generation) {
                scheduleActivationTimeout(generation);
            } else {
                cancelActivationTimeout();
            }
        });
    }

    void requestCurrentStatus() {
        scheduler.post(() -> {
            if (!closed) publishIfChanged(machine.snapshot(), null, true);
        });
    }

    private void scheduleActivationTimeout(long generation) {
        cancelActivationTimeout();
        Runnable timeout = () -> {
            if (closed || activationTimeout == null) return;
            activationTimeout = null;
            publishIfChanged(machine.onActivationTimeout(generation), null, false);
        };
        activationTimeout = timeout;
        if (!scheduler.postDelayed(timeout, ACTIVATION_TIMEOUT_MS)) {
            activationTimeout = null;
            publishIfChanged(machine.onActivationTimeout(generation), null, false);
        }
    }

    private void cancelTimeoutUnlessActivating() {
        if (machine.snapshot().status != PowerHoldStatusPolicy.Status.ACTIVATING) {
            cancelActivationTimeout();
        }
    }

    private void cancelActivationTimeout() {
        Runnable timeout = activationTimeout;
        activationTimeout = null;
        if (timeout != null) scheduler.removeCallbacks(timeout);
    }

    private void publishIfChanged(PowerHoldStatusPolicy.Snapshot snapshot,
                                  PowerHoldPolicy.Outcome requestOutcome, boolean force) {
        if (closed) return;
        if (!force && requestOutcome == null && snapshot.equals(lastPublished)) return;
        lastPublished = snapshot;
        listener.onStatus(snapshot, requestOutcome, force);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        CanBusEventHub.Subscription current = subscription;
        subscription = null;
        if (current != null) current.close();
        cancelActivationTimeout();
        seedLoader.close();
        closeAction.close();
    }
}
