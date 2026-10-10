package ru.big.town.restoremode.carplay;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.widget.Toast;

import ru.big.town.common.CarPlayTarget;

/** Uses Native's task transfer path when the launcher opens an embedded CarPlay session. */
public final class CarPlayLauncherActivity extends Activity {
    private static final int LAUNCH_ON_DISPLAY = 34;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean bound;
    private final Runnable timeout = this::unavailable;
    private final ServiceConnection connection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    if (isFinishing() || isDestroyed()) return;
                    handler.removeCallbacks(timeout);
                    try {
                        Message request = Message.obtain(null, LAUNCH_ON_DISPLAY, 1, 0);
                        Bundle data = new Bundle();
                        data.putString("left", CarPlayTarget.ID);
                        data.putString("right", "");
                        data.putInt("displayId", getWindowManager().getDefaultDisplay().getDisplayId());
                        request.setData(data);
                        new Messenger(binder).send(request);
                        finish();
                    } catch (RemoteException error) {
                        unavailable();
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    unavailable();
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    unavailable();
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    unavailable();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            bound =
                    bindService(
                            new Intent()
                                    .setComponent(
                                            new ComponentName(
                                                    "ru.big.town.anative",
                                                    "ru.big.town.anative.SetModesService")),
                            connection,
                            BIND_AUTO_CREATE);
        } catch (SecurityException error) {
            bound = false;
        }
        if (bound) handler.postDelayed(timeout, 5000);
        else unavailable();
    }

    private void unavailable() {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, "Сервис VoyahTune недоступен", Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(timeout);
        if (bound) unbindService(connection);
        super.onDestroy();
    }
}
