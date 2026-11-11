package ru.big.town.updater.ui;

import org.json.JSONObject;

/** Read-only access to the existing authenticated root-service status endpoint. */
public final class SystemStatusClient {
    private final RootClient client = new RootClient();

    public JSONObject read() throws Exception {
        return client.call(new JSONObject().put("command", "status"));
    }
}
