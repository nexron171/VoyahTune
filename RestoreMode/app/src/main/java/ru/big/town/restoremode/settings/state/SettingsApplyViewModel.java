package ru.big.town.restoremode.settings.state;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import ru.big.town.restoremode.integration.GlobalVars;

/** Native replies and the timeout survive recreation without retaining the settings Activity. */
public final class SettingsApplyViewModel extends ViewModel {
    private static final int MSG_APPLY_DRIVE_MODES = 1;
    private static final int MSG_RESULT = 4;
    private final MutableLiveData<Boolean> applying = new MutableLiveData<>(false);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> setApplying(false);
    private boolean cleared;
    private final Messenger reply =
            new Messenger(
                    new Handler(Looper.getMainLooper()) {
                        @Override
                        public void handleMessage(Message message) {
                            if (message.what == MSG_RESULT && !cleared) {
                                setApplying(false);
                            } else {
                                super.handleMessage(message);
                            }
                        }
                    });

    public LiveData<Boolean> applying() {
        return applying;
    }

    public boolean isApplying() {
        return Boolean.TRUE.equals(applying.getValue());
    }

    public void apply() {
        if (isApplying() || !GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            return;
        }
        try {
            Message message = Message.obtain(null, MSG_APPLY_DRIVE_MODES);
            message.replyTo = reply;
            GlobalVars.serviceMessenger.send(message);
            setApplying(true);
        } catch (RemoteException exception) {
            Log.w("SettingsApply", "Native apply unavailable", exception);
        }
    }

    private void setApplying(boolean active) {
        applying.setValue(active);
        handler.removeCallbacks(timeout);
        if (active) {
            handler.postDelayed(timeout, 12_000);
        }
    }

    @Override
    protected void onCleared() {
        cleared = true;
        handler.removeCallbacksAndMessages(null);
    }
}
