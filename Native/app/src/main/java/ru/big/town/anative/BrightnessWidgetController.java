package ru.big.town.anative;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;

import ru.big.town.common.ScreenBrightnessSettings;
import ru.big.town.common.BrightnessWidgetProtocol;

final class BrightnessWidgetController {
    private final Context context;
    private final WidgetClients clients = new WidgetClients();
    private final OemScreenBrightnessTransport transport = new OemScreenBrightnessTransport();
    private boolean closed;
    private int pending = -1;
    private long deadline;
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (closed || !clients.hasClients()) return;
            Bundle state = new Bundle();
            try {
                transport.connect(context);
                int mode = transport.readMode(1);
                boolean same = transport.readMode(2) == mode && transport.readMode(16) == mode;
                int override = context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE)
                        .getInt(BrightnessWidgetProtocol.OVERRIDE, BrightnessWidgetProtocol.AUTO);
                boolean rules = context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE)
                        .getBoolean(ScreenBrightnessSettings.ENABLED, false);
                int selected = same ? (BrightnessWidgetProtocol.rulesActive(rules, override) ? override : mode) : -1;
                if (pending == selected && override == pending) pending = -1;
                boolean expired = pending >= 0 && SystemClock.elapsedRealtime() >= deadline;
                state.putInt(BrightnessWidgetProtocol.MODE, selected);
                state.putInt(BrightnessWidgetProtocol.PENDING, expired ? -1 : pending);
                state.putBoolean(BrightnessWidgetProtocol.AVAILABLE, pending < 0 || expired);
                String message = "";
                if (expired) message = "Переключение не подтверждено";
                else if (pending >= 0) message = "Переключение…";
                else if (!same) message = "Разные режимы";
                state.putString(BrightnessWidgetProtocol.MESSAGE, message);
            } catch (Exception | LinkageError error) {
                state.putString(BrightnessWidgetProtocol.MESSAGE, "Нет связи с дисплеями");
            }
            clients.publish(BrightnessWidgetProtocol.STATE, state);
            if (clients.hasClients()) ScreenBrightnessService.WORKER.postDelayed(this, 1000);
        }
    };

    BrightnessWidgetController(Context context) {
        this.context = context.getApplicationContext();
    }

    void handle(Message request) {
        int what = request.what;
        int mode = request.arg1;
        int session = request.arg2;
        Messenger reply = request.replyTo;
        ScreenBrightnessService.WORKER.post(() -> {
            if (closed || reply == null) return;
            if (what == BrightnessWidgetProtocol.WATCH) {
                clients.watch(reply, session);
            } else if (what == BrightnessWidgetProtocol.UNWATCH) {
                clients.unwatch(reply, session);
            } else if (what == BrightnessWidgetProtocol.SELECT
                    && clients.isWatching(reply, session) && mode >= 0 && mode <= 2) {
                pending = mode;
                deadline = SystemClock.elapsedRealtime() + 5000;
                try {
                    context.startForegroundService(new Intent(context, ScreenBrightnessService.class)
                            .putExtra(BrightnessWidgetProtocol.MODE, mode));
                } catch (RuntimeException error) {
                    deadline = 0;
                }
            }
            ScreenBrightnessService.WORKER.removeCallbacks(refresh);
            ScreenBrightnessService.WORKER.postDelayed(refresh, 300);
        });
    }

    void close() {
        ScreenBrightnessService.WORKER.post(() -> {
            closed = true;
            clients.clear();
            ScreenBrightnessService.WORKER.removeCallbacks(refresh);
        });
    }
}
