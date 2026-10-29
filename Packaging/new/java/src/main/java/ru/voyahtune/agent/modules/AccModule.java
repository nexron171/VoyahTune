package ru.voyahtune.agent.modules;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.DriveSelection;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;

public final class AccModule implements ModuleRuntime.Module {
    public interface Platform {
        boolean supports(Object component);
        int accountType(Object component) throws Exception;
        boolean nativeCaller();
        boolean stockCaller();
        DriveSelection.Targets selected(Object component) throws Exception;
        Snapshot snapshot(Object component) throws Exception;
        Snapshot observeAcc(Object component, int value) throws Exception;
        boolean dispatchSettings(Object component) throws Exception;
        void persist(Object component, int drive, int energy) throws Exception;
        boolean claim(Object component, long revision) throws Exception;
        void complete(Object component, long revision, boolean accepted) throws Exception;
        Object discover() throws Exception;
        int submit(Object component, Object bundle) throws Exception;
        void error(String operation, Exception failure);
    }

    public static final class Snapshot {
        public final int acc;
        public final long revision;
        public final long cycle;
        public final boolean smartSaveEnabled;
        public final String energy;
        public final boolean forcedEv;
        public final String startup;

        public Snapshot(int acc, long revision, long cycle, boolean smartSaveEnabled, String energy, boolean forcedEv, String startup) {
            this.acc = acc;
            this.revision = revision;
            this.cycle = cycle;
            this.smartSaveEnabled = smartSaveEnabled;
            this.energy = energy;
            this.forcedEv = forcedEv;
            this.startup = startup;
        }

        boolean smartSave() {
            return acc == 2 && smartSaveEnabled && "SREV".equals(energy) && !forcedEv;
        }
    }

    public static final String COMPONENT = "com.qinggan.canbus.service.protocol.dongfeng_h97c.DongfengH97CCanBusComponentImpl";
    public static final String BASE = "com.qinggan.canbus.service.BaseCanBusComponent";
    public static final String TASK = COMPONENT + "$ModeSettingTask";
    private static final String STATE = "com.qinggan.canbus.VehicleState";
    private static final String ENERGY = "IVI_SOC_MODESET";
    private static final int[] COLORS = {0, 19, 5, 64, 54, 10, 9};
    private static final String[] MARKERS = {"__vt_auto", "__vt_user", "__vt_temporary_energy", "__vt_energy_revision", "__vt_energy_cycle"};
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final Platform platform;
    private final ReentrantLock sendLock = new ReentrantLock();
    private final ThreadLocal<ParserState> parserState = new ThreadLocal<>();
    private final ThreadLocal<Request> sending = new ThreadLocal<>();
    private final Map<Object, Request> taskRequests = new WeakHashMap<>();
    private Method parser;
    private Method setter;
    private Method single;
    private Method feedback;
    private Constructor<?> taskConstructor;
    private Method taskSend;
    private Object energyState;
    private volatile boolean bootstrapDone;
    private volatile boolean settingsDispatched;
    private Object selectedComponent;

    public AccModule(MethodAccess methods, HookRegistry hooks, Platform platform) {
        this.methods = methods;
        this.hooks = hooks;
        this.platform = platform;
    }

    @Override public void prepare(Map<String, String> configuration) throws Exception {
        parser = methods.require(COMPONENT, "onBCM_PEPSChangeData", "[I", "boolean");
        setter = methods.require(COMPONENT, "setVehicleAndAirConditionBundleState", "android.os.Bundle", "android.os.Bundle");
        single = methods.require(COMPONENT, "setVehicleState", STATE, "int");
        feedback = methods.require(BASE, "onVehicleStateChanged", STATE, "int");
        taskConstructor = methods.type(TASK).getDeclaredConstructor(methods.type(COMPONENT), methods.type(BASE));
        taskSend = methods.require(TASK, "setVehicleMode", "java.util.HashMap");
        methods.require(COMPONENT, "getAccStatus");
        energyState = OemAccess.call(methods.type(STATE), "valueOf", ENERGY);
    }

    @Override public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        install(scope, taskConstructor, invocation -> {
            Object result = invocation.proceed();
            Request request = sending.get();
            if (request != null) {
                sendLock.lock();
                try { taskRequests.put(invocation.receiver(), request); }
                finally { sendLock.unlock(); }
            }
            return result;
        });
        install(scope, taskSend, this::sendTask);
        install(scope, setter, this::sendBundle);
        install(scope, feedback, invocation -> {
            ParserState state = state(invocation.receiver());
            if (state != null && state.drive > 0 && invocation.arguments()[1].equals(1)
                    && "DRIVING_MODE_SET".equals(String.valueOf(invocation.arguments()[0]))) invocation.arguments()[1] = state.drive;
            return invocation.proceed();
        });
        install(scope, single, this::sendSingle);
        install(scope, parser, this::parse);
        scope.own(() -> {
            sendLock.lock();
            try { taskRequests.clear(); selectedComponent = null; }
            finally { sendLock.unlock(); }
        });
    }

    private void install(ModuleScope scope, Executable target, HookRegistry.Callback callback) throws Exception {
        scope.own(hooks.install("acc", target, scope.callback(callback)));
    }

    private Object sendTask(HookRegistry.Invocation invocation) throws Throwable {
        sendLock.lock();
        Request previous = sending.get();
        try {
            Request request = taskRequests.remove(invocation.receiver());
            if (request != null && request.temporary) {
                try { if (!temporaryAllowed(request)) return false; }
                catch (Exception unavailable) { error("temporary-task", unavailable); return false; }
                sending.set(request);
            } else if (request != null) {
                boolean omit = request.snapshot.smartSave();
                try {
                    Snapshot current = platform.snapshot(request.component);
                    omit |= current.smartSave() || current.acc != 2 || current.revision != request.snapshot.revision || current.cycle != request.snapshot.cycle;
                } catch (Exception unavailable) {
                    omit = true;
                    error("automatic-task", unavailable);
                }
                if (omit) {
                    HashMap<Object, Object> values = new HashMap<>((Map<?, ?>) invocation.arguments()[0]);
                    values.remove(energyState);
                    if (values.isEmpty()) return false;
                    invocation.arguments()[0] = values;
                }
            }
            return invocation.proceed();
        } finally {
            restore(sending, previous);
            sendLock.unlock();
        }
    }

    private Object sendBundle(HookRegistry.Invocation invocation) throws Throwable {
        sendLock.lock();
        Request previous = sending.get();
        try {
            Object component = invocation.receiver();
            ParserState state = state(component);
            Object vehicle = invocation.arguments()[1];
            boolean automatic = false;
            int drive = 0;
            if (vehicle != null) {
                boolean nativeCaller = platform.nativeCaller();
                boolean stockCaller = platform.stockCaller();
                boolean guest = state != null && !state.submitted && invocation.arguments()[0] == null && platform.accountType(component) == 1
                        && integer(vehicle, "DRIVING_MODE_SET") == 1 && integer(vehicle, "EPS_MODE_SET") == 2 && integer(vehicle, "PROP_MODE_SET") == 1;
                automatic = guest || (stockCaller || nativeCaller) && flag(vehicle, "__vt_auto") || sending.get() != null;
                Object outgoing = OemAccess.construct(methods.type("android.os.Bundle"), vehicle);
                for (String marker : MARKERS) OemAccess.call(outgoing, "remove", marker);
                if (nativeCaller && flag(vehicle, "__vt_temporary_energy")) {
                    Request request = Request.temporary(component, integer(vehicle, ENERGY), longValue(vehicle, "__vt_energy_revision"), longValue(vehicle, "__vt_energy_cycle"));
                    try { if (!temporaryAllowed(request)) return -1; }
                    catch (Exception unavailable) { error("temporary-request", unavailable); return -1; }
                    sending.set(request);
                } else if (automatic) {
                    Request request = null;
                    try {
                        request = Request.automatic(component, platform.snapshot(component));
                        sending.set(request);
                        DriveSelection.Targets targets = platform.selected(component);
                        if (targets != null) {
                            apply(outgoing, targets);
                            if (guest) OemAccess.call(outgoing, "putInt", "ASC_MAINTAIN_SWITCH", targets.maintenance);
                            int[] profile = targets.drive();
                            drive = profile == null ? 0 : profile[0];
                        }
                    } catch (Exception unavailable) {
                        if (request == null) sending.remove();
                        error("automatic-profile", unavailable);
                    }
                    if (request != null && request.snapshot.smartSave()) OemAccess.call(outgoing, "remove", ENERGY);
                } else {
                    sending.remove();
                    if ((stockCaller && flag(vehicle, "__vt_user") || nativeCaller) && hasSelection(vehicle)) persist(component, vehicle);
                }
                invocation.arguments()[1] = outgoing;
            }
            Object result = invocation.proceed();
            if (state != null && automatic) {
                state.submitted = true;
                if (result.equals(0)) state.drive = drive;
            }
            return result;
        } finally {
            restore(sending, previous);
            sendLock.unlock();
        }
    }

    private Object sendSingle(HookRegistry.Invocation invocation) throws Throwable {
        ParserState state = state(invocation.receiver());
        String key = String.valueOf(invocation.arguments()[0]);
        int value = (Integer) invocation.arguments()[1];
        if (state != null && state.drive > 0 && value == 19 && key.equals("VEHICLE_AMBIENT_LIGHT_COLOR")) invocation.arguments()[1] = COLORS[state.drive];
        sendLock.lock();
        try {
            Request pending = sending.get();
            if (state == null && (pending == null || !pending.temporary) && platform.nativeCaller()
                    && (key.equals("DRIVING_MODE_SET") || key.equals(ENERGY))) {
                try { platform.persist(invocation.receiver(), key.equals("DRIVING_MODE_SET") ? value : -1, key.equals(ENERGY) ? value : -1); }
                catch (Exception unavailable) { error("single-intent", unavailable); }
            }
            return invocation.proceed();
        } finally { sendLock.unlock(); }
    }

    private Object parse(HookRegistry.Invocation invocation) throws Throwable {
        Object component = invocation.receiver();
        int[] data = (int[]) invocation.arguments()[0];
        int raw = data == null || data.length == 0 ? -1 : data[0] & 7;
        boolean entering = false;
        try { entering = raw == 2 && acc(component) != 2 && platform.supports(component); }
        catch (Exception unavailable) { error("ACC-edge", unavailable); }
        try {
            int observed = raw == 1 ? 0 : raw;
            if (platform.supports(component) && (entering || observed == 0 && acc(component) != 0)) {
                platform.observeAcc(component, observed);
                if (entering) { bootstrapDone = false; settingsDispatched = false; }
            }
        } catch (Exception unavailable) { error("ACC-state", unavailable); }
        ParserState previous = parserState.get();
        if (entering) parserState.set(new ParserState(component)); else parserState.remove();
        try { return invocation.proceed(); }
        finally {
            restore(parserState, previous);
            if (entering) {
                try { dispatch(component); }
                catch (Exception unavailable) { error("ACC-settings", unavailable); }
            }
        }
    }

    private void apply(Object outgoing, DriveSelection.Targets targets) throws Exception {
        int[] drive = targets.drive();
        if (drive != null) {
            String[] keys = {"DRIVING_MODE_SET", "EPS_MODE_SET", "PROP_MODE_SET"};
            for (int index = 0; index < keys.length; index++) OemAccess.call(outgoing, "putInt", keys[index], drive[index]);
            if (drive[0] == 6) OemAccess.call(outgoing, "remove", "HUM_ENERGY_PTREGEN_LEVL");
        }
        if (targets.recycle != null) OemAccess.call(outgoing, "putInt", "HUM_ENERGY_PTREGEN_LEVL", targets.recycle);
        if (targets.energy != null) OemAccess.call(outgoing, "putInt", ENERGY, targets.energy);
    }

    private boolean temporaryAllowed(Request request) throws Exception {
        Snapshot snapshot = platform.snapshot(request.component);
        return snapshot.acc == 2 && "SREV".equals(snapshot.energy) && !snapshot.forcedEv
                && snapshot.revision == request.revision && snapshot.cycle == request.cycle
                && (request.energy == 4 || request.energy == 2 && snapshot.smartSaveEnabled);
    }

    private void persist(Object component, Object packet) {
        try { platform.persist(component, integer(packet, "DRIVING_MODE_SET"), integer(packet, ENERGY)); }
        catch (Exception unavailable) { error("user-intent", unavailable); }
    }

    private boolean hasSelection(Object packet) throws Exception {
        return (Boolean) OemAccess.call(packet, "containsKey", "DRIVING_MODE_SET") || (Boolean) OemAccess.call(packet, "containsKey", ENERGY);
    }

    private int integer(Object packet, String key) throws Exception { return (Integer) OemAccess.call(packet, "getInt", key, -1); }
    private long longValue(Object packet, String key) throws Exception { return (Long) OemAccess.call(packet, "getLong", key, -1L); }
    private boolean flag(Object packet, String key) throws Exception { return (Boolean) OemAccess.call(packet, "getBoolean", key, false); }
    private int acc(Object component) throws Exception { return (Integer) OemAccess.call(component, "getAccStatus"); }
    private ParserState state(Object receiver) {
        ParserState current = parserState.get();
        return current != null && current.owner == receiver ? current : null;
    }
    private static <T> void restore(ThreadLocal<T> local, T previous) { if (previous == null) local.remove(); else local.set(previous); }
    private void error(String operation, Exception failure) {
        try { platform.error(operation, failure); }
        catch (RuntimeException ignored) { /* Diagnostics cannot replay an OEM command. */ }
    }

    private void dispatch(Object component) throws Exception {
        sendLock.lock();
        try { if (!settingsDispatched) settingsDispatched = platform.dispatchSettings(component); }
        finally { sendLock.unlock(); }
    }

    private void bootstrap(Object component) throws Exception {
        if (bootstrapDone || component == null || !platform.supports(component)) return;
        sendLock.lock();
        try {
            Snapshot snapshot = platform.observeAcc(component, acc(component));
            if (snapshot.acc != 2) return;
            try { dispatch(component); }
            catch (Exception unavailable) { error("startup-settings", unavailable); }
            if (!"pending".equals(snapshot.startup)) { bootstrapDone = true; return; }
            DriveSelection.Targets targets = platform.selected(component);
            if (targets == null || targets.drive() == null && targets.energy == null && targets.recycle == null) return;
            Object packet = OemAccess.construct(methods.type("android.os.Bundle"));
            apply(packet, targets);
            if (!platform.claim(component, snapshot.revision)) return;
            bootstrapDone = true;
            Request previous = sending.get();
            sending.set(Request.automatic(component, snapshot));
            boolean accepted = false;
            try { accepted = platform.submit(component, packet) == 0; }
            finally {
                restore(sending, previous);
                platform.complete(component, snapshot.revision, accepted);
            }
        } finally { sendLock.unlock(); }
    }

    @Override public void configure(ModuleScope scope, Map<String, String> configuration) throws Exception {
        scope.schedule(0, () -> readiness(scope));
    }

    private void readiness(ModuleScope scope) {
        try {
            if (!bootstrapDone) {
                if (selectedComponent == null) selectedComponent = platform.discover();
                bootstrap(selectedComponent);
            }
            if (!settingsDispatched && selectedComponent != null && platform.supports(selectedComponent)) dispatch(selectedComponent);
        } catch (Exception unavailable) { error("startup-waiting", unavailable); }
        try { scope.schedule(5000, () -> readiness(scope)); }
        catch (Exception unavailable) { error("readiness-timer", unavailable); }
    }

    private static final class ParserState {
        final Object owner;
        boolean submitted;
        int drive;
        ParserState(Object owner) { this.owner = owner; }
    }

    private static final class Request {
        final Object component;
        final Snapshot snapshot;
        final boolean temporary;
        final int energy;
        final long revision;
        final long cycle;
        private Request(Object component, Snapshot snapshot, boolean temporary, int energy, long revision, long cycle) {
            this.component = component;
            this.snapshot = snapshot;
            this.temporary = temporary;
            this.energy = energy;
            this.revision = revision;
            this.cycle = cycle;
        }
        static Request automatic(Object component, Snapshot snapshot) { return new Request(component, snapshot, false, -1, snapshot.revision, snapshot.cycle); }
        static Request temporary(Object component, int energy, long revision, long cycle) { return new Request(component, null, true, energy, revision, cycle); }
    }
}
