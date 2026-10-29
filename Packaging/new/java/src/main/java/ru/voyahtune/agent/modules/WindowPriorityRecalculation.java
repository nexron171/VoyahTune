package ru.voyahtune.agent.modules;

import java.util.Objects;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.OemAccess;

public final class WindowPriorityRecalculation {
    private final Object activityManager;

    public WindowPriorityRecalculation(Object activityManager) throws ReflectiveOperationException {
        this.activityManager = Objects.requireNonNull(activityManager);
        ClassLoader loader = activityManager.getClass().getClassLoader();
        new MethodAccess(name -> Class.forName(name, false, loader))
                .require(activityManager.getClass().getName(), "updateOomAdjLocked", "java.lang.String");
    }

    public void recompute() throws Exception {
        synchronized (activityManager) {
            OemAccess.call(activityManager, "updateOomAdjLocked", "voyahtune-windows");
        }
    }
}
