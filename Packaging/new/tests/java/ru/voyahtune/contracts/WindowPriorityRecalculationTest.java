package ru.voyahtune.contracts;

import ru.voyahtune.agent.modules.WindowPriorityRecalculation;

public final class WindowPriorityRecalculationTest {
    public static void main(String[] arguments) throws Exception {
        recomputesWithAndroidElevenReason();
        preservesOriginalFailure();
        rejectsUnsupportedSignature();
        System.out.println("WindowPriorityRecalculationTest: passed");
    }

    private static void recomputesWithAndroidElevenReason() throws Exception {
        ActivityManager manager = new ActivityManager();
        WindowPriorityRecalculation recalculation = new WindowPriorityRecalculation(manager);
        require(manager.calls == 0, "Preparing memory protection changed process priorities");

        recalculation.recompute();

        require(manager.calls == 1, "Priority recalculation did not call ActivityManager once");
        require("voyahtune-windows".equals(manager.reason), "Priority recalculation lost its reason");
    }

    private static void preservesOriginalFailure() throws Exception {
        ActivityManager manager = new ActivityManager();
        manager.failure = new IllegalStateException("OEM priority recalculation failed");
        WindowPriorityRecalculation recalculation = new WindowPriorityRecalculation(manager);
        try {
            recalculation.recompute();
            throw new AssertionError("ActivityManager failure was swallowed");
        } catch (IllegalStateException failure) {
            require(failure == manager.failure, "ActivityManager failure was replaced");
            require(manager.calls == 1, "Failed priority recalculation was replayed");
        }
    }

    private static void rejectsUnsupportedSignature() throws Exception {
        UnsupportedActivityManager manager = new UnsupportedActivityManager();
        try {
            new WindowPriorityRecalculation(manager);
            throw new AssertionError("An incompatible ActivityManager signature was accepted");
        } catch (NoSuchMethodException expected) {
            require(manager.calls == 0, "Preparation invoked an incompatible overload");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class ActivityManager {
        int calls;
        String reason;
        IllegalStateException failure;

        private void updateOomAdjLocked(String reason) {
            require(Thread.holdsLock(this), "ActivityManager monitor was not held");
            calls++;
            this.reason = reason;
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class UnsupportedActivityManager {
        int calls;

        private void updateOomAdjLocked(int reason) {
            calls++;
        }
    }
}
