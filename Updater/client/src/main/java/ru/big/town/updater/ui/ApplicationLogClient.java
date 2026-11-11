package ru.big.town.updater.ui;

import org.json.JSONObject;

/** Uses the authenticated RestoreMode connection to the system log recorder. */
public final class ApplicationLogClient {
    private final RootClient client = new RootClient();

    public JSONObject call(String action, String appVersion) throws Exception {
        JSONObject request = new JSONObject();
        request.put("command", "application_log");
        request.put("action", action);
        if ("upload".equals(action)) {
            request.put("app_version", appVersion);
        }
        try {
            return client.call(request).getJSONObject("logging");
        } catch (UnsupportedOperationException error) {
            throw new UnsupportedOperationException(
                    "Для записи системных логов обновите VoyahTune через USB", error);
        }
    }
}
