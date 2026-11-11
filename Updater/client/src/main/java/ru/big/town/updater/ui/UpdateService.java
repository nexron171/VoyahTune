package ru.big.town.updater.ui;

import org.json.JSONObject;

/** Schema-1 JSON API; the remote service owns operations and their durable state. */
public interface UpdateService {
    JSONObject call(JSONObject request) throws Exception;
}
