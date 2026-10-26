package ru.big.town.restoremode.widgets.radio;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.widget.Toast;

import ru.big.town.common.RadioWidgetProtocol;

/**
 * Observes the radio session only while its dashboard is visible; binding never requests playback.
 */
public final class RadioWidgetDashboard {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Bundle state;
    private RadioWidgetView view;
    private MediaBrowser browser;
    private MediaController controller;
    private MediaController.Callback callback;
    private boolean watching;
    private int generation;
    private int requestRevision;
    private long pendingUntil;
    private String pendingAction;
    private int pendingFrequency;
    private boolean pendingFm;
    private final Runnable poll = this::requestSnapshot;
    private final Runnable responseTimeout =
            () -> {
                if (controller == null) connectionLost();
                else unavailable("Нет ответа радио. Проверьте обновление VoyahTune");
            };
    private final Runnable reconnect = this::connect;

    public RadioWidgetDashboard(Context context) {
        this.context = context;
    }

    public RadioWidgetView create(int columns, int rows) {
        loadState();
        view = new RadioWidgetView(context, columns, rows, this::open, this::command);
        render();
        return view;
    }

    public void watch(boolean enabled) {
        if (watching == enabled) return;
        watching = enabled;
        loadState();
        if (enabled) connect();
        else disconnect();
    }

    private void loadState() {
        if (state != null) return;
        SharedPreferences saved = preferences();
        state = new Bundle();
        state.putBoolean(RadioWidgetProtocol.FM, saved.getBoolean(RadioWidgetProtocol.FM, true));
        state.putInt(
                RadioWidgetProtocol.FREQUENCY, saved.getInt(RadioWidgetProtocol.FREQUENCY, -1));
        state.putString(RadioWidgetProtocol.NAME, saved.getString(RadioWidgetProtocol.NAME, ""));
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences("radio_widget", Context.MODE_PRIVATE);
    }

    private void connect() {
        disconnect();
        if (!watching) return;
        int connectionGeneration = generation;
        unavailable("Подключение к радио…");
        browser =
                new MediaBrowser(
                        context,
                        new ComponentName(RadioWidgetProtocol.PACKAGE, RadioWidgetProtocol.SERVICE),
                        new MediaBrowser.ConnectionCallback() {
                            @Override
                            public void onConnected() {
                                if (!current(connectionGeneration)) return;
                                try {
                                    controller =
                                            new MediaController(context, browser.getSessionToken());
                                    callback =
                                            new MediaController.Callback() {
                                                @Override
                                                public void onSessionDestroyed() {
                                                    if (current(connectionGeneration))
                                                        connectionLost();
                                                }
                                            };
                                    controller.registerCallback(callback, handler);
                                    handler.removeCallbacks(responseTimeout);
                                    requestSnapshot();
                                } catch (RuntimeException exception) {
                                    connectionLost();
                                }
                            }

                            @Override
                            public void onConnectionFailed() {
                                if (current(connectionGeneration)) connectionLost();
                            }

                            @Override
                            public void onConnectionSuspended() {
                                if (current(connectionGeneration)) connectionLost();
                            }
                        },
                        null);
        try {
            browser.connect();
            handler.postDelayed(responseTimeout, 5000);
        } catch (RuntimeException exception) {
            connectionLost();
        }
    }

    private boolean current(int expectedGeneration) {
        return watching && expectedGeneration == generation;
    }

    private void connectionLost() {
        disconnect();
        unavailable("Нет связи с радио");
        if (watching) handler.postDelayed(reconnect, 3000);
    }

    private void requestSnapshot() {
        handler.removeCallbacks(poll);
        if (!watching || controller == null) return;
        send(RadioWidgetProtocol.SNAPSHOT, null);
    }

    private void command(String action) {
        if (!watching
                || controller == null
                || pendingAction != null
                || !state.getBoolean(RadioWidgetProtocol.READY)) return;
        pendingAction = action;
        pendingUntil = SystemClock.elapsedRealtime() + 6000;
        pendingFrequency = -1;
        handler.removeCallbacks(poll);
        send(action, new Bundle(state));
        render();
    }

    private void send(String action, Bundle station) {
        int connectionGeneration = generation;
        int revision = ++requestRevision;
        Bundle request = station == null ? new Bundle() : station;
        request.putInt(RadioWidgetProtocol.SCHEMA, RadioWidgetProtocol.VERSION);
        request.putString(RadioWidgetProtocol.ACTION, action);
        handler.removeCallbacks(responseTimeout);
        handler.postDelayed(responseTimeout, 3000);
        try {
            controller.sendCommand(
                    RadioWidgetProtocol.COMMAND,
                    request,
                    new ResultReceiver(handler) {
                        @Override
                        protected void onReceiveResult(int resultCode, Bundle response) {
                            if (!current(connectionGeneration) || revision != requestRevision)
                                return;
                            handler.removeCallbacks(responseTimeout);
                            if (resultCode != 1
                                    || response == null
                                    || response.getInt(RadioWidgetProtocol.SCHEMA)
                                            != RadioWidgetProtocol.VERSION) {
                                pendingAction = null;
                                unavailable("Обновите поддержку радио VoyahTune");
                            } else {
                                if (!RadioWidgetProtocol.SNAPSHOT.equals(action)) {
                                    if (!response.getBoolean(RadioWidgetProtocol.ACCEPTED)) {
                                        pendingAction = null;
                                    } else {
                                        pendingFrequency =
                                                response.getInt(
                                                        RadioWidgetProtocol.REQUESTED_FREQUENCY,
                                                        -1);
                                        pendingFm =
                                                response.getBoolean(
                                                        RadioWidgetProtocol.REQUESTED_FM, true);
                                    }
                                }
                                receive(response);
                            }
                            handler.postDelayed(poll, 1000);
                        }
                    });
        } catch (RuntimeException exception) {
            connectionLost();
        }
    }

    private void receive(Bundle response) {
        boolean fm = response.getBoolean(RadioWidgetProtocol.FM, true);
        int frequency = response.getInt(RadioWidgetProtocol.FREQUENCY, -1);
        if (RadioWidgetProtocol.validFrequency(fm, frequency)) {
            String name = response.getString(RadioWidgetProtocol.NAME, "");
            if (name.isEmpty()
                    && fm == state.getBoolean(RadioWidgetProtocol.FM, true)
                    && frequency == state.getInt(RadioWidgetProtocol.FREQUENCY, -1)) {
                name = state.getString(RadioWidgetProtocol.NAME, "");
            }
            state.putBoolean(RadioWidgetProtocol.FM, fm);
            state.putInt(RadioWidgetProtocol.FREQUENCY, frequency);
            state.putString(RadioWidgetProtocol.NAME, name);
            preferences()
                    .edit()
                    .putBoolean(RadioWidgetProtocol.FM, fm)
                    .putInt(RadioWidgetProtocol.FREQUENCY, frequency)
                    .putString(RadioWidgetProtocol.NAME, name)
                    .apply();
        }
        boolean playing = response.getBoolean(RadioWidgetProtocol.PLAYING);
        state.putBoolean(RadioWidgetProtocol.PLAYING, playing);
        state.putBoolean(RadioWidgetProtocol.READY, response.getBoolean(RadioWidgetProtocol.READY));
        state.putBoolean(
                RadioWidgetProtocol.CAN_SKIP, response.getBoolean(RadioWidgetProtocol.CAN_SKIP));
        state.putString(
                RadioWidgetProtocol.MESSAGE, response.getString(RadioWidgetProtocol.MESSAGE, ""));
        if (pendingAction != null) {
            boolean confirmed =
                    RadioWidgetProtocol.PAUSE.equals(pendingAction)
                            ? !playing
                            : playing && frequency == pendingFrequency && fm == pendingFm;
            if (confirmed) pendingAction = null;
            else if (SystemClock.elapsedRealtime() >= pendingUntil) {
                pendingAction = null;
                state.putString(RadioWidgetProtocol.MESSAGE, "Радио не подтвердило команду");
            }
        }
        render();
    }

    private void unavailable(String message) {
        loadState();
        pendingAction = null;
        state.putBoolean(RadioWidgetProtocol.READY, false);
        state.putBoolean(RadioWidgetProtocol.PLAYING, false);
        state.putString(RadioWidgetProtocol.MESSAGE, message);
        render();
        if (watching && controller != null) {
            handler.removeCallbacks(poll);
            handler.postDelayed(poll, 2000);
        }
    }

    private void render() {
        if (view == null || state == null) return;
        Bundle visible = new Bundle(state);
        if (pendingAction != null) {
            visible.putBoolean(RadioWidgetProtocol.READY, false);
            visible.putString(RadioWidgetProtocol.MESSAGE, "Ожидание радио…");
        }
        view.update(visible);
    }

    private void open() {
        Intent intent =
                context.getPackageManager().getLaunchIntentForPackage(RadioWidgetProtocol.PACKAGE);
        if (intent != null) {
            try {
                context.startActivity(intent);
                return;
            } catch (RuntimeException ignored) {
                // The OEM package may have been disabled after its launch intent was resolved.
            }
        }
        Toast.makeText(context, "Приложение радио недоступно", Toast.LENGTH_SHORT).show();
    }

    private void disconnect() {
        ++generation;
        ++requestRevision;
        handler.removeCallbacksAndMessages(null);
        pendingAction = null;
        if (controller != null && callback != null) controller.unregisterCallback(callback);
        controller = null;
        callback = null;
        if (browser != null) browser.disconnect();
        browser = null;
        unavailable("Ожидание радио");
    }

    public void resetView() {
        view = null;
    }

    public void close() {
        watching = false;
        disconnect();
        view = null;
    }
}
