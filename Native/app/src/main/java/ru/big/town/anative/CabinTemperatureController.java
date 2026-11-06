package ru.big.town.anative;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import ru.big.town.common.CabinTemperatureProtocol;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleConsumer;

/** One last-known cabin observation shared by scenarios and both dashboards. */
final class CabinTemperatureController {
    private static CabinTemperatureController instance;
    private static final String SAVED_RAW = "cabinTemperatureRaw";
    private final Context context;
    private final android.content.SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CabinTemperatureReading reading = new CabinTemperatureReading();
    private final WidgetClients clients = new WidgetClients();
    private final CanBusEventHub.Subscription connection;
    private final CopyOnWriteArrayList<DoubleConsumer> listeners = new CopyOnWriteArrayList<>();
    private boolean closed;
    private boolean requesting;
    private final BroadcastReceiver updates =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent intent) {
                    if (CabinTemperatureProtocol.UPDATE.equals(intent.getAction()))
                        accept(intent.getExtras());
                }
            };

    static synchronized CabinTemperatureController get(Context context) {
        if (instance == null) instance = new CabinTemperatureController(context);
        return instance;
    }

    private CabinTemperatureController(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE);
        reading.accept(preferences.getInt(SAVED_RAW, -1), 0, SystemClock.elapsedRealtime());
        IntentFilter filter = new IntentFilter(CabinTemperatureProtocol.UPDATE);
        if (Build.VERSION.SDK_INT >= 33) {
            this.context.registerReceiver(
                    updates,
                    filter,
                    CabinTemperatureProtocol.PERMISSION,
                    handler,
                    Context.RECEIVER_EXPORTED);
        } else {
            this.context.registerReceiver(
                    updates, filter, CabinTemperatureProtocol.PERMISSION, handler);
        }
        connection =
                CanBusEventHub.get(context)
                        .subscribe(
                                CanBusEventRouter.INTEREST_CONNECTION,
                                null,
                                handler,
                                event -> {
                                    if (event.kind == CanBusEvent.Kind.CONNECTION) requestLatest();
                                });
        handler.post(this::requestLatest);
    }

    double temperature() {
        return reading.value();
    }

    ScenarioEnvironment.Subscription subscribe(DoubleConsumer listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void accept(Bundle values) {
        if (closed || values == null) return;
        int raw = values.getInt(CabinTemperatureProtocol.RAW, -1);
        boolean accepted =
                reading.accept(
                        raw,
                        values.getLong(CabinTemperatureProtocol.SAMPLED_AT, -1),
                        SystemClock.elapsedRealtime());
        if (accepted) {
            if (preferences.getInt(SAVED_RAW, -1) != raw) {
                preferences.edit().putInt(SAVED_RAW, raw).apply();
            }
            double temperature = reading.value();
            for (DoubleConsumer listener : listeners) listener.accept(temperature);
        }
        publish();
    }

    private void requestLatest() {
        if (closed || requesting) return;
        requesting = true;
        try {
            context.sendOrderedBroadcast(
                    new Intent(CabinTemperatureProtocol.REQUEST)
                            .setPackage("com.qinggan.canbus.service"),
                    CabinTemperatureProtocol.PERMISSION,
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context ignored, Intent intent) {
                            requesting = false;
                            accept(getResultExtras(false));
                        }
                    },
                    handler,
                    0,
                    null,
                    null);
        } catch (RuntimeException unavailable) {
            requesting = false;
            Log.w("CabinTemperature", "OEM cabin cache unavailable", unavailable);
        }
    }

    void handle(Message message) {
        int what = message.what;
        int session = message.arg2;
        android.os.Messenger client = message.replyTo;
        handler.post(
                () -> {
                    if (closed) return;
                    if (what == CabinTemperatureProtocol.UNWATCH) {
                        clients.unwatch(client, session);
                    } else if (what == CabinTemperatureProtocol.WATCH) {
                        clients.watch(client, session);
                        publish();
                        requestLatest();
                    }
                });
    }

    private void publish() {
        double temperature = reading.value();
        Bundle state = new Bundle();
        state.putBoolean(CabinTemperatureProtocol.AVAILABLE, Double.isFinite(temperature));
        state.putDouble(CabinTemperatureProtocol.VALUE, temperature);
        state.putString(
                CabinTemperatureProtocol.MESSAGE, Double.isFinite(temperature) ? "" : "Нет данных");
        clients.publish(CabinTemperatureProtocol.STATE, state);
    }

    void close() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        connection.close();
        context.unregisterReceiver(updates);
        clients.clear();
        listeners.clear();
        synchronized (CabinTemperatureController.class) {
            if (instance == this) instance = null;
        }
    }
}
