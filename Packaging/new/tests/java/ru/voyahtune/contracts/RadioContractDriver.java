package ru.voyahtune.contracts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import ru.voyahtune.agent.policy.RadioStation;
import ru.voyahtune.agent.policy.RadioRestoreController;
import ru.voyahtune.agent.policy.RadioWidgetController;

final class RadioContractDriver {
    static JsonObject execute(String contract, JsonObject input) throws Exception {
        State state = new State(input.getAsJsonObject("initial"));
        state.widget = contract.equals("radio.widget");
        JsonObject result = new JsonObject();
        if (contract.equals("radio.restore")) {
            RadioRestoreController controller = new RadioRestoreController(state, state.saved);
            for (JsonElement item : input.getAsJsonArray("events")) {
                JsonObject event = item.getAsJsonObject();
                switch (event.get("action").getAsString()) {
                    case "observe": state.current = station(event.get("station")); controller.observe(state.current); break;
                    case "select": controller.select(station(event.get("station"))); break;
                    case "resume": controller.resume(() -> state.fallbacks++, false); break;
                    case "advance": state.scheduler.advance(event.get("milliseconds").getAsLong()); break;
                    case "ready": state.ready = event.get("value").getAsBoolean(); break;
                    case "focus": state.focus = event.get("value").getAsBoolean(); break;
                    case "diskFail": state.diskFail = event.get("value").getAsBoolean(); break;
                    case "pause": controller.pause(); break;
                    case "close": controller.close(); break;
                    case "scan": controller.scan(); break;
                    case "scanFinished": controller.scanFinished(); break;
                    default: throw new IllegalArgumentException("Unknown radio restore event");
                }
            }
            result.add("writes", state.writes);
            result.addProperty("plays", state.plays);
            result.addProperty("fallbacks", state.fallbacks);
            result.add("owned", json(state.owned));
        } else {
            RadioWidgetController controller = new RadioWidgetController(state);
            JsonArray responses = new JsonArray();
            for (JsonElement item : input.getAsJsonArray("commands")) {
                JsonObject command = item.getAsJsonObject();
                RadioWidgetController.Response response = controller.execute(command.get("action").getAsString(), station(command.get("station")));
                JsonObject value = new JsonObject();
                value.addProperty("schema", 1);
                value.addProperty("fm", response.fm);
                value.addProperty("freq", response.frequency);
                value.addProperty("name", response.name);
                value.addProperty("ready", response.ready);
                value.addProperty("playing", response.playing);
                value.addProperty("canSkip", response.canSkip);
                value.addProperty("message", response.message);
                if (response.accepted != null) value.addProperty("accepted", response.accepted);
                if (response.requested != null) {
                    value.addProperty("requestedFreq", response.requested.frequency);
                    value.addProperty("requestedFm", response.requested.fm);
                }
                responses.add(value);
            }
            result.add("responses", responses);
            result.addProperty("pauses", state.pauses);
        }
        result.add("tunes", state.tunes);
        return result;
    }

    static RadioStation station(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonObject()) return null;
        JsonObject station = value.getAsJsonObject();
        return new RadioStation(bool(station, "fm"), integer(station, "freq"), bool(station, "rds"), integer(station, "pi"),
                Boolean.TRUE.equals(bool(station, "ta")), station.has("name") && !station.get("name").isJsonNull() ? station.get("name").getAsString() : null);
    }

    private static Boolean bool(JsonObject value, String key) {
        JsonElement field = value.get(key);
        return field != null && field.isJsonPrimitive() && field.getAsJsonPrimitive().isBoolean() ? field.getAsBoolean() : null;
    }

    private static Integer integer(JsonObject value, String key) {
        JsonElement field = value.get(key);
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isNumber()) return null;
        try { return field.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException invalid) { return null; }
    }

    static JsonElement json(RadioStation station) {
        if (station == null) return JsonNull.INSTANCE;
        JsonObject value = new JsonObject();
        if (station.fm != null) value.addProperty("fm", station.fm);
        if (station.frequency != null) value.addProperty("freq", station.frequency);
        if (station.rds != null) value.addProperty("rds", station.rds);
        if (station.programmeId != null) value.addProperty("pi", station.programmeId);
        value.addProperty("ta", station.trafficAnnouncement);
        if (station.name != null) value.addProperty("name", station.name);
        return value;
    }

    static final class State implements RadioRestoreController.IO, RadioWidgetController.IO {
        final ManualScheduler scheduler = new ManualScheduler();
        final JsonArray tunes = new JsonArray();
        final JsonArray writes = new JsonArray();
        final List<RadioStation> stations = new ArrayList<>();
        RadioStation saved;
        RadioStation current;
        RadioStation owned;
        RadioStation selected;
        boolean ready;
        boolean focus;
        boolean playing;
        boolean diskFail;
        boolean widget;
        int plays;
        int fallbacks;
        int pauses;

        State(JsonObject initial) {
            saved = station(initial.get("saved"));
            current = station(initial.get("current"));
            ready = Boolean.TRUE.equals(bool(initial, "ready"));
            focus = Boolean.TRUE.equals(bool(initial, "focus"));
            playing = Boolean.TRUE.equals(bool(initial, "playing"));
            if (initial.has("list")) for (JsonElement value : initial.getAsJsonArray("list")) stations.add(station(value));
        }
        @Override public long now() { return scheduler.now(); }
        @Override public void later(long delay, Runnable action) { scheduler.schedule(delay, action); }
        @Override public RadioStation current() { return current; }
        @Override public boolean ready() { return ready; }
        @Override public boolean acquireFocus() { return focus; }
        @Override public boolean hasFocus() { return focus; }
        @Override public boolean isPlaying() { return playing; }
        @Override public boolean save(RadioStation station) {
            if (diskFail) return false;
            writes.add(json(station)); saved = station; return true;
        }
        @Override public void tune(RadioStation station) {
            JsonObject value = json(station).getAsJsonObject();
            if (widget) value.remove("name");
            tunes.add(value); owned = station;
        }
        @Override public void playCurrent() { plays++; }
        @Override public void stageSelected(RadioStation station) { selected = station; }
        @Override public void clearSelected() { selected = null; }
        @Override public void cancelOwned() { owned = null; }
        @Override public void releaseOwned() { owned = null; }
        @Override public void log(String event) {}
        @Override public RadioStation saved() { return saved; }
        @Override public List<RadioStation> list() { return stations; }
        @Override public void pause() { pauses++; }
    }
}
