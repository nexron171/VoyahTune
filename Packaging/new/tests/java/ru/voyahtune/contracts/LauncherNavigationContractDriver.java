package ru.voyahtune.contracts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ru.voyahtune.agent.policy.LauncherNavigation;

final class LauncherNavigationContractDriver {
    static JsonObject execute(JsonObject input) throws Throwable {
        JsonArray calls = new JsonArray();
        LauncherNavigation navigation = new LauncherNavigation(false);
        LauncherNavigation.Button button = input.get("slot").getAsString().equals("home") ? LauncherNavigation.Button.HOME : LauncherNavigation.Button.ALL_APPS;
        String role = input.get("slot").getAsString();
        boolean longPress = input.get("gesture").getAsString().equals("long");
        LauncherNavigation.Action action = navigation.action(input.get("display").getAsInt(), button, input.get("voyahtuneShort").getAsBoolean(), longPress);
        if (action == LauncherNavigation.Action.DASHBOARD) calls.add("voyahtune:" + role);
        else if (action == LauncherNavigation.Action.STOCK && longPress) navigation.stock(() -> calls.add("stock:" + role));
        else calls.add("stock:" + role);
        JsonObject result = new JsonObject();
        result.add("calls", calls);
        result.addProperty("stockBypassCleared", !navigation.bypassing());
        return result;
    }
}
