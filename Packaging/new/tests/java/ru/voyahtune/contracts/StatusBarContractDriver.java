package ru.voyahtune.contracts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ru.voyahtune.agent.policy.StatusBarController;

final class StatusBarContractDriver {
    static JsonObject execute(JsonObject input) {
        JsonObject initial = input.getAsJsonObject("initial");
        Fixture fixture = new Fixture();
        fixture.hide = initial.get("hide").getAsBoolean();
        for (int display = 0; display < 2; display++) fixture.top[display] = initial.getAsJsonArray("top").get(display).getAsString();
        StatusBarController controller = new StatusBarController(fixture);
        input.getAsJsonArray("events").forEach(value -> {
            JsonObject event = value.getAsJsonObject();
            switch (event.get("action").getAsString()) {
                case "reconcile": controller.reconcile(); break;
                case "setHide": fixture.hide = event.get("value").getAsBoolean(); break;
                case "top": fixture.top[event.get("display").getAsInt()] = event.get("value").getAsString(); break;
                case "advance": fixture.scheduler.advance(event.get("milliseconds").getAsLong()); break;
                default: throw new IllegalArgumentException("Unknown status bar event");
            }
        });
        JsonObject result = new JsonObject();
        JsonArray visible = new JsonArray();
        for (boolean value : fixture.visible) visible.add(value);
        result.add("visible", visible);
        result.add("reports", fixture.reports);
        return result;
    }

    static final class Fixture implements StatusBarController.IO {
        final String[] top = {"", ""};
        final boolean[] visible = {true, true};
        final long[] reveal = {0, 0};
        final JsonArray reports = new JsonArray();
        final ManualScheduler scheduler = new ManualScheduler();
        boolean hide;
        @Override public String top(int display) { return top[display]; }
        @Override public boolean hideSystemBars() { return hide; }
        @Override public long revealRemaining(int display) { return reveal[display]; }
        @Override public boolean stockHidden(String activity, int display) { return activity.equals("Camera") || activity.equals("Video"); }
        @Override public void apply(int display, boolean value, Object receiver) { visible[display] = value; }
        @Override public void reportHidden(int display, boolean hidden) {
            JsonArray report = new JsonArray(); report.add(display); report.add(hidden); reports.add(report);
        }
        @Override public void schedule(long delay, Runnable action) { scheduler.schedule(delay, action); }
        @Override public void error(Exception failure) { throw new AssertionError(failure); }
    }
}
