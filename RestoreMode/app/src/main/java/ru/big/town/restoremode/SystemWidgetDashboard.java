package ru.big.town.restoremode;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import ru.big.town.common.SystemWidgetProtocol;

/** Local metrics/history; only memory cleanup uses Native. Hidden widgets do not poll. */
final class SystemWidgetDashboard {
    private final Context context;
    private final Consumer<String> notify;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SystemLoadHistory history = new SystemLoadHistory();
    private final List<SystemLoadWidgetView> monitors = new ArrayList<>();
    private final SystemMetricsReader metrics;
    private final ExecutorService metricsExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "VoyahTune-dashboard-metrics");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final Messenger client = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private View clearButton;
    private TextView clearLabel;
    private boolean resumed, samplingActive, sampling, destroyed;
    private long generation;
    private int sequence, clearRequest;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (destroyed || !samplingActive) return;
            history.prune(SystemClock.elapsedRealtime());
            redraw();
            sampleMetrics();
            handler.postDelayed(this, SystemMetricsReader.INTERVAL_MS);
        }
    };
    private final Runnable clearTimeout = this::onClearTimeout;
    private void onClearTimeout() {
        handler.removeCallbacks(clearTimeout);
        if (clearRequest == 0) return;
        clearRequest = 0;
        updateClear();
        if (resumed) notify.accept("Нет ответа от Native. Результат очистки неизвестен");
    }

    SystemWidgetDashboard(Context context, Consumer<String> notify) {
        this.context = context; this.notify = notify;
        metrics = new SystemMetricsReader(context);
    }

    void resetViews() { monitors.clear(); clearButton = null; clearLabel = null; }

    View create(String id, ViewGroup parent) {
        if (SystemWidgetLayout.CLEAR.equals(id)) {
            clearButton = LayoutInflater.from(context).inflate(R.layout.tile_clear_memory, parent, false);
            clearLabel = clearButton.findViewById(R.id.clearMemoryLabel);
            clearButton.setOnClickListener(v -> clear());
            updateClear();
            return clearButton;
        }
        SystemLoadWidgetView view = new SystemLoadWidgetView(context, SystemWidgetLayout.CPU.equals(id), history);
        monitors.add(view);
        view.update();
        return view;
    }

    void setResumed(boolean resumed) {
        this.resumed = resumed;
        boolean active = resumed && !destroyed && !monitors.isEmpty();
        if (active == samplingActive) return;
        samplingActive = active;
        ++generation; // Ignore worker results from the previous visible session.
        handler.removeCallbacks(tick);
        if (active) handler.post(tick);
    }

    private void sampleMetrics() {
        if (sampling) return;
        sampling = true;
        final long session = generation;
        try {
            metricsExecutor.execute(() -> {
                SystemMetricsReader.Snapshot snapshot = metrics.read(session);
                handler.post(() -> {
                    sampling = false;
                    if (destroyed || !samplingActive || session != generation) return;
                    history.add(snapshot.elapsed, snapshot.cpuPercent, snapshot.ramPercent,
                            SystemClock.elapsedRealtime());
                    redraw();
                });
            });
        } catch (RejectedExecutionException ignored) { sampling = false; }
    }

    void disconnected() {
        if (destroyed) return;
        if (clearRequest != 0) clearTimeout.run();
    }

    private boolean send(int what, int request) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) return false;
        Message message = Message.obtain(null, what, request, 0);
        message.replyTo = client;
        try { GlobalVars.serviceMessenger.send(message); return true; }
        catch (RemoteException e) { return false; }
    }

    private void clear() {
        if (clearRequest != 0 || destroyed) return;
        clearRequest = ++sequence;
        if (!send(SystemWidgetProtocol.CLEAR, clearRequest)) {
            clearRequest = 0;
            notify.accept("Сервис Native не готов");
            return;
        }
        updateClear();
        handler.postDelayed(clearTimeout, SystemWidgetProtocol.TIMEOUT_MS);
    }

    private boolean receive(Message message) {
        if (destroyed) return true;
        Bundle data = message.getData();
        if (data.getInt(SystemWidgetProtocol.SCHEMA) != SystemWidgetProtocol.VERSION) return true;
        if (message.what == SystemWidgetProtocol.CLEAR_RESULT) {
            if (clearRequest == 0 || message.arg1 != clearRequest) return true;
            clearRequest = 0;
            handler.removeCallbacks(clearTimeout);
            updateClear();
            String error = data.getString(SystemWidgetProtocol.ERROR);
            int succeeded = data.getInt(SystemWidgetProtocol.SUCCEEDED), failed = data.getInt(SystemWidgetProtocol.FAILED);
            String result = error != null ? error : failed > 0
                    ? "Очистка выполнена частично. Ошибок: " + failed
                    : succeeded == 0 ? "Нет сторонних приложений для закрытия" : "Очистка выполнена";
            if (resumed) notify.accept(result);
            // Do not invent freed bytes or an app count: Native only confirms force-stop calls.
        }
        return true;
    }

    private void redraw() { for (SystemLoadWidgetView view : monitors) view.update(); }
    private void updateClear() {
        if (clearButton == null) return;
        clearButton.setEnabled(clearRequest == 0);
        clearButton.setAlpha(clearRequest == 0 ? 1f : .65f);
        clearLabel.setText(clearRequest == 0 ? "Очистить\nпамять" : "Закрытие\nприложений");
        clearButton.setContentDescription(clearRequest == 0
                ? "Очистить память: закрыть все сторонние приложения" : "Закрытие сторонних приложений");
    }
    void close() {
        destroyed = true; resumed = samplingActive = false; clearRequest = 0; ++generation;
        handler.removeCallbacksAndMessages(null);
        metricsExecutor.shutdownNow();
        resetViews();
    }
}
