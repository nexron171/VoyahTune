package ru.big.town.anative;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Движок пользовательских сценариев: событийные триггеры (селектор, двери, освещённость),
 * проверка условий и запуск последовательности действий.
 *
 * <p>Работает в процессе Native, поэтому сценарии срабатывают и при закрытом RestoreMode.
 * Определения приходят снимком из RestoreMode через {@link ScenarioConfigReceiver}; запуск
 * вручную (плитка/голос) — через {@link #runNow(String)}.</p>
 */
final class ScenarioEngine {
    private static final String TAG = "$$$ ScenarioEngine $$$";
    /** Антидребезг повторного запуска одного сценария. */
    private static final long MIN_RUN_INTERVAL_MS = 5_000L;

    private final ScenarioEnvironment environment;
    private final ScenarioRunner runner;
    private final HandlerThread thread;
    private final Handler handler;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService conditionExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ScenarioConditions");
        thread.setDaemon(true);
        return thread;
    });

    private volatile List<ScenarioDefinition> scenarios = Collections.emptyList();
    private ScenarioEnvironment.Subscription lightSubscription;
    private volatile boolean closed;

    /* Поля ниже читаются и пишутся только на handler движка. */
    private final Set<String> running = new HashSet<>();
    private final Map<String, Long> lastRunElapsed = new HashMap<>();
    private boolean gearSnapshotSeen;
    private boolean doorSnapshotSeen;
    private ScenarioPolicy.LightState lastLight = ScenarioPolicy.LightState.HOLD;

    private final ScenarioEnvironment.Subscription gearSubscription;
    private final ScenarioEnvironment.Subscription doorSubscription;

    ScenarioEngine(SetModesService service) {
        this(ScenarioEnvironment.oem(service));
    }

    ScenarioEngine(ScenarioEnvironment environment) {
        this.environment = environment;
        this.runner = new ScenarioRunner(environment);
        thread = new HandlerThread("ScenarioEngine");
        thread.start();
        handler = new Handler(thread.getLooper());
        gearSubscription = environment.gear(handler, this::onGear);
        doorSubscription = environment.doors(handler, this::onDoors);
    }

    void close() {
        closed = true;
        runner.close();
        main.removeCallbacksAndMessages(null);
        handler.removeCallbacksAndMessages(null);
        gearSubscription.close();
        doorSubscription.close();
        stopLightListening();
        conditionExecutor.shutdownNow();
        thread.quitSafely();
    }

    /** Полный снимок определений из RestoreMode. Пустая строка выключает все сценарии. */
    void reload(String json) {
        List<ScenarioDefinition> parsed = ScenarioDefinition.parse(json);
        scenarios = parsed;
        updateLightListening();
        Log.i(TAG, "Загружено сценариев: " + parsed.size());
    }

    /** Нужен ли движку уровень датчика освещённости (хотя бы одному включённому сценарию). */
    boolean usesLightLevel() {
        for (ScenarioDefinition scenario : scenarios) {
            if (scenario.enabled && scenario.usesLight()) return true;
        }
        return false;
    }

    /** Запуск вручную: плитка на главном экране или голосовая команда. */
    boolean runNow(String id) {
        if (id == null) return false;
        for (ScenarioDefinition scenario : scenarios) {
            if (!scenario.id.equals(id)) continue;
            if (!scenario.enabled || !scenario.hasSteps()) {
                Log.w(TAG, "Сценарий " + id + " выключен или пуст");
                return false;
            }
            handler.post(() -> start(scenario, "manual", false));
            return true;
        }
        Log.w(TAG, "Сценарий не найден: " + id);
        return false;
    }

    boolean testRun(String id) {
        if (id == null) return false;
        for (ScenarioDefinition scenario : scenarios) {
            if (!scenario.id.equals(id)) continue;
            if (!scenario.hasSteps()) {
                Log.w(TAG, "Сценарий " + id + " пуст");
                toast("В сценарии нет действий");
                return false;
            }
            handler.post(() -> evaluateAndRun(scenario, "тест", true));
            return true;
        }
        Log.w(TAG, "Сценарий не найден: " + id);
        toast("Сценарий не найден");
        return false;
    }

    // --- Триггеры ---

    private void onGear(int gearValue) {
        if (!gearSnapshotSeen) {
            // Первое событие — снимок при подписке; сценарий должен реагировать только на смену.
            gearSnapshotSeen = true;
            return;
        }
        for (ScenarioDefinition scenario : enabledScenarios()) {
            if (ScenarioPolicy.matchesGear(scenario, gearValue)) {
                evaluateAndRun(scenario, "селектор " + gearValue);
            }
        }
    }

    private void onDoors(int doorMask) {
        if (!doorSnapshotSeen) {
            doorSnapshotSeen = true;
            return;
        }
        for (ScenarioDefinition scenario : enabledScenarios()) {
            if (ScenarioPolicy.matchesDoor(scenario, doorMask)) {
                evaluateAndRun(scenario, "двери " + doorMask);
            }
        }
    }

    private void onLightLevel(int level) {
        handler.post(() -> {
            ScenarioPolicy.LightState state = ScenarioPolicy.lightState(level);
            ScenarioPolicy.LightState previous = lastLight;
            lastLight = state;
            if (state == ScenarioPolicy.LightState.HOLD || state == previous) return;
            for (ScenarioDefinition scenario : enabledScenarios()) {
                if (ScenarioPolicy.matchesLight(scenario, previous, state)) {
                    evaluateAndRun(scenario, "освещённость " + state);
                }
            }
        });
    }

    // --- Проверка условий и запуск ---

    private void evaluateAndRun(ScenarioDefinition scenario, String reason) {
        evaluateAndRun(scenario, reason, false);
    }

    private void evaluateAndRun(ScenarioDefinition scenario, String reason, boolean test) {
        if (running.contains(scenario.id)) {
            if (test) toast("Сценарий уже выполняется");
            return;
        }
        if (scenario.conditions.isEmpty()) {
            start(scenario, reason, test);
            return;
        }
        conditionExecutor.execute(() -> {
            ScenarioPolicy.Snapshot snapshot = environment.snapshot(scenario);
            String failure = ScenarioPolicy.firstFailingCondition(scenario, snapshot);
            handler.post(() -> {
                if (failure == null) {
                    start(scenario, reason, test);
                } else {
                    Log.i(TAG, "«" + scenario.name + "» (" + reason + "): условия не выполнены — " + failure);
                    toast("Сценарий не выполнен из-за несоблюдения условия: " + failure);
                }
            });
        });
    }

    private void toast(String text) {
        main.post(() -> { if (!closed) environment.notifyUser(text); });
    }

    /** Только handler движка: держит running/lastRunElapsed без блокировок. */
    private void start(ScenarioDefinition scenario, String reason, boolean test) {
        if (closed || running.contains(scenario.id)) return;
        long now = SystemClock.elapsedRealtime();
        Long last = lastRunElapsed.get(scenario.id);
        if (!test && last != null && now - last < MIN_RUN_INTERVAL_MS) return;
        running.add(scenario.id);
        lastRunElapsed.put(scenario.id, now);
        Log.i(TAG, "Запуск «" + scenario.name + "»: " + reason);
        runner.run(scenario, id -> handler.post(() -> {
            running.remove(id);
            toast("Сценарий " + scenario.name + " выполнен");
        }));
    }

    private List<ScenarioDefinition> enabledScenarios() {
        List<ScenarioDefinition> result = new ArrayList<>();
        for (ScenarioDefinition scenario : scenarios) {
            if (scenario.enabled && scenario.hasSteps()) result.add(scenario);
        }
        return result;
    }

    private void updateLightListening() {
        boolean needed = usesLightLevel();
        if (needed && lightSubscription == null) {
            lightSubscription = environment.light(this::onLightLevel);
        } else if (!needed && lightSubscription != null) {
            stopLightListening();
        }
    }

    private void stopLightListening() {
        if (lightSubscription == null) return;
        lightSubscription.close();
        lightSubscription = null;
        handler.post(() -> lastLight = ScenarioPolicy.LightState.HOLD);
    }
}
