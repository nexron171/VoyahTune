package ru.big.town.restoremode.widgets.climate;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import ru.big.town.common.CabinTemperatureProtocol;
import ru.big.town.restoremode.integration.GlobalVars;

public final class CabinTemperatureWidgetDashboard {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Messenger client =
            new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private CabinTemperatureWidgetView view;
    private Bundle state = new Bundle();
    private boolean watching;
    private int session;
    private final Runnable unavailable =
            () -> showUnavailable("Обновите Native или проверьте связь");

    public CabinTemperatureWidgetDashboard(Context context) {
        this.context = context;
    }

    public CabinTemperatureWidgetView create() {
        view = new CabinTemperatureWidgetView(context);
        view.update(state);
        return view;
    }

    public void watch(boolean enabled) {
        if (!enabled) {
            if (watching) {
                send(CabinTemperatureProtocol.UNWATCH, 0);
            }
            watching = false;
            handler.removeCallbacks(unavailable);
            showUnavailable("Ожидание Native");
            return;
        }
        if (watching) {
            return;
        }
        ++session;
        watching = send(CabinTemperatureProtocol.WATCH, 0);
        if (watching) {
            handler.postDelayed(unavailable, 5000);
        }
    }

    public void disconnected() {
        watching = false;
        ++session;
        handler.removeCallbacks(unavailable);
        showUnavailable("Нет связи с Native");
    }

    private boolean receive(Message response) {
        if (response.what != CabinTemperatureProtocol.STATE) {
            return false;
        }
        if (watching && response.arg2 == session) {
            handler.removeCallbacks(unavailable);
            Bundle observed = new Bundle(response.getData());
            if (observed.getBoolean(CabinTemperatureProtocol.AVAILABLE, false)
                    || !state.getBoolean(CabinTemperatureProtocol.AVAILABLE, false)) {
                state = observed;
            }
            if (view != null) {
                view.update(state);
            }
        }
        return true;
    }

    private boolean send(int what, int mode) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            return false;
        }
        Message request = Message.obtain(null, what, mode, session);
        request.replyTo = client;
        try {
            GlobalVars.serviceMessenger.send(request);
            return true;
        } catch (RemoteException exception) {
            return false;
        }
    }

    private void showUnavailable(String message) {
        state.putString(CabinTemperatureProtocol.MESSAGE, message);
        if (view != null) {
            view.update(state);
        }
    }

    public void resetView() {
        view = null;
    }

    public void close() {
        watch(false);
        view = null;
    }
}
