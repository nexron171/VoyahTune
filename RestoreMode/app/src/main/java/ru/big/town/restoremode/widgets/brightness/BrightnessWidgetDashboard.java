package ru.big.town.restoremode.widgets.brightness;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import ru.big.town.common.BrightnessWidgetProtocol;
import ru.big.town.restoremode.integration.GlobalVars;

public final class BrightnessWidgetDashboard {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Messenger client =
            new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private BrightnessWidgetView view;
    private Bundle state = new Bundle();
    private boolean watching;
    private int session;
    private final Runnable unavailable =
            () -> showUnavailable("Обновите Native или проверьте связь");

    public BrightnessWidgetDashboard(Context context) {
        this.context = context;
    }

    public BrightnessWidgetView create() {
        view =
                new BrightnessWidgetView(
                        context,
                        mode -> {
                            if (!watching
                                    || !state.getBoolean(
                                            BrightnessWidgetProtocol.AVAILABLE, false)) {
                                return;
                            }
                            if (send(BrightnessWidgetProtocol.SELECT, mode)) {
                                state.putBoolean(BrightnessWidgetProtocol.AVAILABLE, false);
                                state.putInt(BrightnessWidgetProtocol.PENDING, mode);
                                state.putString(BrightnessWidgetProtocol.MESSAGE, "Отправка…");
                                handler.removeCallbacks(unavailable);
                                handler.postDelayed(unavailable, 5000);
                                view.update(state);
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
                send(BrightnessWidgetProtocol.UNWATCH, 0);
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
        watching = send(BrightnessWidgetProtocol.WATCH, 0);
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
        if (response.what != BrightnessWidgetProtocol.STATE) {
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
        state.putString(BrightnessWidgetProtocol.MESSAGE, message);
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
