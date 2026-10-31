package ru.big.town.updater.ui;

import org.json.JSONObject;

/** Authenticated access to the cluster sound transaction owned by the root service. */
public final class TurnSoundClient {
    public JSONObject call(
            String action, String sound, int volume, java.util.function.Consumer<String> progress)
            throws Exception {
        JSONObject request =
                new JSONObject()
                        .put("command", "turn_sound")
                        .put("action", action)
                        .put("progress", true);
        if ("apply".equals(action)) {
            request.put("sound", sound).put("volume", volume);
        }
        return new RootClient()
                .call(request, event -> progress.accept(event.optString("stage")))
                .getJSONObject("turnSound");
    }
}
