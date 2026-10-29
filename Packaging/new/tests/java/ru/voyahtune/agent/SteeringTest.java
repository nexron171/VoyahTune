package ru.voyahtune.agent;

import java.util.ArrayList;
import java.util.List;
import ru.voyahtune.agent.policy.MediaKeyDelivery;
import ru.voyahtune.agent.policy.SteeringMedia;
import ru.voyahtune.agent.policy.SteeringPress;
import ru.voyahtune.agent.runtime.ModuleScope;

public final class SteeringTest {
    public static void main(String[] arguments) throws Exception {
        for (int failureAction : new int[] {-1, 0, 1}) {
            List<Integer> delivered = new ArrayList<>();
            boolean handled = MediaKeyDelivery.dispatch(85, (action, code) -> {
                if (action == failureAction) {
                    throw new IllegalStateException("audio transport failed");
                }
                delivered.add(action);
            });
            require(handled == (failureAction != 0), "Only a failed DOWN can fall back");
            require(delivered.equals(failureAction == 0 ? List.of()
                    : failureAction == 1 ? List.of(0) : List.of(0, 1)), "Unexpected media delivery");
        }
        int[] decisions = {0};
        SteeringMedia media = new SteeringMedia(new SteeringMedia.Transport() {
            @Override
            public SteeringMedia.Decision decide(String command, int keyCode) {
                decisions[0]++;
                return SteeringMedia.decision(keyCode, "keymanager", keyCode);
            }

            @Override
            public boolean dispatch(int keyCode) {
                return false;
            }
        });
        require(!media.consume(6, 0, 0) && !media.consume(6, 0, 1) && !media.consume(6, 1, 0),
                "Failed dispatch must preserve the native route through UP");
        require(decisions[0] == 1, "Repeat must not ask provider twice");
        require(!SteeringMedia.allowedProxyKey(87, true) && SteeringMedia.allowedProxyKey(85, true)
                && !SteeringMedia.allowedProxyKey(1, false), "Proxy key admission");
        List<Runnable> timers = new ArrayList<>();
        ModuleScope.Scheduler scheduler = (delay, task) -> {
            timers.add(task);
            return () -> {};
        };
        List<String> actions = new ArrayList<>();
        String[] configuration = {"short", "long"};
        SteeringPress press = new SteeringPress(scheduler, () -> configuration[0], () -> configuration[1], actions::add);
        press.down(0);
        press.close();
        timers.get(0).run();
        require(actions.isEmpty(), "A cancelled long press must have no effect even if its timer was queued");
        press.down(0);
        configuration[1] = "changed";
        timers.get(1).run();
        require(actions.equals(List.of("long")) && press.up(), "Long action belongs to DOWN snapshot");
        press.down(0);
        configuration[0] = "current-short";
        require(press.up() && actions.equals(List.of("long", "current-short")), "Short action reads current setting");
        timers.get(2).run();
        require(actions.size() == 2, "UP must cancel the long timer");
        System.out.println("SteeringTest: passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
