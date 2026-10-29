package ru.voyahtune.contracts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.ClusterModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ResourceGroup;

final class ClusterContractDriver {
    static final ThreadLocal<Harness> active = new ThreadLocal<>();

    static JsonObject execute(String contract, JsonObject input) throws Throwable {
        try (Harness harness = new Harness(false, false, false)) {
            harness.flush();
            JsonObject result = new JsonObject();
            if (contract.equals("cluster.distance")) {
                result.addProperty("unit", (String) harness.backend.invoke(Formatter.class.getDeclaredMethod("toDistanceUnit", Context.class, int.class), null, null, input.get("meters").getAsInt()));
                return result;
            }
            for (var value : input.getAsJsonArray("events")) {
                JsonArray event = value.getAsJsonArray();
                if (event.get(0).getAsString().equals("enabled")) { harness.enabled = event.get(1).getAsBoolean(); harness.changed.run(); harness.flush(); }
                else if (event.get(0).getAsString().equals("send")) harness.send(GuideState.valueOf(event.get(1).getAsString()));
                else throw new IllegalArgumentException("Unknown cluster event");
            }
            JsonArray states = new JsonArray();
            for (Info info : harness.writes) states.add(info.state.name());
            result.add("guideStates", states);
            result.addProperty("sourceUnchanged", harness.sources.stream().allMatch(source -> source.info.state == source.expected));
            return result;
        }
    }

    static final class Harness implements AutoCloseable, ClusterModule.Platform {
        final FixtureHookBackend backend = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        final HookRegistry hooks = new HookRegistry(backend);
        final ManualScheduler scheduler = new ManualScheduler();
        final ArrayDeque<Runnable> main = new ArrayDeque<>();
        final Binder binder = new Binder();
        final List<Info> writes = new ArrayList<>();
        final List<Source> sources = new ArrayList<>();
        final ModuleRuntime runtime;
        Runnable changed;
        boolean late;
        boolean enabled = true;
        int closedObservers;

        Harness(boolean late, boolean noFormatter, boolean noWriter) {
            active.set(this);
            this.late = late;
            MethodAccess methods = new MethodAccess(name -> {
                switch (name) {
                    case "com.qinggan.cluster.GuidanceFormat":
                        if (noFormatter) throw new ClassNotFoundException(name);
                        return Formatter.class;
                    case ClusterModule.CONTRACT:
                        if (noWriter) throw new ClassNotFoundException(name);
                        return Contract.class;
                    case ClusterModule.CONTRACT + "$Stub": return Stub.class;
                    case ClusterModule.INFO: return Info.class;
                    case "com.qinggan.cluster.info.GuideState": return GuideState.class;
                    case "android.content.Context": return Context.class;
                    case "android.os.Parcel": return Parcel.class;
                    default: return Class.forName(name);
                }
            });
            runtime = new ModuleRuntime("code", scheduler, id -> new ClusterModule(methods, hooks, this));
            runtime.apply("code", 1, List.of("cluster"), Map.of());
        }
        void send(GuideState state) throws Throwable {
            Info info = new Info();
            info.state = state;
            sources.add(new Source(info, state));
            backend.invoke(Binder.class.getDeclaredMethod("updateNaviInfo", Info.class), binder, info);
        }
        void transact(int code) throws Throwable {
            Parcel data = new Parcel();
            data.info = new Info();
            data.info.state = GuideState.GUIDING;
            backend.invoke(Stub.class.getDeclaredMethod("onTransact", int.class, Parcel.class, Parcel.class, int.class), binder, code, data, null, 0);
        }
        void flush() {
            for (int attempt = 0; attempt < 15; attempt++) {
                scheduler.advance(0);
                if (main.isEmpty()) return;
                while (!main.isEmpty()) main.remove().run();
            }
            throw new AssertionError("Cluster callbacks did not settle");
        }
        @Override public boolean enabled() { return enabled; }
        @Override public Object findTarget() { return late ? null : binder; }
        @Override public void postMain(Runnable action) { main.add(action); }
        @Override public void watch(ResourceGroup resources, Runnable changed) throws Exception { this.changed = changed; resources.own(() -> closedObservers++); }
        @Override public ClusterModule.Card card() { return null; }
        @Override public void error(Exception failure) {}
        @Override public void close() throws Exception { runtime.close(); hooks.close(); active.remove(); }
    }

    interface Contract {}
    static class Stub {
        boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws Throwable {
            if (code == 105) active.get().backend.invoke(Binder.class.getDeclaredMethod("updateNaviInfo", Info.class), this, data.info);
            return true;
        }
    }
    static final class Binder extends Stub implements Contract { void updateNaviInfo(Info info) { active.get().writes.add(info); } }
    enum GuideState { GUIDING, MAN_STOP }
    static final class Info {
        GuideState state;
        boolean display = true;
        int icon = 7;
        int distance = 250;
        String currentRoad = "Current";
        String nextRoad = "Next";
        String destination = "Destination";
        void setGuideState(GuideState value) { state = value; }
        void setDisplay(boolean value) { display = value; }
        void setTbtIconId(int value) { icon = value; }
        void setNextDistance(int value) { distance = value; }
        void setCurrentRoadName(String value) { currentRoad = value; }
        void setTurnRoadName(String value) { nextRoad = value; }
        void setDestName(String value) { destination = value; }
    }
    static final class Context {}
    static final class Parcel { Info info; }
    static final class Formatter { static String toDistanceUnit(Context context, int meters) { return "OEM"; } }
    static final class Source { final Info info; final GuideState expected; Source(Info info, GuideState expected) { this.info = info; this.expected = expected; } }
}
