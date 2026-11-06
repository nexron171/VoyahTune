package ru.big.town.restoremode.widgets.fuel;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import ru.big.town.common.FuelFlapWidgetProtocol;
import ru.big.town.restoremode.integration.GlobalVars;

public final class FuelFlapWidgetDashboard {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Messenger client =
            new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private FuelFlapWidgetView view;
    private Bundle state = new Bundle();
    private boolean watching;
    private int session;
    private final Runnable unavailable =
            () -> showUnavailable("Обновите Native или проверьте связь");

    public FuelFlapWidgetDashboard(Context context) {
        this.context = context;
    }

    public FuelFlapWidgetView create(FuelFlapWidgetLayout layout) {
        view =
                new FuelFlapWidgetView(
                        context,
                        layout,
                        () -> {
                            if (!watching
                                    || !state.getBoolean(FuelFlapWidgetProtocol.CAN_OPEN, false))
                                return;
                            if (send(FuelFlapWidgetProtocol.OPEN, 0)) {
                                state.putBoolean(FuelFlapWidgetProtocol.CAN_OPEN, false);
                                state.putString(FuelFlapWidgetProtocol.MESSAGE, "Открываем лючок…");
                                view.update(state);
                                handler.removeCallbacks(unavailable);
                                handler.postDelayed(unavailable, 5000);
                            } else {
                                disconnected();
                            }
                        });
        view.update(state);
        return view;
    }

    public void watch(boolean enabled) {
        if (!enabled) {
            if (watching) {
                send(FuelFlapWidgetProtocol.UNWATCH, 0);
            }
            watching = false;
            ++session;
            handler.removeCallbacks(unavailable);
            showUnavailable("Ожидание Native");
            return;
        }
        if (watching) {
            return;
        }
        ++session;
        watching = send(FuelFlapWidgetProtocol.WATCH, 0);
        if (watching) {
            handler.postDelayed(unavailable, 5000);
        } else {
            showUnavailable("Нет связи с Native");
        }
    }

    public void disconnected() {
        watching = false;
        ++session;
        handler.removeCallbacks(unavailable);
        showUnavailable("Нет связи с Native");
    }

    private boolean receive(Message response) {
        if (response.what != FuelFlapWidgetProtocol.STATE) {
            return false;
        }
        if (watching && response.arg2 == session) {
            handler.removeCallbacks(unavailable);
            state = new Bundle(response.getData());
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
        state = new Bundle();
        state.putString(FuelFlapWidgetProtocol.MESSAGE, message);
        if (view != null) {
            view.update(state);
        }
    }

    public void resetView() {
        if (view != null) view.dismissConfirmation();
        view = null;
    }

    public void close() {
        watch(false);
        view = null;
    }
}
