package ru.voyahtune.contracts;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.RdsModule;
import ru.voyahtune.agent.modules.RdsTuneOwnership;
import ru.voyahtune.agent.policy.RadioRestoreController;
import ru.voyahtune.agent.policy.RadioStation;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

public final class RdsModuleTest {
    private static final ThreadLocal<Harness> active = new ThreadLocal<>();

    public static void main(String[] arguments) throws Throwable { verify(); System.out.println("RdsModuleTest: passed"); }

    static void verify() throws Throwable {
        try (Harness harness = new Harness(target -> true)) {
            harness.flush();
            require(harness.manager.tunes == 0 && harness.manager.plays == 0 && harness.selected != null, "Attach silently started tuner/audio");
            require(harness.invoke(Model.class, "getCurRadioStation", new Class<?>[0], harness.model) == harness.selected, "Silent selected station not presented in model");
            harness.invoke(Manager.class, "play", new Class<?>[0], harness.manager);
            require(harness.manager.tunes == 1 && harness.selected == null && harness.manager.tempPlayStation.frequency == 10170, "Playback did not restore saved station once");
            harness.invoke(Manager.class, "resumePlay", new Class<?>[0], harness.manager);
            require(harness.manager.tunes == 1, "Repeated resume extended/replayed live request");
            harness.invoke(Info.class, "onCurrentStationUpdate", new Class<?>[] {Station.class}, new Info(), new Station(10170));
            require(!harness.tuning.hasOwned(), "Confirmation did not release owned command");
            harness.saved = new RadioStation(false, 999, false, 0, false, null);
            harness.runtime.close();
            require(harness.backend.installed.isEmpty() && harness.cleanup == 1 && harness.receiversClosed == 1, "RDS cleanup retained hooks/receiver");
        }
        try (Harness harness = new Harness(target -> true, null)) {
            harness.invoke(Manager.class, "play", new Class<?>[0], harness.manager);
            require(harness.manager.plays == 1 && harness.manager.tunes == 0, "Missing saved station did not preserve OEM playback");
        }
        try (Harness harness = new Harness(target -> true, new RadioStation(false, 999, false, 0, false, null))) {
            harness.invoke(Manager.class, "play", new Class<?>[0], harness.manager);
            require(harness.manager.tunes == 1 && harness.manager.lastBand == Band.MW, "Owned AM tune kept OEM FM source bug");
        }
        try (Harness harness = new Harness(target -> true)) {
            harness.manager.playing = true;
            harness.invoke(Manager.class, "initData", new Class<?>[0], harness.manager);
            harness.invoke(AudioStatus.class, "onAudioPlayStatus", new Class<?>[] {boolean.class}, new AudioStatus(), false);
            require(harness.manager.tunes == 1 && harness.manager.plays == 0, "Automatic active-radio callbacks replayed restore or requested audioPlay");
        }
        try (Harness harness = new Harness(target -> true)) {
            harness.manager.current = new Station(10170);
            harness.manager.playFailure = new IllegalStateException("Owned original playback failure");
            try { harness.invoke(Manager.class, "play", new Class<?>[0], harness.manager); throw new AssertionError("Playback error lost"); }
            catch (RuntimeException failure) { require(failure == harness.manager.playFailure && harness.manager.plays == 1 && harness.manager.tunes == 0, "Replacement fallback replayed original playback"); }
        }
        try (Harness harness = new Harness(target -> true)) {
            harness.invoke(Manager.class, "playStation", new Class<?>[] {Station.class}, harness.manager, new Station(9900));
            require(harness.selected == null && harness.manager.tunes == 1 && harness.manager.tempPlayStation.frequency == 9900, "Explicit station selection was replaced");
            harness.invoke(Info.class, "onCurrentStationUpdate", new Class<?>[] {Station.class}, new Info(), new Station(9900));
            harness.scheduler.advance(750);
            require(harness.saved.frequency == 9900, "Settled explicit choice not persisted");
            harness.runtime.apply("code", 2, List.of("rds"), Map.of());
            harness.flush();
            require(harness.manager.tunes == 1, "Configuration change replayed tuning");
        }
        try (Harness harness = new Harness(target -> true)) {
            harness.manager.ready = false;
            harness.manager.delayPlay = true;
            harness.invoke(Manager.class, "play", new Class<?>[0], harness.manager);
            harness.focus = false;
            harness.scheduler.advance(250);
            require(harness.manager.tunes == 0 && !harness.manager.delayPlay, "Focus loss did not cancel owned readiness wait");
        }
        try (Harness harness = new Harness(target -> !target.getName().equals("resumePlay"))) {
            require(harness.runtime.snapshot().phase == ModuleRuntime.Phase.FAILED && harness.backend.installed.isEmpty() && harness.cleanup == 1,
                    "Partial required hook installation was not rolled back");
        }
    }

    static final class Harness implements AutoCloseable, RdsModule.Platform {
        final FixtureHookBackend backend = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        final HookRegistry hooks = new HookRegistry(backend);
        final ManualScheduler scheduler = new ManualScheduler();
        final ArrayDeque<Runnable> main = new ArrayDeque<>();
        final Manager manager = new Manager();
        final Model model = new Model();
        final ModuleRuntime runtime;
        RadioStation saved;
        Station selected;
        RdsTuneOwnership tuning;
        boolean focus = true;
        int cleanup;
        int receiversClosed;

        Harness(Predicate<Method> accepted) {
            this(accepted, new RadioStation(true, 10170, true, 1234, false, null));
        }
        Harness(Predicate<Method> accepted, RadioStation saved) {
            this.saved = saved;
            active.set(this);
            backend.accept = accepted;
            MethodAccess methods = new MethodAccess(name -> {
                switch (name) {
                    case RdsModule.MANAGER: return Manager.class;
                    case RdsModule.MODEL: return Model.class;
                    case RdsModule.MODEL + "$1": return ModelListener.class;
                    case RdsModule.MANAGER + "$3": return Info.class;
                    case RdsModule.MANAGER + "$4": return AudioStatus.class;
                    case RdsModule.STATION: return Station.class;
                    case RdsModule.BAND: return Band.class;
                    case "com.adayo.proxy.dab.DABConstants$ScanState": return ScanState.class;
                    default: return Class.forName(name);
                }
            });
            runtime = new ModuleRuntime("code", scheduler, id -> new RdsModule(methods, hooks, this));
            runtime.apply("code", 1, List.of("rds"), Map.of());
        }
        Object invoke(Class<?> owner, String name, Class<?>[] types, Object receiver, Object... arguments) throws Throwable {
            return backend.invoke(owner.getDeclaredMethod(name, types), receiver, arguments);
        }
        void flush() { scheduler.advance(0); while (!main.isEmpty()) main.remove().run(); scheduler.advance(0); }
        @Override public void prepare() {}
        @Override public RadioStation saved() { return saved; }
        @Override public RadioStation snapshot(Object value) {
            if (value == null) return null;
            Station station = (Station) value;
            return new RadioStation(station.fm, station.frequency, station.rds, station.pi, false, null);
        }
        @Override public RadioRestoreController.IO restoration(ModuleScope scope, RdsModule.Originals originals) {
            tuning = new RdsTuneOwnership(manager, Station.class, originals);
            return new RadioRestoreController.IO() {
                @Override public long now() { return scheduler.now(); }
                @Override public void later(long delay, Runnable action) { try { scope.schedule(delay, action); } catch (Exception failure) { throw new IllegalStateException(failure); } }
                @Override public RadioStation current() { return snapshot(manager.current); }
                @Override public boolean ready() { return manager.ready; }
                @Override public boolean acquireFocus() { tuning.resumeDelay(!manager.ready); return focus; }
                @Override public boolean hasFocus() { return focus; }
                @Override public boolean isPlaying() { return focus && manager.playing; }
                @Override public boolean save(RadioStation station) { saved = station; return true; }
                @Override public void tune(RadioStation station) throws Exception { tuning.tune(station); }
                @Override public void playCurrent() throws Exception { tuning.playCurrent(); }
                @Override public void stageSelected(RadioStation station) throws Exception { selected = (Station) tuning.command(station); }
                @Override public void clearSelected() { selected = null; }
                @Override public void cancelOwned() throws Exception { tuning.cancel(); }
                @Override public void releaseOwned() throws Exception { tuning.release(); }
                @Override public void log(String event) {}
            };
        }
        @Override public Object selectedStation() { return selected; }
        @Override public boolean ownedStation(Object station) { return tuning.owns(station); }
        @Override public boolean hasOwnedStation() { return tuning.hasOwned(); }
        @Override public void beginDispatch(RadioStation station) { tuning.beginDispatch(station); }
        @Override public void finishDispatch() throws Exception { tuning.finishDispatch(); }
        @Override public RadioStation dispatching() { return tuning.dispatching(); }
        @Override public Object band(boolean fm) { return fm ? Band.FM : Band.MW; }
        @Override public long playbackCalls() { return tuning.playbackCalls(); }
        @Override public void postMain(Runnable action) { main.add(action); }
        @Override public void bridges(ModuleScope scope, HookRegistry registry, RadioRestoreController controller, RdsModule.Originals originals) throws Exception { scope.own(() -> receiversClosed++); }
        @Override public void restoreAccSelection(RadioRestoreController controller) throws Exception { controller.restoreSilent(1); }
        @Override public void cleanup() throws Exception { cleanup++; selected = null; if (tuning != null) tuning.close(); }
        @Override public void error(Exception failure) {}
        @Override public void close() throws Exception { runtime.close(); hooks.close(); active.remove(); }
    }

    enum Band { FM, MW }
    enum ScanState { FINISH }
    static final class Station {
        final int frequency;
        boolean fm = true;
        boolean rds;
        int pi;
        Station(int frequency) { this.frequency = frequency; }
        void setIsFMStation(boolean value) { fm = value; }
        void setIsRDS(boolean value) { rds = value; }
        void setPICode(int value) { pi = value; }
    }
    static final class Manager {
        Station current = new Station(8750);
        Station delayPlayStation;
        Station tempPlayStation;
        final Handler handler = new Handler();
        int actionCode = -1;
        boolean delayPlay;
        boolean ready = true;
        boolean playing;
        int tunes;
        int plays;
        RuntimeException playFailure;
        Band lastBand;
        boolean requestAudioFocus() { return active.get().focus; }
        void playStation(Station station) throws Throwable { tunes++; active.get().invoke(Manager.class, "switchRadioSource", new Class<?>[] {Band.class}, this, Band.FM); tempPlayStation = station; actionCode = 1002; }
        void play() { plays++; if (playFailure != null) throw playFailure; playing = true; }
        void resumePlay() { plays++; playing = true; }
        void pause() { playing = false; }
        void pauseNoAbandon() { playing = false; }
        void fullScan() {}
        void fullScan(Band band) {}
        void switchRadioSource(Band band) { lastBand = band; }
        void initData() {}
    }
    static final class Model {
        void play() { active.get().manager.play(); }
        Station getCurRadioStation() { return active.get().manager.current; }
        Station getCurStation() { return active.get().manager.current; }
    }
    static final class ModelListener { void onCurrentStationUpdate(Station station) {} }
    static final class Info {
        void onCurrentStationUpdate(Station station) { active.get().manager.current = station; }
        void onFreqSeekStateChanged(ScanState state) {}
    }
    static final class AudioStatus { void onAudioPlayStatus(boolean paused) {} }
    static final class Handler { void removeMessages(int code) {} }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
