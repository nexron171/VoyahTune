package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

public class ScenarioRunnerTest {
    private static final class Queue implements ScenarioRunner.Scheduler {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final List<Long> delays = new ArrayList<>();

        public void post(Runnable task, long delay) {
            tasks.add(task);
            delays.add(delay);
        }

        public void clear() {
            tasks.clear();
        }

        void drain() {
            while (!tasks.isEmpty()) tasks.remove().run();
        }
    }

    private ScenarioEnvironment environment(
            BiConsumer<String, ScenarioActionExecutor.Completion> execute) {
        return (ScenarioEnvironment)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ScenarioEnvironment.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("execute"))
                                execute.accept(
                                        (String) arguments[0],
                                        (ScenarioActionExecutor.Completion) arguments[1]);
                            return null;
                        });
    }

    private ScenarioDefinition scenario() {
        return new ScenarioDefinition(
                "winter",
                "Зима",
                true,
                true,
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList(
                        new ScenarioDefinition.Step("action", "wheel_heat:on", 0),
                        new ScenarioDefinition.Step("pause", "", 3),
                        new ScenarioDefinition.Step("action", "drive:SPORT", 0)));
    }

    @Test
    public void failedStepsContinueAndDuplicateCallbacksDoNotInflateCounts() {
        Queue queue = new Queue();
        List<String> calls = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        ScenarioRunner runner =
                new ScenarioRunner(
                        environment(
                                (action, completion) -> {
                                    calls.add(action);
                                    completion.onFinished(
                                            action.startsWith("wheel"), "Ошибка отправки");
                                    completion.onFinished(true, null);
                                }),
                        queue,
                        text -> {});
        runner.run(
                scenario(),
                (id, accepted, total) ->
                        notices.add(ActionNoticeText.scenario("Зима", accepted, total)));
        queue.drain();
        assertEquals(Arrays.asList("wheel_heat:on", "drive:SPORT"), calls);
        assertEquals(
                Collections.singletonList("Сценарий «Зима»: выполнено 1 из 2 действий"), notices);
        assertTrue(queue.delays.contains(3000L));
    }

    @Test
    public void thrownActionIsCountedAndClosingCancelsPendingSteps() {
        Queue queue = new Queue();
        int[] counts = {-1, -1};
        ScenarioRunner runner =
                new ScenarioRunner(
                        environment(
                                (action, completion) -> {
                                    throw new IllegalStateException("transport");
                                }),
                        queue,
                        text -> {});
        runner.run(
                scenario(),
                (id, accepted, total) -> {
                    counts[0] = accepted;
                    counts[1] = total;
                });
        queue.drain();
        assertArrayEquals(new int[] {0, 2}, counts);
        counts[0] = -1;
        runner.run(scenario(), (id, accepted, total) -> counts[0] = accepted);
        runner.close();
        queue.drain();
        assertEquals(-1, counts[0]);
    }
}
