package ru.voyahtune.contracts;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.ClientGeometryModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ResourceGroup;

final class ClientGeometryContractDriver {
    static JsonObject execute(JsonObject input) throws Throwable {
        String name = DriveResetContractDriver.stringValue(input, "package", "org.app");
        try (Harness harness = new Harness(name)) {
            JsonObject result = new JsonObject();
            if (input.has("dpi")) {
                harness.map.scale = input.get("baseline").getAsFloat();
                harness.dpi = input.get("dpi").getAsInt();
                harness.flush();
                result.addProperty("scaleFactor", harness.map.scale);
                result.addProperty("surfaceUpscaled", harness.map.width != 1920 || harness.map.height != 720);
            } else {
                harness.fullscreen = true;
                harness.flush();
                harness.fullscreen = false;
                harness.changed.run();
                harness.flush();
                result.addProperty("originalWidthRestored",
                    harness.view.attributes.width == 1780 && harness.view.root.attributes.width == 1780);
                result.addProperty("dialogMutated",
                    harness.dialog.attributes.width != 400 || harness.dialog.root.attributes.width != 400);
            }
            return result;
        }
    }

    static final class Harness implements AutoCloseable, ClientGeometryModule.Platform {
        final ManualScheduler scheduler = new ManualScheduler();
        final FixtureHookBackend backend = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        final HookRegistry hooks = new HookRegistry(backend);
        final List<Runnable> main = new ArrayList<>();
        final List<View> extra = new ArrayList<>();
        final View view = new View(new Attributes(1780, 1));
        final View dialog = new View(new Attributes(400, 2));
        final MapWindow map = new MapWindow();
        final MapView mapView = new MapView(map);
        final String name;
        final ModuleRuntime runtime;
        Runnable changed;
        boolean fullscreen;
        int dpi;
        int closedObservers;
        boolean mainExecuting;
        Harness(String name) {
            this.name = name;
            MethodAccess methods = new MethodAccess(type -> {
                switch (type) {
                    case "android.view.ViewRootImpl":
                        return Root.class;
                    case "android.view.View":
                        return View.class;
                    case "android.view.ViewGroup":
                        return Group.class;
                    case "android.view.WindowManager$LayoutParams":
                        return Attributes.class;
                    case "com.yandex.mapkit.map.MapWindow":
                        return MapWindow.class;
                    case "com.yandex.mapkit.mapview.MapView":
                        return MapView.class;
                    case "com.yandex.mapkit.internal.MapKitBinding":
                        return Binding.class;
                    default:
                        return Class.forName(type);
                }
            });
            runtime =
                new ModuleRuntime("code", scheduler, id -> new ClientGeometryModule(methods, hooks, this));
            runtime.apply("code", 1, List.of("client." + name), Map.of());
            if (runtime.snapshot().phase == ModuleRuntime.Phase.FAILED)
                throw new AssertionError("Client failed", runtime.snapshot().failure);
        }
        @Override
        public String packageName() {
            return name;
        }
        @Override
        public boolean fullscreen() {
            return fullscreen;
        }
        @Override
        public int density() {
            return dpi;
        }
        @Override
        public List<?> roots() {
            List<View> roots = new ArrayList<>(List.of(view, dialog, mapView));
            roots.addAll(extra);
            return roots;
        }
        @Override
        public void update(Object raw, Object attributes) throws Exception {
            View view = (View) raw;
            view.attributes = (Attributes) attributes;
            try {
                backend.invoke(
                    Root.class.getDeclaredMethod("setLayoutParams", Attributes.class, boolean.class),
                    view.root, attributes, false);
            } catch (Exception failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new AssertionError(failure);
            }
        }
        @Override
        public void postMain(Runnable action) {
            main.add(action);
        }
        @Override
        public void drainMain(AutoCloseable cleanup) throws Exception {
            main.clear();
            cleanup.close();
        }
        @Override
        public void watch(ResourceGroup resources, Runnable changed) throws Exception {
            this.changed = changed;
            resources.own(() -> closedObservers++);
        }
        @Override
        public void error(Exception failure) {}
        void flush() {
            scheduler.advance(0);
            for (int attempts = 0; !main.isEmpty(); attempts++) {
                if (attempts > 100)
                    throw new AssertionError("Unbounded client replay");
                mainExecuting = true;
                try {
                    main.remove(0).run();
                } finally {
                    mainExecuting = false;
                }
                scheduler.advance(0);
            }
        }
        @Override
        public void close() throws Exception {
            runtime.close();
            hooks.close();
        }
    }

    static final class Attributes {
        int width;
        int type;
        Attributes() {}
        Attributes(int width, int type) {
            this.width = width;
            this.type = type;
        }
        int copyFrom(Attributes source) {
            width = source.width;
            type = source.type;
            return 0;
        }
    }
    static final class Root {
        Attributes attributes;
        int display;
        Root(Attributes attributes) {
            this.attributes = attributes;
        }
        int getDisplayId() {
            return display;
        }
        void setView(View view, Attributes attributes, View panel, int user) {
            this.attributes = attributes;
        }
        void setLayoutParams(Attributes attributes, boolean newView) {
            this.attributes = attributes;
        }
    }
    static class View {
        Attributes attributes;
        final Root root;
        View(Attributes attributes) {
            this.attributes = attributes;
            root = new Root(attributes);
        }
        Attributes getLayoutParams() {
            return attributes;
        }
        Root getViewRootImpl() {
            return root;
        }
    }
    static final class Group extends View {
        final List<View> children = new ArrayList<>();
        Group() {
            super(new Attributes(200, 2));
        }
        int getChildCount() {
            return children.size();
        }
        View getChildAt(int index) {
            return children.get(index);
        }
    }
    static final class MapView extends View {
        final MapWindow map;
        MapView(MapWindow map) {
            super(new Attributes(200, 2));
            this.map = map;
        }
        MapWindow getMapWindow() {
            return map;
        }
    }
    static final class MapWindow {
        float scale = 1;
        int width = 1920;
        int height = 720;
        boolean valid = true;
        boolean isValid() {
            return valid;
        }
        float getScaleFactor() {
            return scale;
        }
        void setScaleFactor(float scale) {
            this.scale = scale;
        }
        int width() {
            return width;
        }
        int height() {
            return height;
        }
    }
    static final class Binding {
        MapWindow createMapWindow() {
            return new MapWindow();
        }
        MapWindow createMapWindow(int flags) {
            return new MapWindow();
        }
    }
}
