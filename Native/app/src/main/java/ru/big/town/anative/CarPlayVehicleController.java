package ru.big.town.anative;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.big.town.common.CarPlayAudioProtocol;
import ru.big.town.common.CarPlayVehicleProtocol;
import ru.big.town.common.MessengerSubscription;

/** Observes CAN only while the CarPlay session process owns a live subscription. */
final class CarPlayVehicleController implements AutoCloseable {
    private static final OemVehicleStateTransport.StateKey LIGHT_REASON =
            new OemVehicleStateTransport.StateKey("BCM_RSM_lightSWReason", 1072);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService lightQueryExecutor =
            Executors.newSingleThreadExecutor(task -> new Thread(task, "CarPlayLightQuery"));
    private final CanBusEventHub hub;
    private final CarPlayClusterNavigation navigation;
    private final CarPlayAudioController audio;
    private final CarPlayLightState lightState;
    private Messenger owner;
    private final MessengerSubscription client = new MessengerSubscription();
    private CanBusEventHub.Subscription subscription;
    private int speed = -1;
    private int gear = -1;
    private int reason = -1;
    private long speedTime = -1;
    private final Runnable poll =
            new Runnable() {
                @Override
                public void run() {
                    if (!client.active()) {
                        close();
                        return;
                    }
                    hub.requestSpeedSnapshot();
                    hub.requestGearSnapshot();
                    handler.postDelayed(this, 1000);
                }
            };

    CarPlayVehicleController(Context context, CarPlayAudioController audio) {
        hub = CanBusEventHub.get(context);
        navigation = new CarPlayClusterNavigation(context);
        this.audio = audio;
        Context applicationContext = context.getApplicationContext();
        lightState = new CarPlayLightState(
                lightQueryExecutor,
                handler::post,
                () -> readLightReason(applicationContext),
                value -> {
                    reason = value;
                    publish();
                });
    }

    private static Integer readLightReason(Context context) {
        try {
            return OemVehicleStateTransport.withSession(
                    context,
                    Collections.singleton(LIGHT_REASON),
                    session -> session.readVehicleState(LIGHT_REASON));
        } catch (RuntimeException unavailable) {
            Log.w("CarPlayVehicle", "Initial light reason unavailable", unavailable);
            return null;
        }
    }

    void handle(Message message) {
        if (message.what == CarPlayAudioProtocol.FOCUS) {
            if (owner != null && owner.equals(message.replyTo) && client.active()) {
                audio.setFocus(message.getData().getInt(CarPlayAudioProtocol.STREAM, -1));
            }
            return;
        }
        if (message.what == CarPlayVehicleProtocol.NAVIGATION) {
            if (owner != null && owner.equals(message.replyTo) && client.active()) {
                navigation.update(message.getData());
            }
            return;
        }
        if (message.what == CarPlayVehicleProtocol.UNWATCH) {
            if (client.remove(message.replyTo)) close();
            return;
        }
        if (message.replyTo == null) return;
        if (!message.replyTo.equals(owner)) {
            navigation.clear();
            audio.setFocus(CarPlayAudioProtocol.NONE);
        }
        owner = message.replyTo;
        client.set(message.replyTo);
        if (subscription == null) {
            subscription =
                    hub.subscribe(
                            CanBusEventRouter.INTEREST_CONNECTION
                                    | CanBusEventRouter.INTEREST_GEAR
                                    | CanBusEventRouter.INTEREST_VEHICLE_SPEED
                                    | CanBusEventRouter.INTEREST_VEHICLE_STATE,
                            new int[] {LIGHT_REASON.stableId},
                            handler,
                            this::onEvent);
            handler.post(poll);
        } else {
            lightState.refresh();
        }
        publish();
    }

    private void onEvent(CanBusEvent event) {
        if (subscription == null) return;
        switch (event.kind) {
            case CONNECTION:
            case CONNECTION_LOST:
                speed = gear = reason = -1;
                speedTime = -1;
                lightState.reset();
                if (event.kind == CanBusEvent.Kind.CONNECTION) {
                    lightState.refresh();
                    hub.requestSpeedSnapshot();
                    hub.requestGearSnapshot();
                }
                break;
            case VEHICLE_SPEED:
                speed = event.first;
                speedTime = event.elapsedRealtime;
                break;
            case GEAR:
                gear = event.first;
                break;
            case VEHICLE_STATE:
                if (event.first == LIGHT_REASON.stableId) lightState.onEvent(event.second);
                return;
            default:
                return;
        }
        publish();
    }

    private void publish() {
        Bundle values = new Bundle();
        values.putInt(CarPlayVehicleProtocol.SPEED_KMH, speed);
        values.putLong(CarPlayVehicleProtocol.SPEED_TIME, speedTime);
        values.putInt(CarPlayVehicleProtocol.GEAR, gear);
        values.putInt(CarPlayVehicleProtocol.SW_REASON, reason);
        Message message = Message.obtain(null, CarPlayVehicleProtocol.STATE);
        message.setData(values);
        if (!client.send(message)) close();
    }

    void destroy() {
        close();
        navigation.destroy();
        lightQueryExecutor.shutdownNow();
    }

    @Override
    public void close() {
        handler.removeCallbacks(poll);
        client.clear();
        owner = null;
        lightState.reset();
        navigation.clear();
        audio.setFocus(CarPlayAudioProtocol.NONE);
        if (subscription != null) subscription.close();
        subscription = null;
        speed = gear = reason = -1;
        speedTime = -1;
    }
}
