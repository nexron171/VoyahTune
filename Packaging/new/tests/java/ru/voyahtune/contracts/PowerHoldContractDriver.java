package ru.voyahtune.contracts;

import com.google.gson.JsonObject;
import ru.voyahtune.agent.policy.PowerHoldController;

final class PowerHoldContractDriver {
    static JsonObject execute(JsonObject input) {
        Manager manager = new Manager(input);
        PowerHoldController controller = new PowerHoldController(() -> {
            if (DriveResetContractDriver.flag(input, "uninitialized")) throw new IllegalStateException("Uninitialized manager");
            return manager;
        }, () -> manager.now);
        PowerHoldController.Response response = controller.request("activate", 103000, DriveResetContractDriver.flag(input, "engineExtender"));
        JsonObject result = new JsonObject();
        result.addProperty("outcome", response.outcome);
        result.addProperty("activationCalls", manager.activations);
        return result;
    }

    static final class Manager implements PowerHoldController.Manager {
        final JsonObject options;
        long now = 100000;
        int status;
        int activations;
        boolean engine;
        Manager(JsonObject options) { this.options = options; status = DriveResetContractDriver.integer(options, "status", 0); }
        @Override public Boolean parking() { return !options.has("parking") || options.get("parking").getAsBoolean(); }
        @Override public int battery() {
            if (DriveResetContractDriver.flag(options, "slowValidation")) now += 4000;
            return DriveResetContractDriver.integer(options, "soc", 80);
        }
        @Override public int status() { return status; }
        @Override public void activate(boolean engineExtender) {
            activations++;
            engine = engineExtender;
            status = 2;
            if (DriveResetContractDriver.flag(options, "lostReply")) throw new IllegalStateException("Reply lost after arming");
        }
    }
}
