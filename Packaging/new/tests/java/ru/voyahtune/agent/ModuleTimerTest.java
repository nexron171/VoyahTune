package ru.voyahtune.agent;

import java.util.ArrayList;
import java.util.List;
import ru.voyahtune.agent.runtime.ModuleScope;

public final class ModuleTimerTest {
    public static void main(String[] arguments) throws Exception {
        verify();
        System.out.println("ModuleTimerTest: passed");
    }

    public static void verify() throws Exception {
        List<String> effects = new ArrayList<>();
        List<Runnable> pending = new ArrayList<>();
        ModuleScope scope = new ModuleScope((delay, task) -> {
            if (delay == 0) task.run();
            else pending.add(task);
            return () -> {};
        }, 1);
        scope.schedule(0, () -> effects.add("installed"));
        require(effects.isEmpty(), "Installed work ran before configuration committed");
        scope.configure(1, () -> {
            require(effects.isEmpty(), "Installed work ran while configure was preparing");
            scope.schedule(0, () -> effects.add("configured"));
        });
        require(effects.equals(List.of("installed", "configured")), "First configuration lost installed/configured timers");
        scope.schedule(10, () -> effects.add("old"));
        scope.configure(2, () -> scope.schedule(0, () -> effects.add("second")));
        pending.forEach(Runnable::run);
        require(effects.equals(List.of("installed", "configured", "second")), "Revision did not cancel old work or lost new work");
        AutoCloseable cancelled = scope.schedule(10, () -> effects.add("cancelled"));
        cancelled.close();
        pending.forEach(Runnable::run);
        require(effects.equals(List.of("installed", "configured", "second")), "Cancelled queued work ran in the same configuration revision");
        scope.close();
        ModuleScope uncertain = new ModuleScope((delay, task) -> () -> { throw new Exception("timer-cancel-failed"); }, 1);
        uncertain.configure(1, () -> {});
        AutoCloseable broken = uncertain.schedule(10, () -> effects.add("broken"));
        try { broken.close(); throw new AssertionError("Timer cancellation failure was ignored"); }
        catch (Exception expected) { require(expected.getMessage().equals("timer-cancel-failed"), "Timer cancellation failure changed"); }
        try { uncertain.close(); throw new AssertionError("Module cleanup forgot a failed cancellation"); }
        catch (ModuleScope.CleanupException expected) { /* A failed delegate cannot be acknowledged as drained. */ }
        ModuleScope failed = new ModuleScope((delay, task) -> { task.run(); return () -> {}; }, 1);
        failed.schedule(0, () -> effects.add("failed-install"));
        try {
            failed.configure(1, () -> { throw new IllegalStateException("configuration-failed"); });
            throw new AssertionError("Configure failure was lost");
        } catch (IllegalStateException expected) {
            failed.close();
        }
        require(effects.equals(List.of("installed", "configured", "second")), "Failed preparation produced a side effect");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
