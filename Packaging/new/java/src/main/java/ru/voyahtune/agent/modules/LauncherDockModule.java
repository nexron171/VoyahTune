package ru.voyahtune.agent.modules;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.LauncherCommand;
import ru.voyahtune.agent.policy.LauncherNavigation;
import ru.voyahtune.agent.policy.LauncherPolicy;
import ru.voyahtune.agent.policy.LauncherPresentation;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.runtime.ResourceGroup;
import ru.voyahtune.agent.runtime.WeakIdentityMap;

public final class LauncherDockModule implements ModuleRuntime.Module {
    public static final String MODEL = "com.qinggan.app.launcher.LauncherModel";
    private static final String SHARED = "com.qinggan.mainlauncher.navigation.NavigationBar";
    private static final String SPLIT = "com.qinggan.launcher.navigation.NavigationBarMain";
    private static final String CONTROLLER = "com.qinggan.launcher.navigation.NavigationBarController";
    private static final String SECOND_HOME = "com.qinggan.secondlauncher.activity.SecondMainActivity";
    private static final long[] BOOT_PASSES = {0, 800, 2500, 5000, 8000, 12000, 15000};
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final LauncherPlatform platform;
    private final Map<Method, Binding> bindings = new LinkedHashMap<>();
    private final WeakIdentityMap<Object, Boolean> bars = new WeakIdentityMap<>();
    private final WeakIdentityMap<Object, Role> roles = new WeakIdentityMap<>();
    private final LauncherPolicy.Top[] foreground = {new LauncherPolicy.Top("", "", false), new LauncherPolicy.Top("", "", false)};
    private final Guard[] guards = {new Guard(), new Guard()};
    private final Boolean[] beforeMove = new Boolean[2];
    private final boolean[] hidden = new boolean[2];
    private final boolean[] restoring = new boolean[2];
    private final AtomicBoolean chromePending = new AtomicBoolean();
    private WeakReference<Object> model = new WeakReference<>(null);
    private volatile LauncherPolicy policy;
    private volatile boolean initialized;
    private volatile boolean pending = true;
    private boolean shared;
    private Class<?> navigationType;
    private LauncherNavigation navigation;
    private LauncherPresentation presentation;
    private LauncherWindowVisibility visibility;
    private Object navigationListener;
    private Object slotListener;
    private long bootStarted;
    private long generation;
    private int lastSlot;

    public LauncherDockModule(MethodAccess methods, HookRegistry hooks, LauncherPlatform platform) {
        this.methods = methods;
        this.hooks = hooks;
        this.platform = platform;
    }

    @Override public void prepare(Map<String, String> configuration) throws Exception {
        if (navigationType != null) return;
        String owner;
        try {
            core(SPLIT);
            owner = SPLIT;
            shared = false;
        } catch (ReflectiveOperationException unavailable) {
            core(SHARED);
            owner = SHARED;
            shared = true;
        }
        navigationType = methods.type(owner);
        bind(owner, "onClick", "click", true, "android.view.View");
        bind(owner, "updateTheme", "paint", true);
        bind(owner, "updateSelectedApp", "selected", true, "java.lang.String", "java.lang.String");
        bind(owner, "initScreenUpViews", "paint", shared);
        bind(owner, "doScreenLift", "lift", shared, "int");
        bind(owner, "dismiss", "dismiss", shared);
        bind(owner, "show", "show", false);
        bind(MODEL, "handleUpdateMainNavigationBar", "gate0", shared, "java.lang.String", "java.lang.String", "boolean");
        bind(MODEL, "handleUpdateSecondNavigationBar", "gate1", shared, "java.lang.String", "java.lang.String", "boolean");
        bind(MODEL, "onReceive", "receive", shared, "android.content.Context", "android.content.Intent");
        bind(MODEL, "doScreenLift", "model-lift", false, "int");
        moves("onMoveStart", "move-start");
        moves("onMoveStop", "move-stop");
        if (shared) bind(SECOND_HOME, "onResume", "second-resume", true);
        else {
            String passenger = "com.qinggan.launcher.navigation.NavigationBarSecond";
            bind(passenger, "initScreenUpViews", "paint", false);
            bind(passenger, "updateTheme", "paint", false);
            bind(passenger, "initAllAppShow", "paint", false);
            bind(passenger, "updateSelectedApp", "selected", false, "java.lang.String", "java.lang.String");
            bind(CONTROLLER, "doScreenLift", "lift", false, "int");
            bind(CONTROLLER, "show", "show", false);
            bind(CONTROLLER, "dismiss", "dismiss", false);
            bind(MODEL, "isThirdShowFloatApp", "float", false, "java.lang.String");
            bind("com.qinggan.launcher.base.drag.ThirdAppUtil", "isThirdShowFloatApp", "float", false, "java.lang.String");
        }
        policy = new LauncherPolicy(shared, platform.settings());
    }

    private void core(String owner) throws Exception {
        methods.require(owner, "onClick", "android.view.View");
        methods.require(owner, "updateTheme");
        methods.require(owner, "updateSelectedApp", "java.lang.String", "java.lang.String");
    }

    private void bind(String owner, String name, String role, boolean required, String... arguments) throws Exception {
        try {
            Method method = methods.optional(owner, name, arguments);
            if (method == null) { if (required) throw new NoSuchMethodException(owner + "." + name); return; }
            if (method.getReturnType() != (role.equals("float") ? boolean.class : void.class)) throw new NoSuchMethodException("Launcher return ABI mismatch: " + method);
            Binding previous = bindings.putIfAbsent(method, new Binding(methods.type(owner), role, required));
            if (previous != null && !previous.role.equals(role)) throw new IllegalArgumentException("Conflicting launcher method roles");
        } catch (ClassNotFoundException missing) { if (required) throw missing; }
    }

    private void moves(String name, String role) throws Exception {
        if (shared) { bind(MODEL, name, role, true, "java.lang.String", "java.lang.String", "int", "int", "int", "int"); return; }
        try {
            for (Method method : methods.overloads(MODEL, name)) {
                Class<?>[] types = method.getParameterTypes();
                if (types.length >= 4 && types[0] == String.class && types[1] == String.class && types[2] == int.class && types[3] == int.class && method.getReturnType() == void.class) {
                    bindings.putIfAbsent(method, new Binding(methods.type(MODEL), role, false));
                }
            }
        } catch (ClassNotFoundException absent) { error(absent); }
    }

    @Override public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        presentation = new LauncherPresentation(platform);
        visibility = new LauncherWindowVisibility();
        navigation = new LauncherNavigation(shared);
        navigationListener = platform.listener(view -> gesture(scope, view));
        slotListener = platform.listener(view -> gesture(scope, view));
        bootStarted = platform.now();
        for (int display = 0; display < 2; display++) hidden[display] = platform.chromeHidden(display);
        ResourceGroup resources = new ResourceGroup();
        scope.own(resources);
        resources.own(() -> platform.drainMain(this::release));
        for (Map.Entry<Method, Binding> entry : bindings.entrySet()) {
            try {
                Binding binding = entry.getValue();
                resources.own(hooks.install("launcher-dock", entry.getKey(), scope.callback(call -> {
                    if (call.receiver() != null && !binding.owner.isInstance(call.receiver())) return call.proceed();
                    return callback(scope, binding.role, call);
                })));
            } catch (HookRegistry.RejectedException rejected) { if (entry.getValue().required) throw rejected; error(rejected); }
        }
        platform.watch(resources, new LauncherPlatform.Events() {
            @Override public void reload() { post(scope, () -> { refresh(); later(scope, 300, () -> repaint(scope)); }); }
            @Override public void packages(String name) { platform.invalidatePackage(name); post(scope, () -> repaint(scope)); }
            @Override public void locale() { platform.invalidatePackage(""); post(scope, () -> repaint(scope)); }
            @Override public void chrome() { post(scope, () -> reconcile(scope, findModel(), false)); }
        });
    }

    @Override public void configure(ModuleScope scope, Map<String, String> configuration) throws Exception {
        pending = true;
        chromePending.set(false);
        scope.schedule(0, () -> post(scope, () -> {
            refresh();
            repaint(scope);
            if (!initialized && shared && top(1).activity.contains("SecondMainActivity")) execute(LauncherCommand.Kind.PASSENGER_ALL_APPS, 1, "", 0);
            initialized = true;
            pending = false;
        }));
        for (long delay : BOOT_PASSES) {
            long remaining = bootStarted + delay - platform.now();
            if (remaining > 0) scope.schedule(remaining, () -> post(scope, () -> repaint(scope)));
        }
    }

    @Override public boolean ready() { return initialized && !pending; }
    public boolean shared() { return shared; }

    private Object callback(ModuleScope scope, String role, HookRegistry.Invocation call) throws Throwable {
        Object receiver = call.receiver();
        Object[] arguments = call.arguments();
        switch (role) {
            case "float": return !policy.setting("floathome").equals("0") ? false : call.proceed();
            case "click": return click(call, receiver, arguments[0]);
            case "selected":
                int display = screen(receiver);
                if (physical(display)) foreground[display] = new LauncherPolicy.Top((String) arguments[0], (String) arguments[1], false);
                if (display == 1) {
                    Object selected = call.proceed();
                    platform.passengerSelection((String) arguments[0]);
                    return selected;
                }
                if (display == 0) {
                    if (LauncherPolicy.NATIVE.equals(arguments[0]) && String.valueOf(arguments[1]).contains("SplitHostActivity") && lastSlot > 0) {
                        Object selectedView = field(bar(receiver), "mScreenUpItemView" + lastSlot);
                        if (selectedView != null) { presentation.checked(selectedView, true); return null; }
                    }
                    for (int slot = 1; slot <= 2; slot++) if (policy.setting("dock" + slot).equals(arguments[0])) arguments[0] = slot == 1 ? "com.qinggan.bluetoothphone" : "com.qinggan.app.music";
                }
                return call.proceed();
            case "dismiss": return dismiss(scope, call, receiver);
            case "show":
                int shownDisplay = screen(receiver);
                if (physical(shownDisplay) && !restoring[shownDisplay] && hides(shownDisplay, top(shownDisplay))) {
                    call.disableFallback();
                    if (!hide(scope, receiver)) {
                        Exception failure = new IllegalStateException("Dock window visibility ABI unavailable");
                        scope.requireRestart(failure);
                        throw failure;
                    }
                    report(shownDisplay);
                    return null;
                }
                visibility.forget(receiver);
                Object shown = call.proceed();
                paintGuarded(scope, receiver, platform.lift());
                if (physical(shownDisplay)) platform.reportDockHidden(shownDisplay, false);
                return shown;
            case "gate0": return gate(scope, call, 0);
            case "gate1": return gate(scope, call, 1);
            case "move-start": startMove(receiver, arguments); return call.proceed();
            case "move-stop":
                Object moved = call.proceed();
                stopMove(scope, receiver, arguments);
                return moved;
            case "receive":
                Object received = call.proceed();
                if (arguments[1] != null && "android.intent.action.TOP_ACTIVITY_CHANGED".equals(OemAccess.call(arguments[1], "getAction"))) {
                    rememberModel(receiver);
                    post(scope, () -> reconcile(scope, receiver, false));
                    later(scope, 300, () -> reconcile(scope, receiver, false));
                }
                return received;
            case "model-lift":
                Object lifted = call.proceed();
                later(scope, 50, () -> repaint(scope));
                later(scope, 250, () -> repaint(scope));
                return lifted;
            case "second-resume":
                Object resumed = call.proceed();
                Object targetDisplay = OemAccess.call(receiver, "getDisplay");
                if (targetDisplay != null && Integer.valueOf(1).equals(OemAccess.call(targetDisplay, "getDisplayId"))) execute(LauncherCommand.Kind.PASSENGER_ALL_APPS, 1, "", 0);
                return resumed;
            case "paint":
            case "lift":
                Object result = call.proceed();
                paintGuarded(scope, receiver, role.equals("lift") ? (Integer) arguments[0] : platform.lift());
                return result;
            default: throw new IllegalArgumentException("Unknown launcher method role");
        }
    }

    private Object click(HookRegistry.Invocation call, Object owner, Object view) throws Throwable {
        if (screen(owner) != 0 || navigation.bypassing()) return call.proceed();
        Role role = role(owner, view);
        if (role == null) return call.proceed();
        if (role.button != null) {
            LauncherNavigation.Action action = navigation.action(0, role.button, policy.dashboardShort(role.button), false);
            if (action != LauncherNavigation.Action.STOCK) {
                call.disableFallback();
                action(action, 0);
                return null;
            }
        } else {
            String target = platform.setting("dock" + role.slot);
            if (platform.available(target)) {
                call.disableFallback();
                lastSlot = role.slot;
                execute(!shared && target.equals("ru.big.town.restoremode") ? LauncherCommand.Kind.DASHBOARD : LauncherCommand.Kind.FREEFORM, 0, target, 0);
                return null;
            }
        }
        return call.proceed();
    }

    private boolean gesture(ModuleScope scope, Object view) {
        boolean[] handled = {false};
        scope.listener(() -> {
            Role role = roles.get(view);
            if (role == null) return;
            try {
                if (role.button != null) {
                    handled[0] = true;
                    LauncherNavigation.Action action = navigation.action(0, role.button, policy.dashboardShort(role.button), true);
                    if (action == LauncherNavigation.Action.STOCK) navigation.stock(() -> OemAccess.call(view, "performClick"));
                    else action(action, 0);
                } else if (policy.setting("dock" + role.slot + "HasSplit").equals("1") || !shared && policy.setting("dock" + role.slot + "LongAction").equals("cluster")) {
                    handled[0] = true;
                    execute(shared ? LauncherCommand.Kind.DOCK_SPLIT : LauncherCommand.Kind.DOCK_LONG_PRESS, 0, "", role.slot);
                }
            } catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { error(exception(failure)); }
        }).run();
        return handled[0];
    }

    private void action(LauncherNavigation.Action action, int display) throws Exception {
        LauncherCommand.Kind kind = action == LauncherNavigation.Action.DASHBOARD ? LauncherCommand.Kind.DASHBOARD
                : action == LauncherNavigation.Action.TASK_MANAGER ? LauncherCommand.Kind.TASK_MANAGER : LauncherCommand.Kind.STOCK_HOME;
        execute(kind, display, "", 0);
    }

    private Object dismiss(ModuleScope scope, HookRegistry.Invocation call, Object owner) throws Throwable {
        int display = screen(owner);
        if (!physical(display)) return call.proceed();
        LauncherPolicy.Top foreground = top(display);
        if (!restoring[display] && hides(display, foreground)) {
            call.disableFallback();
            if (hide(scope, owner)) { report(display); return null; }
            return call.proceed();
        }
        if (policy.carPlay(foreground) && platform.revealRemaining(display) > 0 || moving() || launchPending(display) || policy.keeps(foreground)) return null;
        visibility.forget(owner);
        return call.proceed();
    }

    private Object gate(ModuleScope scope, HookRegistry.Invocation call, int display) throws Throwable {
        rememberModel(call.receiver());
        Object controller = controller(call.receiver(), display);
        LauncherPolicy.Top current = top(display);
        if (restoring[display] || policy.carPlay(current) && (!policy.hideCarPlay() || platform.revealRemaining(display) > 0)) {
            call.arguments()[2] = true;
            visibility.forget(controller);
            Object result = call.proceed();
            platform.reportDockHidden(display, false);
            return result;
        }
        LauncherPolicy.Top decision = current.live ? current : new LauncherPolicy.Top(nonempty(call.arguments()[0], current.packageName), nonempty(call.arguments()[1], current.activity), false);
        if (!hides(display, decision)) { visibility.forget(controller); return call.proceed(); }
        call.disableFallback();
        if (controller != null && hide(scope, controller)) { report(display); return null; }
        call.arguments()[2] = false;
        return call.proceed();
    }

    private void refresh() throws Exception { policy = new LauncherPolicy(shared, platform.settings()); }

    private boolean hide(ModuleScope scope, Object controller) throws Exception {
        try { return visibility.hide(controller); }
        catch (Exception failure) { scope.requireRestart(failure); throw failure; }
    }

    private void paintGuarded(ModuleScope scope, Object controller, int lift) {
        try { paint(controller, lift); }
        catch (Exception failure) { scope.requireRestart(failure); error(failure); }
    }

    private void repaint(ModuleScope scope) throws Exception {
        Object current = findModel();
        if (current != null) for (int display = 0; display < 2; display++) {
            Object controller = controller(current, display);
            if (controller != null) paint(controller, platform.lift());
        }
        for (Object retained : bars.keys()) paint(retained, platform.lift());
        reconcile(scope, current, false);
        later(scope, 300, () -> reconcile(scope, findModel(), false));
    }

    private void paint(Object controller, int lift) throws Exception {
        Object bar = bar(controller);
        if (bar == null) return;
        if (screen(bar) == 1) {
            Object current = findModel();
            Object driver = bar(controller(current, 0));
            if (driver != null) {
                try {
                    platform.passengerDock(bar, driver, policy.setting("passengerDockSlot1"),
                            policy.setting("passengerDockSlot2"));
                } catch (Exception unavailable) {
                    error(unavailable);
                }
            }
            return;
        }
        if (screen(bar) != 0) return;
        bars.put(bar, true);
        Map<String, Object> views = views(bar);
        presentation.icons(0, Arrays.asList(views.get("slot1"), views.get("slot2")),
                List.of(policy.setting("dock1"), policy.setting("dock2")), List.of(policy.setting("dock1Icon"), policy.setting("dock2Icon")));
        bindRole(views.get("home"), new Role(LauncherNavigation.Button.HOME, 0));
        bindRole(views.get("allApps"), new Role(LauncherNavigation.Button.ALL_APPS, 0));
        bindRole(views.get("slot1"), new Role(null, 1));
        bindRole(views.get("slot2"), new Role(null, 2));
        presentation.lift(0, views, lift, hides(0, top(0)), !policy.setting("dock1").equals("none"), !policy.setting("dock2").equals("none"));
    }

    private void bindRole(Object view, Role role) throws Exception {
        if (view == null) return;
        roles.put(view, role);
        presentation.listener(0, view, role.button == null ? slotListener : navigationListener);
    }

    private Role role(Object owner, Object view) throws Exception {
        Role known = roles.get(view);
        if (known != null) return known;
        Map<String, Object> views = views(bar(owner));
        for (String name : new String[] {"home", "allApps", "slot1", "slot2"}) {
            Object candidate = views.get(name);
            if (candidate == null) continue;
            Object id = OemAccess.call(view, "getId");
            if (candidate == view || id instanceof Integer && (Integer) id >= 0 && id.equals(OemAccess.call(candidate, "getId"))) {
                return name.equals("home") ? new Role(LauncherNavigation.Button.HOME, 0) : name.equals("allApps")
                        ? new Role(LauncherNavigation.Button.ALL_APPS, 0) : new Role(null, name.equals("slot1") ? 1 : 2);
            }
        }
        return null;
    }

    private Map<String, Object> views(Object bar) {
        Map<String, Object> result = new LinkedHashMap<>();
        String[][] fields = {{"up", "mScreenUpView"}, {"down", "mScreenDownView"}, {"group", "mScreenUpRadioGroup"}, {"home", "mScreenUpHomeView"},
                {"allApps", "mScreenUpAllAppView"}, {"slot1", "mScreenUpItemView1"}, {"slot2", "mScreenUpItemView2"}, {"extra1", "mScreenUpItemView3"},
                {"extra2", "mScreenUpItemView4"}, {"temperature", "mScreenUpTemperatureContentView"}};
        for (String[] field : fields) result.put(field[0], field(bar, field[1]));
        return result;
    }

    private Object findModel() throws Exception {
        Object found = platform.model();
        if (found != null) rememberModel(found);
        return found == null ? model.get() : found;
    }
    private void rememberModel(Object value) { if (value != null) model = new WeakReference<>(value); }
    private Object controller(Object model, int display) { return field(model, display == 0 ? "mMainScreenNavigationBar" : "mSecondScreenNavigationBar"); }
    private Object bar(Object owner) { Object nested = field(owner, "mNavigationBar"); return nested == null ? owner : nested; }
    private static Object field(Object owner, String name) {
        if (owner == null) return null;
        try { return OemAccess.read(owner, name); }
        catch (ReflectiveOperationException absent) { return null; }
    }
    private int screen(Object owner) {
        Object value = field(owner, "mScreenId");
        if (value instanceof Integer && physical((Integer) value)) return (Integer) value;
        Object nested = field(owner, "mNavigationBar");
        if (nested != null) return screen(nested);
        if (owner != null && owner.getClass().getName().contains("NavigationBarSecond")) return 1;
        return !shared && owner != null && navigationType.isInstance(owner) ? 0 : -1;
    }

    private LauncherPolicy.Top top(int display) {
        try {
            LauncherPolicy.Top current = platform.top(display, shared);
            if (current != null && !current.packageName.isEmpty()) { foreground[display] = current; return current; }
        } catch (Exception unavailable) { error(unavailable); }
        LauncherPolicy.Top cached = foreground[display];
        return new LauncherPolicy.Top(cached.packageName, cached.activity, false);
    }
    private boolean hides(int display, LauncherPolicy.Top top) { return !restoring[display] && policy.hides(top, platform.revealRemaining(display)); }
    private void report(int display) { LauncherPolicy.Top top = top(display); platform.reportDockHidden(display, policy.carPlay(top) && policy.hideCarPlay() && platform.revealRemaining(display) == 0); }

    private void reconcile(ModuleScope scope, Object currentModel, boolean afterMove) throws Exception {
        if (currentModel == null) return;
        for (int display = 0; display < 2; display++) {
            LauncherPolicy.Top top = top(display);
            boolean nextHidden = policy.carPlay(top) && policy.hideCarPlay() && platform.revealRemaining(display) == 0;
            if (nextHidden && !hidden[display]) { hidden[display] = true; platform.chromeHidden(display, true); }
            boolean restore = hidden[display] && !nextHidden || policy.carPlay(top) && platform.revealRemaining(display) > 0;
            if (nextHidden || restore) {
                boolean accepted = platform.syncStatusBar(display, !nextHidden);
                if (restore) {
                    restoring[display] = true;
                    try {
                        Object controller = controller(currentModel, display);
                        visibility.forget(controller);
                        if (controller != null) OemAccess.call(controller, "show");
                        else request(currentModel, display, top, true);
                    } finally { restoring[display] = false; }
                    platform.reportDockHidden(display, false);
                    if (accepted) { hidden[display] = false; platform.chromeHidden(display, false); }
                    continue;
                }
            }
            Boolean desired = policy.desired(display, top, platform.revealRemaining(display));
            if (desired == null && shared && afterMove) desired = beforeMove[display];
            if (desired != null) request(currentModel, display, top, desired);
        }
        chromeCheck(scope);
    }

    private void request(Object model, int display, LauncherPolicy.Top top, boolean visible) throws Exception {
        OemAccess.call(model, display == 0 ? "handleUpdateMainNavigationBar" : "handleUpdateSecondNavigationBar", top.packageName, top.activity, visible);
    }

    private void chromeCheck(ModuleScope scope) throws Exception {
        long remaining = 1000;
        boolean needed = hidden[0] || hidden[1];
        for (int display = 0; display < 2; display++) {
            long reveal = platform.revealRemaining(display);
            if (reveal > 0) { needed = true; remaining = Math.min(remaining, reveal); }
        }
        if (!needed || !chromePending.compareAndSet(false, true)) return;
        later(scope, remaining, () -> { chromePending.set(false); reconcile(scope, findModel(), false); });
    }

    private boolean moving() {
        if (!policy.pinned()) return false;
        long now = platform.now();
        for (Guard guard : guards) if (guard.deadline > now && guard.deadline - now <= 10000) return true;
        return false;
    }

    private boolean launchPending(int display) throws Exception {
        if (!policy.pinned()) return false;
        String raw = platform.setting("dockLaunchGuard" + display);
        int separator = raw.indexOf('|');
        if (separator <= 0 || separator == raw.length() - 1) return false;
        try {
            long remaining = Long.parseLong(raw.substring(0, separator)) - platform.now();
            String name = raw.substring(separator + 1);
            return remaining > 0 && remaining <= 10000 && !policy.fullscreen(name)
                    && (name.equals(LauncherPolicy.NATIVE) || name.equals("ru.big.town.restoremode") || !name.startsWith("ru.big.town") && !LauncherPolicy.stock(name));
        } catch (NumberFormatException invalid) { return false; }
    }

    private void startMove(Object currentModel, Object[] arguments) throws Exception {
        int display = (Integer) arguments[3];
        if (!physical(display) || !Integer.valueOf(1).equals(arguments[2])) return;
        LauncherPolicy.Top moving = new LauncherPolicy.Top((String) arguments[0], (String) arguments[1], false);
        if (!shared && !policy.keeps(moving)) return;
        if (shared && guards[display].deadline <= platform.now()) for (int index = 0; index < 2; index++) {
            Object controller = controller(currentModel, index);
            beforeMove[index] = controller == null ? null : (Boolean) OemAccess.call(controller, "isShowing");
        }
        Guard guard = guards[display];
        guard.name = moving.packageName;
        guard.deadline = platform.now() + 5000;
        guard.generation = ++generation;
        rememberModel(currentModel);
    }

    private void stopMove(ModuleScope scope, Object currentModel, Object[] arguments) throws Exception {
        int source = (Integer) arguments[3];
        if (!physical(source) || !Integer.valueOf(1).equals(arguments[2])) return;
        String name = String.valueOf(arguments[0]);
        Guard guard = guards[source];
        long stoppedGeneration = guard.generation;
        if (guard.name.equals(name) && guard.deadline > 0) guard.deadline = Math.min(guard.deadline, platform.now() + 750);
        rememberModel(currentModel);
        if (!shared) { later(scope, 300, () -> reconcile(scope, currentModel, false)); return; }
        int destination = arguments.length > 4 && Integer.valueOf(0).equals(arguments[4]) ? source : 1 - source;
        recoverMove(scope, currentModel, name, source, destination, stoppedGeneration, 0);
    }

    private void recoverMove(ModuleScope scope, Object currentModel, String name, int source, int destination, long stoppedGeneration, int attempt) throws Exception {
        later(scope, attempt == 0 ? 300 : 400, () -> {
            if (guards[source].generation == stoppedGeneration) guards[source].deadline = 0;
            boolean settled = name.isEmpty() || top(destination).packageName.equals(name);
            if (settled || attempt == 7) reconcile(scope, currentModel, true);
            else {
                LauncherPolicy.Top current = top(source);
                Boolean desired = policy.desired(source, current, platform.revealRemaining(source));
                if (desired == null) desired = beforeMove[source];
                if (desired != null) request(currentModel, source, current, desired);
                recoverMove(scope, currentModel, name, source, destination, stoppedGeneration, attempt + 1);
            }
        });
    }

    private void release() throws Exception {
        Exception failure = null;
        for (int display = 0; display < 2; display++) if (hidden[display]) {
            try {
                if (!platform.syncStatusBar(display, true)) throw new IllegalStateException("Status-bar release not accepted");
                platform.reportDockHidden(display, false);
                platform.chromeHidden(display, false);
                hidden[display] = false;
            } catch (Exception caught) { if (failure == null) failure = caught; else failure.addSuppressed(caught); }
        }
        try { platform.clearPassengerDock(); }
        catch (Exception caught) { if (failure == null) failure = caught; else failure.addSuppressed(caught); }
        try { presentation.close(); }
        catch (Exception caught) { if (failure == null) failure = caught; else failure.addSuppressed(caught); }
        try { visibility.close(); }
        catch (Exception caught) { if (failure == null) failure = caught; else failure.addSuppressed(caught); }
        if (failure != null) throw failure;
    }

    @FunctionalInterface private interface Work { void run() throws Exception; }
    private void post(ModuleScope scope, Work action) {
        try { platform.postMain(scope.guard(() -> { try { action.run(); } catch (Exception failure) { scope.requireRestart(failure); error(failure); } })); }
        catch (Exception failure) { scope.requireRestart(failure); }
    }
    private void later(ModuleScope scope, long delay, Work action) throws Exception { scope.schedule(delay, () -> post(scope, action)); }
    private boolean execute(LauncherCommand.Kind kind, int display, String target, int slot) throws Exception { return platform.execute(new LauncherCommand(kind, display, target, slot)); }
    private static boolean physical(int display) { return display == 0 || display == 1; }
    private static String nonempty(Object value, String fallback) {
        String text = value == null ? "" : value.toString();
        return text.isEmpty() || text.equals("null") || text.equals("undefined") ? fallback : text;
    }
    private static Exception exception(Throwable failure) { return failure instanceof Exception ? (Exception) failure : new IllegalStateException(failure); }
    private void error(Exception failure) { try { platform.error(failure); } catch (RuntimeException ignored) { /* Launcher diagnostics must not replay OEM actions. */ } }
    private static final class Binding {
        final Class<?> owner;
        final String role;
        final boolean required;
        Binding(Class<?> owner, String role, boolean required) { this.owner = owner; this.role = role; this.required = required; }
    }
    private static final class Role {
        final LauncherNavigation.Button button;
        final int slot;
        Role(LauncherNavigation.Button button, int slot) { this.button = button; this.slot = slot; }
    }
    private static final class Guard { long deadline; long generation; String name = ""; }
}
