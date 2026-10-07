package ru.big.town.anative;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

import ru.big.town.common.ScenarioProtocol;

/**
 * Последовательное выполнение шагов сценария. Пауза — обычная задержка Handler, следующее
 * действие стартует только после завершения предыдущего.
 */
final class ScenarioRunner {
    private static final String TAG = "$$$ ScenarioRunner $$$";

    interface Listener {
        void onFinished(String scenarioId);
    }

    private final ScenarioActionExecutor executor;
    private final Handler handler = new Handler(Looper.getMainLooper());

    ScenarioRunner(SetModesService service) {
        this.executor = new ScenarioActionExecutor(service);
    }

    void run(ScenarioDefinition scenario, Listener listener) {
        Log.i(TAG, "Сценарий «" + scenario.name + "»: шагов " + scenario.steps.size());
        runStep(scenario, 0, listener);
    }

    private void runStep(ScenarioDefinition scenario, int index, Listener listener) {
        if (index >= scenario.steps.size()) {
            Log.i(TAG, "Сценарий «" + scenario.name + "» завершён");
            listener.onFinished(scenario.id);
            return;
        }
        ScenarioDefinition.Step step = scenario.steps.get(index);
        if (step.isPause()) {
            // Логируем и заданное, и фактическое значение: по logcat видно реальную выдержку.
            int seconds = ScenarioProtocol.clampPauseSeconds(step.seconds);
            Log.i(TAG, "Сценарий «" + scenario.name + "»: пауза " + seconds + " с (задано "
                    + step.seconds + ")");
            handler.postDelayed(() -> {
                Log.i(TAG, "Сценарий «" + scenario.name + "»: пауза " + seconds + " с истекла");
                runStep(scenario, index + 1, listener);
            }, seconds * 1000L);
            return;
        }
        Log.i(TAG, "Сценарий «" + scenario.name + "» шаг " + (index + 1) + "/"
                + scenario.steps.size() + ": " + step.value);
        // Ровно одно продвижение на шаг: если асинхронное действие сообщит о завершении дважды,
        // сценарий иначе перескочит следующий шаг (в том числе паузу).
        final AtomicBoolean advanced = new AtomicBoolean();
        executor.execute(step.value, (accepted, error) -> {
            if (!advanced.compareAndSet(false, true)) return;
            if (!accepted && error != null) {
                Log.w(TAG, "Сценарий «" + scenario.name + "»: " + step.value + " — " + error);
            }
            runStep(scenario, index + 1, listener);
        });
    }
}
