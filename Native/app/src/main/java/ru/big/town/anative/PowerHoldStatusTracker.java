package ru.big.town.anative;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.core.content.ContextCompat;

import ru.big.town.common.VehiclePlatform;

import java.util.Arrays;
import java.util.Map;

/**
 * Tracks exact Power Hold feedback through the shared CanBus callback.
 *
 * <p>All state transitions run through one serial scheduler. Snapshot reads use a separate IO
 * thread, but their completion is fenced by connection epoch and live-event revision before it is
 * applied on the serial scheduler.
 */
final class PowerHoldStatusTracker implements AutoCloseable {
    private static final String TAG = "PowerHoldStatus";

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
    }

    interface SeedCallback {
        void onResult(long epoch, Integer switchValue, Integer warningValue, Integer oemStatus);
    }

    interface SeedLoader extends AutoCloseable {
        void load(long epoch, SeedCallback callback);

        @Override
        void close();
    }

    interface StatusListener {
        void onStatus(
                PowerHoldStatusPolicy.Snapshot snapshot,
                PowerHoldPolicy.Outcome requestOutcome,
                boolean force);
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
    private final PowerHoldStatusPolicy.Machine machine = new PowerHoldStatusPolicy.Machine();

    private CanBusEventHub.Subscription subscription;
    private PowerHoldStatusPolicy.Snapshot lastPublished;
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
        boolean stockManager = VehiclePlatform.current().isH97XY();
        PowerHoldOemTransport oemTransport = stockManager ? new PowerHoldOemTransport(app) : null;
        Scheduler scheduler = statusHandler::post;
        SeedLoader seedLoader =
                new SeedLoader() {
                    private volatile boolean stopped;

                    @Override
                    public void load(long epoch, SeedCallback callback) {
                        if (stopped) return;
                        if (!seedHandler.post(
                                () -> {
                                    Map<OemVehicleStateTransport.StateKey, Integer> values =
                                            OemVehicleStateTransport.readVehicleStates(
                                                    app, Arrays.asList(SWITCH_KEY, WARNING_KEY));
                                    Integer switchValue =
                                            values == null ? null : values.get(SWITCH_KEY);
                                    Integer warningValue =
                                            values == null ? null : values.get(WARNING_KEY);
                                    Integer oemStatus =
                                            oemTransport == null ? null : oemTransport.readStatus();
                                    if (stockManager
                                            && oemStatus == null
                                            && Integer.valueOf(0).equals(switchValue)) {
                                        switchValue = null;
                                    }
                                    callback.onResult(epoch, switchValue, warningValue, oemStatus);
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
        BroadcastReceiver[] oemReceiver = new BroadcastReceiver[1];
        PowerHoldStatusTracker tracker =
                new PowerHoldStatusTracker(
                        scheduler,
                        seedLoader,
                        listener,
                        () -> {
                            if (oemReceiver[0] != null) app.unregisterReceiver(oemReceiver[0]);
                            statusHandler.removeCallbacksAndMessages(null);
                            statusThread.quitSafely();
                            seedThread.quitSafely();
                        });
        if (stockManager) {
            oemReceiver[0] =
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context receiverContext, Intent intent) {
                            if (intent == null
                                    || !PowerHoldOemTransport.ACTION_STATUS.equals(
                                            intent.getAction())) return;
                            tracker.acceptOemStatusOnSerial(
                                    intent.getIntExtra(PowerHoldOemTransport.EXTRA_STATUS, -1));
                        }
                    };
            ContextCompat.registerReceiver(
                    app,
                    oemReceiver[0],
                    new IntentFilter(PowerHoldOemTransport.ACTION_STATUS),
                    PowerHoldOemTransport.OEM_PERMISSION,
                    statusHandler,
                    ContextCompat.RECEIVER_EXPORTED);
        }
        tracker.subscription =
                CanBusEventHub.get(app)
                        .subscribe(
                                CanBusEventRouter.INTEREST_CONNECTION
                                        | CanBusEventRouter.INTEREST_VEHICLE_STATE,
                                new int[] {
                                    PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID,
                                    PowerHoldPolicy.POWER_HOLD_MODE_WARNING_ID
                                },
                                statusHandler,
                                tracker::acceptEventOnSerial);
        return tracker;
    }

    PowerHoldStatusTracker(
            Scheduler scheduler,
            SeedLoader seedLoader,
            StatusListener listener,
            CloseAction closeAction) {
        if (scheduler == null || seedLoader == null || listener == null || closeAction == null) {
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
        publishIfChanged(machine.onConnection(epoch), null, false);
        loadSeed(epoch);
    }

    private void loadSeed(long epoch) {
        long seedRevision = liveRevision;
        seedLoader.load(
                epoch,
                (seedEpoch, switchValue, warningValue, oemStatus) ->
                        scheduler.post(
                                () ->
                                        finishSeed(
                                                seedEpoch,
                                                seedRevision,
                                                switchValue,
                                                warningValue,
                                                oemStatus)));
    }

    private void finishSeed(
            long epoch,
            long seedRevision,
            Integer switchValue,
            Integer warningValue,
            Integer oemStatus) {
        if (closed || epoch != activeEpoch || seedRevision != liveRevision) return;
        if (warningValue != null) machine.onWarning(epoch, warningValue);
        if (switchValue != null) {
            machine.onSwitch(epoch, switchValue);
        }
        if (oemStatus != null) machine.onOemStatus(oemStatus);
        publishIfChanged(machine.snapshot(), null, false);
    }

    void acceptOemStatus(int status) {
        scheduler.post(() -> acceptOemStatusOnSerial(status));
    }

    private void acceptOemStatusOnSerial(int status) {
        if (closed || activeEpoch <= 0 || status < 0 || status > 2) return;
        liveRevision++;
        publishIfChanged(machine.onOemStatus(status), null, false);
    }

    private void onConnectionLost() {
        long closingEpoch = activeEpoch;
        activeEpoch = 0;
        liveRevision++;
        publishIfChanged(machine.onConnectionLost(closingEpoch), null, false);
    }

    void beginActivation(ActivationReady ready) {
        if (ready == null || closed) return;
        scheduler.post(
                () -> {
                    if (closed) return;
                    liveRevision++;
                    long generation = machine.beginActivation();
                    publishIfChanged(machine.snapshot(), null, false);
                    ready.onReady(generation);
                });
    }

    void finishActivation(long generation, PowerHoldPolicy.Outcome outcome) {
        if (outcome == null) outcome = PowerHoldPolicy.Outcome.TRANSPORT_FAILURE;
        PowerHoldPolicy.Outcome terminal = outcome;
        scheduler.post(
                () -> {
                    if (closed) return;
                    if (generation != machine.snapshot().requestGeneration) return;
                    liveRevision++;
                    PowerHoldStatusPolicy.Snapshot snapshot =
                            machine.finishActivation(generation, terminal);
                    PowerHoldPolicy.Outcome visibleOutcome = terminal;
                    if (terminal.isAccepted()
                            && (snapshot.status == PowerHoldStatusPolicy.Status.INACTIVE
                                    || snapshot.status == PowerHoldStatusPolicy.Status.UNKNOWN)) {
                        visibleOutcome = null;
                    }
                    publishIfChanged(snapshot, visibleOutcome, true);
                });
    }

    void requestCurrentStatus() {
        scheduler.post(
                () -> {
                    if (closed) return;
                    publishIfChanged(machine.snapshot(), null, true);
                    if (activeEpoch > 0) loadSeed(activeEpoch);
                });
    }

    private void publishIfChanged(
            PowerHoldStatusPolicy.Snapshot snapshot,
            PowerHoldPolicy.Outcome requestOutcome,
            boolean force) {
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
        seedLoader.close();
        closeAction.close();
    }
}
