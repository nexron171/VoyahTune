package ru.voyahtune.contracts;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class GeometryLifecycleContractDriver {
    static JsonObject execute(JsonObject input) throws Throwable {
        if (input.has("package") || input.getAsJsonArray("events") != null && input.getAsJsonArray("events").toString().contains("fullscreen-")) {
            return ClientGeometryContractDriver.execute(input);
        }
        try (WindowGeometryContractDriver.Harness harness = new WindowGeometryContractDriver.Harness()) {
            harness.scheduler.advance(1000);
            JsonObject result = new JsonObject();
            if (input.has("initialDpi")) {
                harness.settings.put("voyahtune_dpi_packages", "org.app");
                harness.settings.put("voyahtune_dpi_org.app", input.get("initialDpi").getAsString());
                harness.reload();
                harness.settings.put("voyahtune_dpi_org.app", input.get("nextDpi").getAsString());
                harness.settings.put("voyahtune_win_left", "300");
                harness.reload();
                result.addProperty("dpi", harness.module.currentPolicy().density("org.app"));
                result.addProperty("partialApplied", harness.module.currentPolicy().left != 145);
            } else if (input.has("failure")) {
                harness.settings.put("voyahtune_fullscreen_apps", "org.app");
                harness.reload();
                WindowLayoutTest.Window window = new WindowLayoutTest.Window(1780, 675);
                window.failCompute = true;
                harness.layout(window);
                result.addProperty("stockFrameRestored", window.frames.mContentFrame.left == 0 && window.frames.mContentFrame.top == 0 && window.frames.mContentFrame.bottom == 720);
                result.addProperty("sharedFieldsRestored", window.attrs.width == 1780 && window.attrs.height == 675 && window.displayFrames.mStable.top == 0 && window.mRequestedWidth == 1780);
            } else {
                int reads = harness.reads;
                int traversals = harness.traversals;
                boolean reload = false;
                for (JsonElement event : input.getAsJsonArray("events")) {
                    switch (event.getAsString()) {
                        case "off": harness.events.screen(false); harness.scheduler.advance(0); break;
                        case "on": harness.events.screen(true); harness.scheduler.advance(0); break;
                        case "reload": reload = true; harness.events.reload(); break;
                        case "advance": harness.scheduler.advance(10000); break;
                        case "flush": harness.scheduler.advance(150); break;
                        default: throw new IllegalArgumentException("Unknown geometry lifecycle event");
                    }
                }
                if (reload) {
                    result.addProperty("settingsSnapshots", harness.reads - reads);
                    result.addProperty("traversals", harness.traversals - traversals);
                } else {
                    result.addProperty("pending", harness.scheduler.activeTasks());
                    result.addProperty("geometryAttached", harness.backend.installed.containsKey(harness.layout));
                }
            }
            return result;
        }
    }
}
