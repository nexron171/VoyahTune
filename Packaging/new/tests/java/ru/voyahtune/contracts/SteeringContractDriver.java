package ru.voyahtune.contracts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ru.voyahtune.agent.policy.SteeringMedia;
import ru.voyahtune.agent.policy.SteeringPress;

final class SteeringContractDriver {
    static JsonObject execute(String contract, JsonObject input) throws Exception {
        if (contract.equals("steering.press")) {
            return press(input);
        }
        if (contract.equals("steering.media-decision")) {
            JsonObject provider = input.get("provider").isJsonNull() ? null : input.getAsJsonObject("provider");
            SteeringMedia.Decision decision = SteeringMedia.decision(input.get("keyCode").getAsInt(),
                    provider == null ? null : provider.get("route").getAsString(),
                    provider == null ? 0 : provider.get("keyCode").getAsInt());
            JsonObject result = new JsonObject();
            result.addProperty("route", decision.route);
            result.addProperty("keyCode", decision.keyCode);
            return result;
        }
        int[] calls = new int[3];
        SteeringMedia media = new SteeringMedia(new SteeringMedia.Transport() {
            @Override
            public SteeringMedia.Decision decide(String command, int keyCode) {
                calls[0]++;
                return SteeringMedia.decision(keyCode, input.get("route").getAsString(), input.get("keyCode").getAsInt());
            }

            @Override
            public boolean dispatch(int keyCode) {
                calls[1]++;
                return !input.has("dispatchFails") || !input.get("dispatchFails").getAsBoolean();
            }
        });
        for (var value : input.getAsJsonArray("events")) {
            JsonArray event = value.getAsJsonArray();
            boolean down = event.get(0).getAsString().equals("down");
            if (!media.consume(3, down ? 0 : 1, event.get(1).getAsInt())) {
                calls[2]++;
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("providerCalls", calls[0]);
        result.addProperty("mediaDispatches", calls[1]);
        result.addProperty("originalKeyEvents", calls[2]);
        return result;
    }

    private static JsonObject press(JsonObject input) throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        JsonArray actions = new JsonArray();
        JsonArray handled = new JsonArray();
        try (SteeringPress press = new SteeringPress(scheduler, () -> input.get("short").getAsString(),
                () -> input.get("long").getAsString(), actions::add)) {
            for (var value : input.getAsJsonArray("events")) {
                JsonArray event = value.getAsJsonArray();
                switch (event.get(0).getAsString()) {
                    case "down":
                        press.down(event.get(1).getAsInt());
                        break;
                    case "up":
                        handled.add(press.up());
                        break;
                    case "advance":
                        scheduler.advance(event.get(1).getAsLong());
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown steering event");
                }
            }
        }
        JsonObject result = new JsonObject();
        result.add("actions", actions);
        result.add("handledUp", handled);
        return result;
    }
}
