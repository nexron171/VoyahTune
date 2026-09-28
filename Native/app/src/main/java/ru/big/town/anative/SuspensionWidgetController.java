package ru.big.town.anative;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Map;
import ru.big.town.common.SuspensionWidgetProtocol;

/** Uses the shared OEM transport and event hub; sends only explicit UI requests. */
final class SuspensionWidgetController {
    private static final OemVehicleStateTransport.StateKey HEIGHT = key("ASC_MODE_SELECT", 750),
            DIRECTION = key("ASC_ADJUST_DIREACT", 751), MAINTENANCE = key("ASC_MAINTAIN_SWITCH", 711),
            INHIBIT = key("ASC_ADJUST_SUSTEMP", 1060), DRIVE = key("DRIVING_MODE_SET", 545),
            ENTER = key("MANUAL_EASY_ENTER_SET", 790);
    private static final java.util.List<OemVehicleStateTransport.StateKey> KEYS =
            Arrays.asList(HEIGHT, DIRECTION, MAINTENANCE, INHIBIT, DRIVE, ENTER);
    private final Context context;
    private final HandlerThread thread = new HandlerThread("SuspensionWidget");
    private final Handler worker;
    private CanBusEventHub.Subscription subscription;
    private Messenger client;
    private int height = -1, direction = -1, maintenance = -1, drive = -1, inhibit = -1, pending = -1;
    private String message = "Нет данных подвески";
    private volatile boolean closed;
    private boolean commandInFlight;
    private final Runnable timeout = () -> {
        if (pending >= 0) { pending = -1; message = "Изменение высоты не подтверждено"; publish(); }
    };

    SuspensionWidgetController(Context context) {
        this.context = context.getApplicationContext();
        thread.start(); worker = new Handler(thread.getLooper());
    }
    private static OemVehicleStateTransport.StateKey key(String name, int id) {
        return new OemVehicleStateTransport.StateKey(name, id);
    }
    void handle(Message request) {
        int what = request.what, selection = request.arg1;
        Messenger reply = request.replyTo;
        boolean heightOnly = request.getData().getBoolean(SuspensionWidgetProtocol.HEIGHT_ONLY, false);
        worker.post(() -> {
            if (closed) return;
            if (what == SuspensionWidgetProtocol.UNWATCH) {
                if (client != null && client.equals(reply)) stopWatching();
                return;
            }
            if (reply == null) return;
            client = reply;
            if (subscription == null) {
                subscription = CanBusEventHub.get(context).subscribe(
                        CanBusEventRouter.INTEREST_CONNECTION | CanBusEventRouter.INTEREST_VEHICLE_STATE,
                        new int[]{750, 751, 711, 545, 1060}, worker, this::onEvent);
                refresh();
            }
            if (what == SuspensionWidgetProtocol.SELECT) {
                if (heightOnly) { message = "Независимая высота пока не проверена"; publish(); }
                else select(selection);
            }
            else publish();
        });
    }
    private void onEvent(CanBusEvent event) {
        if (closed) return;
        if (event.kind == CanBusEvent.Kind.CONNECTION_LOST) {
            height = -1; maintenance = -1; drive = -1; inhibit = -1; pending = -1;
            message = "Нет связи с автомобилем"; publish();
        } else if (event.kind == CanBusEvent.Kind.CONNECTION) {
            refresh();
        } else if (event.kind == CanBusEvent.Kind.VEHICLE_STATE) {
            if (event.first == 750) height = event.second;
            if (event.first == 751) direction = event.second;
            if (event.first == 711) maintenance = event.second;
            if (event.first == 1060) inhibit = event.second;
            if (event.first == 545) { drive = event.second; rememberMedium(); }
            checkCompletion(); publish();
        }
    }
    private void rememberMedium() {
        if (SuspensionWidgetPolicy.mediumMode(drive)) context.getSharedPreferences("suspension_widget", 0)
                .edit().putInt("mediumDrive", drive).apply();
    }
    private void refresh() {
        if (CanSender.isDebugMode()) { height = -1; message = "Данные автомобиля недоступны в эмуляции"; publish(); return; }
        Map<OemVehicleStateTransport.StateKey, Integer> values =
                OemVehicleStateTransport.readVehicleStates(context, KEYS);
        height = value(values, HEIGHT); direction = value(values, DIRECTION);
        maintenance = value(values, MAINTENANCE); drive = value(values, DRIVE);
        inhibit = value(values, INHIBIT);
        rememberMedium(); checkCompletion(); publish();
    }
    private static int value(Map<OemVehicleStateTransport.StateKey, Integer> values,
                             OemVehicleStateTransport.StateKey key) {
        if (values == null || values.get(key) == null) return -1;
        return values.get(key);
    }
    private void checkCompletion() {
        if (pending >= 0 && SuspensionWidgetPolicy.reached(pending, height)) {
            pending = -1; worker.removeCallbacks(timeout); message = "Высота подтверждена";
        } else if (pending < 0 && "Нет данных подвески".equals(message)
                && SuspensionWidgetPolicy.validHeight(height)) message = "";
    }
    private void select(int selection) {
        if (pending >= 0 || commandInFlight) { publish(); return; }
        if (CanSender.isDebugMode()) { message = "Управление недоступно в эмуляции"; publish(); return; }
        int previous = context.getSharedPreferences("suspension_widget", 0).getInt("mediumDrive", 1);
        commandInFlight = true; message = "Отправляем команду…"; publish();
        ApplyEngine.postUserCommand("suspension widget", () -> {
            String result = dispatch(selection, previous);
            worker.post(() -> {
                commandInFlight = false;
                if (closed) return;
                if (result.isEmpty()) {
                    pending = selection; message = "Команда отправлена, ожидаем высоту";
                    worker.removeCallbacks(timeout); worker.postDelayed(timeout, 45000);
                    checkCompletion();
                } else message = result;
                publish();
            });
        }, () -> worker.post(() -> {
            if (commandInFlight) {
                commandInFlight = false; message = "Команда не выполнена"; publish();
            }
        }));
    }
    private String dispatch(int selection, int previous) {
        if (closed) return "Сервис остановлен";
        String mode = SuspensionWidgetPolicy.driveMode(selection, previous);
        Map<OemVehicleStateTransport.StateKey, Integer> profile = mode == null ? null
                : DriveModeCanTransport.statesFor(context, mode);
        if (selection != 0 && profile == null) return "Профиль движения недоступен";
        ArrayList<OemVehicleStateTransport.StateKey> keys = new ArrayList<>(KEYS);
        if (profile != null) keys.addAll(profile.keySet());
        String result = OemVehicleStateTransport.withSession(context, keys, session -> {
            Integer observedHeight = session.readVehicleState(HEIGHT);
            Integer observedMaintenance = session.readVehicleState(MAINTENANCE);
            Integer inhibit = selection == 0 ? session.readVehicleState(INHIBIT) : 0;
            Integer observedDrive = selection == 0 ? session.readVehicleState(DRIVE) : -1;
            String blocked = SuspensionWidgetPolicy.blocked(selection,
                    observedHeight == null ? -1 : observedHeight,
                    observedMaintenance == null ? -1 : observedMaintenance,
                    inhibit == null ? -1 : inhibit, selection == 3 ? session.readVehicleSpeed() : 0,
                    observedDrive == null ? -1 : observedDrive);
            if (blocked != null) return blocked;
            // Reuse OEM height/profile operations; feedback is handled by the shared mode pipeline.
            OemVehicleStateTransport.Result sent = selection == 0
                    ? session.sendVehicleState(new OemVehicleStateTransport.StateValue(ENTER, 2), "suspension easy entry")
                    : session.sendBundle(profile, "suspension drive " + mode);
            return sent.accepted() ? "" : "Команда не отправлена";
        });
        return result == null ? "Нет связи с автомобилем" : result;
    }
    private void publish() {
        if (client == null || closed) return;
        Bundle data = new Bundle();
        data.putInt(SuspensionWidgetProtocol.HEIGHT, height);
        data.putInt(SuspensionWidgetProtocol.DIRECTION, direction);
        data.putInt(SuspensionWidgetProtocol.MAINTENANCE, maintenance);
        data.putInt(SuspensionWidgetProtocol.DRIVE, drive);
        data.putString(SuspensionWidgetProtocol.LOWEST_BLOCKED_REASON,
                SuspensionWidgetPolicy.lowestBlockedReason(drive, inhibit));
        data.putInt(SuspensionWidgetProtocol.PENDING, pending);
        data.putBoolean(SuspensionWidgetProtocol.AVAILABLE, SuspensionWidgetPolicy.validHeight(height) && maintenance == 1 && !commandInFlight);
        data.putString(SuspensionWidgetProtocol.MESSAGE, message);
        Message response = Message.obtain(null, SuspensionWidgetProtocol.STATE);
        response.setData(data);
        try { client.send(response); } catch (RemoteException e) { stopWatching(); }
    }
    private void stopWatching() {
        if (subscription != null) subscription.close();
        subscription = null; client = null;
        height = -1; maintenance = -1; drive = -1; inhibit = -1;
        worker.removeCallbacks(timeout); pending = -1;
    }
    void close() {
        closed = true;
        worker.post(() -> { stopWatching(); thread.quitSafely(); });
    }
}
