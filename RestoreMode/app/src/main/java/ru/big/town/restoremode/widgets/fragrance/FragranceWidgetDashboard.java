package ru.big.town.restoremode.widgets.fragrance;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import ru.big.town.common.FragranceWidgetProtocol;
import ru.big.town.common.FragranceWidgetProtocol.Snapshot;
import ru.big.town.restoremode.integration.GlobalVars;

public final class FragranceWidgetDashboard {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Messenger client =
            new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private final Runnable timeout = () -> update(Snapshot.UNKNOWN, false, "Нет связи с Native");
    private FragranceWidgetView view;
    private Snapshot state = Snapshot.UNKNOWN;
    private String message = "";
    private boolean pending;
    private boolean resumed;
    private boolean watching;
    private int session;

    public FragranceWidgetDashboard(Context context) {
        this.context = context;
    }

    public FragranceWidgetView create(FragranceWidgetLayout layout) {
        view = new FragranceWidgetView(context, layout, this::command);
        view.onVisibilityChange(this::syncSubscription);
        view.setResumed(resumed);
        view.update(state, pending, message);
        return view;
    }

    public void watch(boolean enabled) {
        resumed = enabled;
        if (view != null) view.setResumed(enabled);
        syncSubscription();
    }

    private void syncSubscription() {
        boolean needed = resumed && view != null && view.isVisibleTile();
        if (!needed) {
            if (watching) send(FragranceWidgetProtocol.UNWATCH, 0);
            disconnected();
        } else if (!watching) {
            session++;
            watching = send(FragranceWidgetProtocol.WATCH, 0);
            if (watching) armTimeout();
            else update(Snapshot.UNKNOWN, false, "Нет связи с Native");
        }
    }

    private void command(int command, int value) {
        if (!watching || pending || !state.allows(command, value)) return;
        if (send(command, value)) {
            update(state, true, "");
            armTimeout();
        } else {
            disconnected();
        }
    }

    private boolean send(int command, int value) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) return false;
        Message request = Message.obtain(null, command, value, session);
        request.replyTo = client;
        try {
            GlobalVars.serviceMessenger.send(request);
            return true;
        } catch (RemoteException unavailable) {
            return false;
        }
    }

    private boolean receive(Message response) {
        if (response.what != FragranceWidgetProtocol.STATE) return false;
        if (watching && session == response.arg2) {
            Bundle data = response.getData();
            update(
                    Snapshot.from(data),
                    data.getBoolean(FragranceWidgetProtocol.BUSY),
                    data.getString(FragranceWidgetProtocol.MESSAGE, ""));
            armTimeout();
        }
        return true;
    }

    private void armTimeout() {
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, 12000);
    }

    private void update(Snapshot value, boolean busy, String detail) {
        state = value;
        pending = busy;
        message = detail;
        if (view != null) view.update(value, busy, detail);
    }

    public void disconnected() {
        watching = false;
        handler.removeCallbacks(timeout);
        update(Snapshot.UNKNOWN, false, "");
    }

    public void resetView() {
        if (view != null) {
            view.onVisibilityChange(null);
            view.setResumed(false);
            view = null;
        }
        syncSubscription();
    }

    public void close() {
        resumed = false;
        resetView();
    }
}
