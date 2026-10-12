package ru.big.town.restoremode.carplay;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import java.lang.ref.WeakReference;
import com.shilapi.xcertplay.VoyahCarPlayIntegration;
import ru.big.town.common.CarPlayVehicleProtocol;

/** A bounded UI request, independent from the vehicle/session subscription. */
final class CarPlayProjectionClient {
    private final Context context;
    private final WeakReference<Activity> owner;
    private final int displayId;
    private final VoyahCarPlayIntegration.ProjectionResult result;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean bound, finished;
    private final Runnable timeout = () -> finish(false);
    private final Messenger replies;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (finished) return;
            Activity activity = owner.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()
                    || activityDisplay(activity) != displayId) {
                finish(false);
                return;
            }
            Message request = Message.obtain(null, CarPlayVehicleProtocol.OPEN_PROJECTION);
            Bundle values = new Bundle();
            values.putInt(CarPlayVehicleProtocol.DISPLAY_ID, displayId);
            request.setData(values);
            request.replyTo = replies;
            try { new Messenger(binder).send(request); }
            catch (RemoteException | RuntimeException denied) { finish(false); }
        }
        @Override public void onServiceDisconnected(ComponentName name) { finish(false); }
        @Override public void onBindingDied(ComponentName name) { finish(false); }
        @Override public void onNullBinding(ComponentName name) { finish(false); }
    };

    CarPlayProjectionClient(Activity activity, int displayId,
            VoyahCarPlayIntegration.ProjectionResult result) {
        context = activity.getApplicationContext();
        owner = new WeakReference<>(activity);
        this.displayId = displayId;
        this.result = result;
        replies = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (message.what == CarPlayVehicleProtocol.PROJECTION_RESULT
                    && message.getData().getInt(CarPlayVehicleProtocol.DISPLAY_ID, -1) == displayId) {
                finish(message.getData().getBoolean(CarPlayVehicleProtocol.LAUNCHED));
            }
            return true;
        }));
    }

    private static int activityDisplay(Activity activity) {
        android.view.Display display = activity.getWindow().getDecorView().getDisplay();
        return display != null ? display.getDisplayId()
                : activity.getWindowManager().getDefaultDisplay().getDisplayId();
    }

    void start() {
        handler.postDelayed(timeout, 5000);
        try {
            bound = context.bindService(new Intent().setClassName(
                    "ru.big.town.anative", "ru.big.town.anative.SetModesService"),
                    connection, Context.BIND_AUTO_CREATE);
            if (finished) releaseBinding();
            else if (!bound) finish(false);
        } catch (RuntimeException denied) { finish(false); }
    }

    private void finish(boolean success) {
        if (finished) return;
        finished = true;
        handler.removeCallbacks(timeout);
        releaseBinding();
        result.complete(success);
    }

    private void releaseBinding() {
        if (!bound) return;
        try { context.unbindService(connection); } catch (IllegalArgumentException ignored) {}
        bound = false;
    }
}
