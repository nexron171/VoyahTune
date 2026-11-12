package ru.big.town.anative;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import ru.big.town.common.ScenarioProtocol;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Runs steps in order and counts command outcomes independently of pauses. */
final class ScenarioRunner {
    interface Listener {
        void onFinished(String scenarioId, int accepted, int total);
    }

    interface Scheduler {
        void post(Runnable task, long delayMillis);

        void clear();
    }

    private final ScenarioEnvironment environment;
    private final Scheduler scheduler;
    private final Consumer<String> log;
    private volatile boolean closed;

    ScenarioRunner(ScenarioEnvironment environment) {
        this(
                environment,
                new Scheduler() {
                    private final Handler handler = new Handler(Looper.getMainLooper());

                    @Override
                    public void post(Runnable task, long delayMillis) {
                        handler.postDelayed(task, delayMillis);
                    }

                    @Override
                    public void clear() {
                        handler.removeCallbacksAndMessages(null);
                    }
                },
                message -> Log.i("ScenarioRunner", message));
    }

    ScenarioRunner(ScenarioEnvironment environment, Scheduler scheduler, Consumer<String> log) {
        this.environment = environment;
        this.scheduler = scheduler;
        this.log = log;
    }

    void close() {
        closed = true;
        scheduler.clear();
    }

    void run(ScenarioDefinition scenario, Listener listener) {
        runStep(scenario, 0, 0, 0, listener);
    }

    private void runStep(
            ScenarioDefinition scenario,
            int index,
            int acceptedCount,
            int total,
            Listener listener) {
        if (closed) return;
        if (index >= scenario.steps.size()) {
            listener.onFinished(scenario.id, acceptedCount, total);
            return;
        }
        ScenarioDefinition.Step step = scenario.steps.get(index);
        if (step.isPause()) {
            int seconds = ScenarioProtocol.clampPauseSeconds(step.seconds);
            log.accept("Сценарий «" + scenario.name + "»: пауза " + seconds + " с");
            scheduler.post(
                    () -> runStep(scenario, index + 1, acceptedCount, total, listener),
                    seconds * 1000L);
            return;
        }
        AtomicBoolean advanced = new AtomicBoolean();
        ScenarioActionExecutor.Completion completion =
                (accepted, error) -> {
                    if (closed || !advanced.compareAndSet(false, true)) return;
                    log.accept(
                            "Сценарий «"
                                    + scenario.name
                                    + "»: "
                                    + step.value
                                    + " — "
                                    + (accepted ? "отправлено" : error));
                    scheduler.post(
                            () ->
                                    runStep(
                                            scenario,
                                            index + 1,
                                            acceptedCount + (accepted ? 1 : 0),
                                            total + 1,
                                            listener),
                            0);
                };
        try {
            environment.execute(step.value, completion);
        } catch (RuntimeException exception) {
            completion.onFinished(false, "Не удалось выполнить действие: " + step.value);
        }
    }
}
