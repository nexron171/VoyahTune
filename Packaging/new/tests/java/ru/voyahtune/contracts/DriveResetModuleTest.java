package ru.voyahtune.contracts;

import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.Map;

public final class DriveResetModuleTest {
    public static void main(String[] arguments) throws Throwable {
        verify();
        System.out.println("DriveResetModuleTest: passed");
    }

    static void verify() throws Throwable {
        Method reset = DriveResetContractDriver.Memory.class.getDeclaredMethod("resetSettings");
        Method choice = DriveResetContractDriver.Fragment.class.getDeclaredMethod("setDriveMode", int.class);
        Method power = DriveResetContractDriver.Fragment.class.getDeclaredMethod("setPowerMode", int.class);
        Method hint = DriveResetContractDriver.Fragment.class.getDeclaredMethod("onHintSwitchClick", DriveResetContractDriver.State.class, int.class);
        Method recycle = DriveResetContractDriver.Fragment.class.getDeclaredMethod("setEnergyRecyclerMode", int.class);
        Method resume = DriveResetContractDriver.Activity.class.getDeclaredMethod("onResume");
        try (DriveResetContractDriver.Harness harness = new DriveResetContractDriver.Harness(new JsonObject())) {
            harness.invoke(reset, new DriveResetContractDriver.Memory());
            require(harness.bus.vehicle.getInt("DRIVING_MODE_SET", -1) == 3 && harness.bus.sends == 1, "Automatic reset did not select SPORT");
            harness.invoke(choice, new DriveResetContractDriver.Fragment(), 1);
            require(Boolean.TRUE.equals(harness.bus.vehicle.values.get("__vt_user"))
                    && !harness.bus.vehicle.values.containsKey("__vt_auto")
                    && harness.bus.vehicle.getInt("DRIVING_MODE_SET", -1) == 1, "UI selection was replaced or not tagged");
            harness.invoke(power, new DriveResetContractDriver.Fragment(), 4);
            require("SREV".equals(harness.saved.get("energy")), "UI power selection not persisted");
            RuntimeException binderFailure = new IllegalStateException("owned-Binder-failure");
            harness.bus.failure = binderFailure;
            int previousSends = harness.bus.sends;
            try {
                harness.invoke(reset, new DriveResetContractDriver.Memory());
                throw new AssertionError("Reset lost Binder error");
            } catch (RuntimeException failure) {
                require(failure == binderFailure && harness.bus.sends == previousSends + 1, "Reset replayed Binder command");
            }
            try { harness.invoke(power, new DriveResetContractDriver.Fragment(), 2); }
            catch (RuntimeException failure) { require(failure == binderFailure, "User error identity changed"); }
            require("EV".equals(harness.saved.get("energy")), "UI energy must be persisted before OEM send");
            try { harness.invoke(recycle, new DriveResetContractDriver.Fragment(), 4); }
            catch (RuntimeException failure) { require(failure == binderFailure, "Recycler error identity changed"); }
            require(!harness.saved.containsKey("recycle"), "Failed recuperation command was persisted");
            harness.bus.failure = null;
            harness.invoke(hint, new DriveResetContractDriver.Fragment(), DriveResetContractDriver.State.OTHER, 3);
            require("EV".equals(harness.saved.get("energy")), "User scope leaked after failed power choice");
            harness.invoke(recycle, new DriveResetContractDriver.Fragment(), 3);
            require("MEDIUM".equals(harness.saved.get("recycle")), "Successful recuperation not persisted");
            DriveResetContractDriver.Bundle ordinary = new DriveResetContractDriver.Bundle();
            ordinary.values.put("DRIVING_MODE_SET", 2);
            harness.send(ordinary);
            require(harness.bus.vehicle == ordinary, "Automatic scope leaked after failed reset");
            DriveResetContractDriver.Activity activity = new DriveResetContractDriver.Activity();
            require(harness.invoke(resume, activity).equals(42) && activity.originals == 1 && activity.updates == 1, "Menu changed original result or call count");
            harness.runtime.apply("code", 2, java.util.List.of("drive-reset"), Map.of());
            harness.invoke(resume, activity);
            require(activity.updates == 2, "Menu callback lost after revision change");
            harness.supported = false;
            harness.invoke(reset, new DriveResetContractDriver.Memory());
            require(harness.bus.vehicle == harness.stock && harness.stock.values.equals(harness.stockValues), "Unsupported model was modified");
            harness.runtime.close();
            harness.invoke(resume, activity);
            require(activity.updates == 2 && harness.backend.installed.isEmpty(), "Closed module retained callbacks");
        }
        try (DriveResetContractDriver.Harness harness = new DriveResetContractDriver.Harness(new JsonObject(), target -> !target.getName().equals("onResume"))) {
            harness.invoke(reset, new DriveResetContractDriver.Memory());
            require(harness.bus.vehicle.getInt("DRIVING_MODE_SET", -1) == 3, "Optional menu rejection disabled reset");
        }
        PowerHoldContractDriver.Manager manager = new PowerHoldContractDriver.Manager(new JsonObject());
        ru.voyahtune.agent.policy.PowerHoldController controller = new ru.voyahtune.agent.policy.PowerHoldController(() -> manager, () -> manager.now);
        require(controller.request("status", 103000, false).status == 0 && manager.activations == 0, "Status request sent activation");
        require(controller.request("invalid", 103000, false).outcome == 5 && manager.activations == 0, "Unknown operation sent activation");
        require(controller.request("activate", 99999, false).outcome == 5 && manager.activations == 0, "Expired request sent activation");
        require(controller.request("activate", 103000, true).outcome == 6 && manager.engine && manager.activations == 1, "Engine opt-in was lost");
        require(controller.request("activate", 103000, false).outcome == 6 && manager.activations == 1, "Armed manager was activated twice");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
