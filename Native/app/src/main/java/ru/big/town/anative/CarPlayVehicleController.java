package ru.big.town.anative;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import ru.big.town.common.CarPlayVehicleProtocol;
import ru.big.town.common.MessengerSubscription;

/** Observes CAN only while the CarPlay session process owns a live subscription. */
final class CarPlayVehicleController implements AutoCloseable {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CanBusEventHub hub;
    private final CarPlayClusterNavigation navigation;
    private final CarPlayAudioController audio;
    private android.os.Messenger owner;
    private final MessengerSubscription client = new MessengerSubscription();
    private CanBusEventHub.Subscription subscription;
    private int speed = -1, gear = -1, reason = -1;
    private long speedTime = -1;
    private int ticks;
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
                    if (ticks++ % 10 == 0) hub.requestVehicleStateSnapshot();
                    handler.postDelayed(this, 1000);
                }
            };

    CarPlayVehicleController(Context context, CarPlayAudioController audio) {
        hub = CanBusEventHub.get(context);
        navigation = new CarPlayClusterNavigation(context);
        this.audio = audio;
    }

    void handle(Message message) {
        if (message.what == ru.big.town.common.CarPlayAudioProtocol.FOCUS) {
            if (owner != null && owner.equals(message.replyTo) && client.active())
                audio.setFocus(message.getData().getInt(ru.big.town.common.CarPlayAudioProtocol.STREAM, -1));
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
            audio.setFocus(ru.big.town.common.CarPlayAudioProtocol.NONE);
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
                            new int[] {1072},
                            handler,
                            this::onEvent);
            handler.post(poll);
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
                if (event.kind == CanBusEvent.Kind.CONNECTION) {
                    hub.requestSpeedSnapshot();
                    hub.requestGearSnapshot();
                    hub.requestVehicleStateSnapshot();
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
                reason = event.second;
                break;
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
    }

    @Override
    public void close() {
        handler.removeCallbacks(poll);
        client.clear();
        owner = null;
        navigation.clear();
        audio.setFocus(ru.big.town.common.CarPlayAudioProtocol.NONE);
        if (subscription != null) subscription.close();
        subscription = null;
        speed = gear = reason = -1;
        speedTime = -1;
    }
}
