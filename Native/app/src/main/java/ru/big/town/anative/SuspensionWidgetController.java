package ru.big.town.anative;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Map;
import ru.big.town.common.SuspensionWidgetProtocol;
import ru.big.town.common.DriveSelectionPolicy;

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
    private VehiclePort.Subscription subscription;
    private final VehiclePort vehicle;
    private final DriveStore driveStore;
    private final CommandQueue commands;
    private final long confirmationTimeoutMs;
    interface DriveStore { DriveSelectionPolicy read(); boolean record(String mode); }
    interface CommandQueue { void post(Runnable command, Runnable completed); }
    private final ru.big.town.common.MessengerSubscription client = new ru.big.town.common.MessengerSubscription();
    private int height = -1, direction = -1, maintenance = -1, drive = -1, inhibit = -1, pending = -1;
    private String message = "Нет данных подвески";
    private volatile boolean closed;
    private boolean commandInFlight;
    private final Runnable timeout = () -> {
        if (pending >= 0) { pending = -1; message = "Изменение высоты не подтверждено"; publish(); }
    };

    SuspensionWidgetController(Context context) {
        this(context, VehiclePort.oem(context), new DriveStore() {
            public DriveSelectionPolicy read() { return DriveSelectionStore.read(context); }
            public boolean record(String mode) {
                ApplyEngine.noteVehicleMode("driveMode", mode);
                return DriveSelectionStore.record(context, mode, DriveSelectionPolicy.WIDGET);
            }
        }, (command, done) -> ApplyEngine.postUserCommand("suspension widget", command, done), 45000);
    }
    SuspensionWidgetController(Context context, VehiclePort vehicle, DriveStore driveStore,
                               CommandQueue commands, long confirmationTimeoutMs) {
        this.context = context.getApplicationContext();
        this.vehicle = vehicle; this.driveStore = driveStore;
        this.commands = commands; this.confirmationTimeoutMs = confirmationTimeoutMs;
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
                if (client.remove(reply)) stopWatching();
                return;
            }
            if (reply == null) return;
            client.set(reply);
            if (subscription == null) {
                subscription = vehicle.subscribe(
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
            if (event.first == 545) drive = event.second;
            checkCompletion(); publish();
        }
    }
    private void refresh() {
        Map<OemVehicleStateTransport.StateKey, Integer> values =
                vehicle.session(KEYS, session -> {
                    Map<OemVehicleStateTransport.StateKey, Integer> result = new java.util.LinkedHashMap<>();
                    for (OemVehicleStateTransport.StateKey key : KEYS) {
                        Integer value = session.readVehicleState(key);
                        if (value == null) return null;
                        result.put(key, value);
                    }
                    return result;
                });
        height = value(values, HEIGHT); direction = value(values, DIRECTION);
        maintenance = value(values, MAINTENANCE); drive = value(values, DRIVE);
        inhibit = value(values, INHIBIT);
        checkCompletion(); publish();
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
        commandInFlight = true; message = "Отправляем команду…"; publish();
        commands.post(() -> {
            String result = dispatch(selection);
            worker.post(() -> {
                commandInFlight = false;
                if (closed) return;
                if (result.isEmpty()) {
                    pending = selection; message = "Команда отправлена, ожидаем высоту";
                    worker.removeCallbacks(timeout); worker.postDelayed(timeout, confirmationTimeoutMs);
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
    private String dispatch(int selection) {
        if (closed) return "Сервис остановлен";
        DriveSelectionPolicy saved = driveStore.read();
        if (saved == null) return "Обновите RestoreMode: нет общего состояния режимов";
        int previous = DriveSelectionPolicy.value(saved.medium);
        String mode = SuspensionWidgetPolicy.driveMode(selection, previous);
        Map<OemVehicleStateTransport.StateKey, Integer> profile = mode == null ? null
                : vehicle.driveProfile(mode);
        if (selection != 0 && profile == null) return "Профиль движения недоступен";
        ArrayList<OemVehicleStateTransport.StateKey> keys = new ArrayList<>(KEYS);
        if (profile != null) keys.addAll(profile.keySet());
        String result = vehicle.session(keys, session -> {
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
            if (!sent.accepted()) return "Команда не отправлена";
            String selectedMode = mode;
            if (selection == 0) {
                ModeFeedbackDecoder.Feedback observed = ModeFeedbackDecoder.decode(545,
                        observedDrive == null ? -1 : observedDrive);
                selectedMode = observed == null ? null : observed.mode;
            }
            return driveStore.record(selectedMode) ? "" : "Команда отправлена, но режим не сохранён";
        });
        return result == null ? "Нет связи с автомобилем" : result;
    }
    private void publish() {
        if (closed) return;
        if (!client.active()) { stopWatching(); return; }
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
        if (!client.send(response)) stopWatching();
    }
    private void stopWatching() {
        if (subscription != null) subscription.close();
        subscription = null; client.clear();
        height = -1; maintenance = -1; drive = -1; inhibit = -1;
        worker.removeCallbacks(timeout); pending = -1;
    }
    void close() {
        closed = true;
        worker.post(() -> { stopWatching(); thread.quitSafely(); });
    }
}
