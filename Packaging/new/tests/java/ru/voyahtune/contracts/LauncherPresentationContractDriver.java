package ru.voyahtune.contracts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.policy.LauncherPresentation;

final class LauncherPresentationContractDriver {
    static JsonObject execute(JsonObject input) throws Exception {
        Access access = new Access();
        LauncherPresentation presentation = new LauncherPresentation(access);
        View first = new View();
        View second = new View();
        List<Object> slots = List.of(first, second);
        if (input.has("initialStyles")) presentation.icons(0, slots, strings(input.getAsJsonArray("packages")), strings(input.getAsJsonArray("initialStyles")));
        presentation.icons(input.get("display").getAsInt(), slots, strings(input.getAsJsonArray("packages")), strings(input.getAsJsonArray("styles")));
        JsonObject result = new JsonObject();
        JsonArray icons = new JsonArray();
        JsonArray sizes = new JsonArray();
        for (View view : List.of(first, second)) {
            icons.add(String.valueOf(view.properties.get("background")));
            sizes.add(WindowGeometryContractDriver.values(view.width, view.height));
        }
        result.add("icons", icons);
        result.add("sizes", sizes);
        presentation.close();
        return result;
    }

    private static List<String> strings(JsonArray array) {
        List<String> result = new ArrayList<>();
        array.forEach(value -> result.add(value.getAsString()));
        return result;
    }
    static final class View {
        final Map<String, Object> properties = new HashMap<>(Map.of("background", "OEM"));
        final List<Object> anchors = new ArrayList<>();
        int width = 116;
        int height = 90;
    }
    static final class Access implements LauncherPresentation.Access {
        @Override public Object read(Object owner, String property) {
            View view = (View) owner;
            if (property.equals("height")) return view.height;
            return view.properties.get(property);
        }
        @Override public void write(Object owner, String property, Object value) {
            View view = (View) owner;
            if (property.equals("height")) view.height = (Integer) value;
            else view.properties.put(property, value);
        }
        @Override public AutoCloseable retain(Object owner, Object state) {
            View view = (View) owner;
            view.anchors.add(state);
            return () -> view.anchors.remove(state);
        }
        @Override public Object icon(String target, int slot, String style) {
            return style.equals("navigation") ? "dock_navigation_icon" : style.equals("music") ? "dock_music_icon" : "default:" + target;
        }
        @Override public void invalidate(Object view) {}
        @Override public boolean layout(Object view) { return true; }
    }
}
