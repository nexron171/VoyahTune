package ru.voyahtune.agent.modules;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.LauncherCatalog;
import ru.voyahtune.agent.policy.LauncherCommand;
import ru.voyahtune.agent.policy.LauncherPolicy;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.runtime.OemList;
import ru.voyahtune.agent.runtime.ReflectionAccess;
import ru.voyahtune.agent.runtime.ResourceGroup;
import ru.voyahtune.agent.runtime.ViewOwnership;
import ru.voyahtune.agent.runtime.WeakIdentityMap;

public final class LauncherAllAppsModule implements ModuleRuntime.Module {
    private static final String APP_LAUNCHER = "com.qinggan.launcher.base.utils.AppLauncher";
    private static final String SECOND_ADAPTER = "com.qinggan.secondlauncher.adapter.SecondAllAppAdapter";
    private static final String SECOND_FRAGMENT = "com.qinggan.secondlauncher.fragment.SecondMainFragment";
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final LauncherPlatform platform;
    private final boolean shared;
    private final Map<Method, Binding> bindings = new LinkedHashMap<>();
    private final WeakIdentityMap<Object, OemList> lists = new WeakIdentityMap<>();
    private final WeakIdentityMap<Object, Boolean> bars = new WeakIdentityMap<>();
    private final WeakIdentityMap<Object, Boolean> adapters = new WeakIdentityMap<>();
    private String beanName;
    private String dataName;
    private String adapterName;
    private String barName;
    private Class<?> beanType;
    private Class<?> barType;
    private Class<?> groupType;
    private Class<?> recyclerType;
    private LauncherCatalog<Object> catalog;
    private ViewOwnership presentation;
    private volatile boolean pending = true;
    private long preparationDeadline;
    private AutoCloseable packageTimer;
    private boolean prepared;

    public LauncherAllAppsModule(MethodAccess methods, HookRegistry hooks, LauncherPlatform platform, boolean shared) {
        this.methods = methods;
        this.hooks = hooks;
        this.platform = platform;
        this.shared = shared;
    }

    @Override public void prepare(Map<String, String> configuration) throws Exception {
        if (prepared) return;
        if (!shared) {
            try {
                family("com.qinggan.launcher.allapp.AppBean", "com.qinggan.launcher.allapp.AllAppDataManager", "com.qinggan.launcher.allapp.AllAppAdapter", "com.qinggan.launcher.allapp.AllAppBarView");
            } catch (ClassNotFoundException absent) { baseFamily(); }
        } else baseFamily();
        beanType = methods.type(beanName);
        barType = methods.type(barName);
        boolean constructor = false;
        for (Constructor<?> candidate : ReflectionAccess.allConstructors(beanType)) if (Arrays.equals(candidate.getParameterTypes(), new Class<?>[] {int.class, int.class, String.class})) constructor = true;
        if (!constructor) throw new NoSuchMethodException("All Apps bean constructor ABI mismatch");
        for (String name : new String[] {"getPackageName", "getSubType", "getType", "getIcon", "getNameRes"}) methods.require(beanName, name);
        methods.require(beanName, "setSubType", "java.lang.String");
        methods.require(dataName, "getInstance");
        for (String field : new String[] {"mMainAllApps", "mSecondAllApps"}) OemAccess.field(methods.type(dataName), field);
        Method get = methods.require(dataName, "getAllApps", "int");
        if (!List.class.isAssignableFrom(get.getReturnType())) throw new NoSuchMethodException("All Apps list return ABI mismatch");
        bindings.put(get, new Binding(methods.type(dataName), "get", true));
        bind(barName, "onClick", "click", true, "android.view.View");
        bind(adapterName, "onBindViewHolder", "bind", !shared, adapterName + "$AppViewHolder", "int");
        bind(adapterName, "onBindViewHolder", "bind", shared, adapterName + "$AppViewHolder", "int", "java.util.List");
        bind(dataName, "loadData", "load", false);
        bind(dataName, "reload", "reload", false);
        bind(barName, "onAppReload", "refresh", false);
        bind(barName, "onAttachedToWindow", "refresh", false);
        bind(SECOND_ADAPTER, "onBindViewHolder", "second-bind", false, SECOND_ADAPTER + "$ViewHolder", "int");
        bind(SECOND_FRAGMENT, "onItemClick", "second-click", false, beanName);
        bind(SECOND_FRAGMENT, "onAppReload", "refresh", false);
        bind(APP_LAUNCHER, "startApp", "start-intent", true, "android.content.Context", "android.content.Intent", "int");
        bind(APP_LAUNCHER, "startApp", "start-component", true, "android.content.Context", "java.lang.String", "java.lang.String", "int");
        try { groupType = methods.type("android.view.ViewGroup"); } catch (ClassNotFoundException absent) { groupType = null; }
        for (String name : new String[] {"androidx.recyclerview.widget.RecyclerView", "android.support.v7.widget.RecyclerView"}) {
            try { recyclerType = methods.type(name); break; } catch (ClassNotFoundException absent) { /* OEM support library differs. */ }
        }
        prepared = true;
    }

    private void baseFamily() throws ClassNotFoundException {
        family("com.qinggan.launcher.base.bean.AppBean", "com.qinggan.launcher.base.allapp.AllAppDataManager", "com.qinggan.launcher.base.adapter.AllAppAdapter", "com.qinggan.launcher.base.allapp.AllAppBarView");
    }
    private void family(String bean, String data, String adapter, String bar) throws ClassNotFoundException {
        for (String name : new String[] {bean, data, adapter, bar}) methods.type(name);
        beanName = bean;
        dataName = data;
        adapterName = adapter;
        barName = bar;
    }
    private void bind(String owner, String name, String role, boolean required, String... arguments) throws Exception {
        try {
            Method target = methods.optional(owner, name, arguments);
            if (target == null) { if (required) throw new NoSuchMethodException(owner + "." + name); return; }
            if (target.getReturnType() != void.class) throw new NoSuchMethodException("All Apps callback return ABI mismatch: " + target);
            bindings.putIfAbsent(target, new Binding(methods.type(owner), role, required));
        } catch (ClassNotFoundException absent) { if (required) throw absent; }
    }

    @Override public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        presentation = new ViewOwnership(platform);
        catalog = new LauncherCatalog<>(new Entries(), new LauncherCatalog.Source() {
            @Override public List<String> installed() throws Exception { return platform.installed(); }
            @Override public boolean carPlayAvailable() { return platform.available(LauncherCatalog.CARPLAY); }
            @Override public LauncherCatalog.Template fallback() throws Exception { return template(field(manager(), "mMainAllApps")); }
        });
        ResourceGroup resources = new ResourceGroup();
        scope.own(resources);
        resources.own(() -> platform.drainMain(() -> {
            Exception failure = null;
            try { presentation.close(); } catch (Exception caught) { failure = caught; }
            try {
                Object manager = manager();
                if (manager == null) catalog.close();
                else synchronized (manager) { catalog.close(); }
            } catch (Exception caught) { if (failure == null) failure = caught; else failure.addSuppressed(caught); }
            if (failure != null) throw failure;
        }));
        for (Map.Entry<Method, Binding> entry : bindings.entrySet()) {
            Binding binding = entry.getValue();
            try {
                resources.own(hooks.install("launcher-all-apps", entry.getKey(), scope.callback(call -> {
                    if (call.receiver() != null && !binding.owner.isInstance(call.receiver())) return call.proceed();
                    return callback(scope, binding.role, call);
                })));
            } catch (HookRegistry.RejectedException rejected) { if (binding.required) throw rejected; platform.error(rejected); }
        }
        platform.watchApps(resources, new LauncherPlatform.Events() {
            @Override public void reload() { post(scope, () -> initialize(scope)); }
            @Override public void packages(String name) {
                platform.invalidatePackage(name);
                try {
                    if (packageTimer != null) packageTimer.close();
                    packageTimer = scope.schedule(300, () -> post(scope, () -> {
                        OemAccess.call(methods.type(dataName), "reload");
                        initialize(scope);
                    }));
                } catch (Exception failure) { scope.requireRestart(failure); }
            }
            @Override public void locale() { platform.invalidatePackage(""); post(scope, () -> grid(scope, 0)); }
            @Override public void chrome() {}
        });
    }

    @Override public void configure(ModuleScope scope, Map<String, String> configuration) throws Exception {
        pending = true;
        preparationDeadline = platform.now() + 20000;
        scope.schedule(0, () -> post(scope, () -> initialize(scope)));
    }
    @Override public boolean ready() { return !pending; }

    private Object callback(ModuleScope scope, String role, HookRegistry.Invocation call) throws Throwable {
        Object[] arguments = call.arguments();
        switch (role) {
            case "get":
                Object list = call.proceed();
                if (list != null && physical((Integer) arguments[0])) {
                    effect(scope, () -> {
                        Object manager = manager();
                        if (manager == null) { post(scope, () -> initialize(scope)); return; }
                        try { synchronized (manager) { catalog.reconcile(list(list), (Integer) arguments[0]); } }
                        catch (LauncherCatalog.TemplatePending waiting) { post(scope, () -> initialize(scope)); }
                    });
                }
                return list;
            case "load":
            case "reload":
                effect(scope, () -> restoreData(call.receiver()));
                Object loaded = call.proceed();
                post(scope, () -> initialize(scope));
                return loaded;
            case "bind":
            case "second-bind":
                Object holder = arguments[0];
                effect(scope, () -> restoreHolder(holder));
                Object bound = call.proceed();
                adapters.put(call.receiver(), true);
                if (!scope.stoppingOrFailed()) effect(scope, () -> present(call.receiver(), holder, (Integer) arguments[1], role.equals("second-bind")));
                return bound;
            case "click":
                Object view = arguments[0];
                Object tag = view == null ? null : OemAccess.call(view, "getTag");
                if (tag != null && beanType.isInstance(tag) && catalog.synthetic(tag)) {
                    call.disableFallback();
                    int display = screen(call.receiver(), view);
                    effect(scope, () -> { if (physical(display) && launch(catalog.target(tag), display)) OemAccess.call(call.receiver(), "dismiss"); });
                    return null;
                }
                return call.proceed();
            case "second-click":
                if (arguments[0] != null && catalog.synthetic(arguments[0])) { call.disableFallback(); effect(scope, () -> launch(catalog.target(arguments[0]), 1)); return null; }
                return call.proceed();
            case "start-intent": return startIntent(call);
            case "start-component": return startComponent(call);
            case "refresh":
                Object refreshed = call.proceed();
                if (call.receiver() != null && barType.isInstance(call.receiver())) bars.put(call.receiver(), true);
                post(scope, () -> initialize(scope));
                return refreshed;
            default: throw new IllegalArgumentException("Unknown All Apps callback role");
        }
    }

    private Object startIntent(HookRegistry.Invocation call) throws Throwable {
        Object intent = call.arguments()[1];
        int display = (Integer) call.arguments()[2];
        if (!physical(display) || intent == null) return call.proceed();
        Object component = OemAccess.call(intent, "getComponent");
        String name = component == null ? platform.resolvePackage(intent) : string(OemAccess.call(component, "getPackageName"));
        String activity = component == null ? "" : string(OemAccess.call(component, "getClassName"));
        if (shared && display == 1 && activity.equals("com.qinggan.secondlauncher.activity.SecondMainActivity")) {
            Object copy = OemAccess.construct(methods.type("android.content.Intent"), intent);
            OemAccess.call(copy, "setClassName", LauncherPolicy.LAUNCHER, "com.qinggan.secondlauncher.activity.AllAppActivity");
            call.arguments()[1] = copy;
        }
        if (dashboard(name, activity)) { call.disableFallback(); execute(LauncherCommand.Kind.DASHBOARD, display, ""); return null; }
        if (!activity.equals("ru.big.town.restoremode.carplay.CarPlayLauncherActivity") && fullscreen(name)) {
            call.disableFallback(); execute(LauncherCommand.Kind.FULLSCREEN, display, name); return null;
        }
        return call.proceed();
    }

    private Object startComponent(HookRegistry.Invocation call) throws Throwable {
        String name = string(call.arguments()[1]);
        String activity = string(call.arguments()[2]);
        int display = (Integer) call.arguments()[3];
        if (!physical(display)) return call.proceed();
        if (shared && display == 1 && activity.equals("com.qinggan.secondlauncher.activity.SecondMainActivity")) call.arguments()[2] = "com.qinggan.secondlauncher.activity.AllAppActivity";
        if (dashboard(name, activity)) { call.disableFallback(); execute(LauncherCommand.Kind.DASHBOARD, display, ""); return null; }
        if (!activity.equals("ru.big.town.restoremode.carplay.CarPlayLauncherActivity") && fullscreen(name)) {
            call.disableFallback(); execute(LauncherCommand.Kind.FULLSCREEN, display, name); return null;
        }
        return call.proceed();
    }

    private boolean dashboard(String name, String activity) {
        if (shared || !name.equals(LauncherCatalog.RESTORE)) return false;
        if (activity.startsWith(".")) activity = name + activity;
        return activity.equals(LauncherCatalog.RESTORE + ".MainActivity") || activity.equals(LauncherCatalog.RESTORE + ".VoiceAnimationPreviewActivity")
                || activity.equals("ru.big.town.restoremode.passenger.PassengerDashboardActivity");
    }
    private boolean fullscreen(String name) throws Exception {
        String csv = platform.setting("fullscreen_apps");
        if (csv != null) for (String value : csv.split(",")) if (value.trim().equals(name)) return true;
        return false;
    }
    private boolean launch(String target, int display) throws Exception {
        if (!platform.available(target)) return false;
        if (target.equals(LauncherCatalog.RESTORE)) return execute(LauncherCommand.Kind.DASHBOARD, display, "");
        if (!target.contains("/") && fullscreen(target)) return execute(LauncherCommand.Kind.FULLSCREEN, display, target);
        return execute(LauncherCommand.Kind.APP, display, target);
    }
    private boolean execute(LauncherCommand.Kind kind, int display, String target) throws Exception { return platform.execute(new LauncherCommand(kind, display, target, 0)); }

    private void initialize(ModuleScope scope) throws Exception {
        Object manager = manager();
        if (manager == null) { waitForData(scope); return; }
        try {
            platform.installed();
            synchronized (manager) { inject(manager); }
            capture(manager);
            grid(scope, 0);
        } catch (LauncherCatalog.TemplatePending waiting) { waitForData(scope); }
    }
    private Object manager() throws Exception { return OemAccess.call(methods.type(dataName), "getInstance"); }
    private void waitForData(ModuleScope scope) throws Exception {
        pending = true;
        if (platform.now() >= preparationDeadline) throw new IllegalStateException("All Apps preparation deadline exceeded");
        scope.schedule(500, () -> post(scope, () -> initialize(scope)));
    }

    private void inject(Object manager) throws Exception {
        for (int display = 0; display < 2; display++) {
            Object raw = field(manager, display == 0 ? "mMainAllApps" : "mSecondAllApps");
            if (raw != null) catalog.reconcile(list(raw), display);
        }
    }
    private void restoreData(Object manager) throws Exception {
        if (manager == null) manager = manager();
        if (manager == null) return;
        synchronized (manager) {
            for (String name : new String[] {"mMainAllApps", "mSecondAllApps"}) {
                Object raw = field(manager, name);
                OemList list = raw == null ? null : lists.get(raw);
                if (list != null) catalog.restore(list);
            }
        }
    }
    private OemList list(Object raw) {
        OemList known = lists.get(raw);
        if (known != null) return known;
        if (lists.size() >= 32) throw new IllegalStateException("All Apps live list limit reached");
        OemList created = new OemList(raw, beanType);
        lists.put(raw, created);
        return created;
    }

    private LauncherCatalog.Template template(Object raw) throws Exception {
        if (!(raw instanceof List<?>)) return null;
        for (Object bean : (List<?>) raw) {
            if (bean == null || !beanType.isInstance(bean)) continue;
            LauncherCatalog.Template template = new Entries().template(bean);
            if (template != null) return template;
        }
        return null;
    }

    private void restoreHolder(Object holder) throws Exception {
        for (String name : new String[] {"iconView", "nameView"}) {
            Object view = field(holder, name);
            if (view != null) presentation.restore(view);
        }
    }
    private void present(Object adapter, Object holder, int position, boolean passenger) throws Exception {
        Object raw = field(adapter, passenger ? "allAppList" : "mAppBeans");
        if (!(raw instanceof List<?>)) return;
        List<?> values = (List<?>) raw;
        if (position < 0 || position >= values.size()) return;
        Object bean = values.get(position);
        if (bean == null || !catalog.synthetic(bean)) return;
        String target = catalog.target(bean);
        Object icon = field(holder, "iconView");
        Object name = field(holder, "nameView");
        if (icon != null) {
            Object drawable = platform.appIcon(target);
            if (passenger && platform.image(icon)) presentation.write(icon, "drawable", drawable);
            else {
                if (platform.image(icon)) presentation.write(icon, "drawable", null);
                presentation.write(icon, "background", drawable);
            }
        }
        if (name != null) presentation.write(name, "text", platform.label(target));
    }

    private void capture(Object manager) throws Exception {
        Object listeners = field(manager, "mAllAppDataListeners");
        if (listeners instanceof List<?>) {
            List<?> snapshot;
            synchronized (manager) { snapshot = new ArrayList<>((List<?>) listeners); }
            for (Object listener : snapshot) if (listener != null && barType.isInstance(listener)) bars.put(listener, true);
        }
        List<Object> remaining = new ArrayList<>();
        for (Object root : platform.roots()) if (root != null) remaining.add(root);
        for (int index = 0; index < remaining.size(); index++) {
            if (index >= 4096) throw new IllegalStateException("Launcher view-tree limit reached");
            Object view = remaining.get(index);
            if (barType.isInstance(view)) bars.put(view, true);
            if (recyclerType != null && recyclerType.isInstance(view)) {
                Object adapter = OemAccess.call(view, "getAdapter");
                if (adapter != null && (methods.type(adapterName).isInstance(adapter) || isSecondAdapter(adapter))) adapters.put(adapter, true);
            }
            if (groupType != null && groupType.isInstance(view)) {
                int count = (Integer) OemAccess.call(view, "getChildCount");
                for (int child = 0; child < count; child++) { Object value = OemAccess.call(view, "getChildAt", child); if (value != null) remaining.add(value); }
            }
        }
    }
    private boolean isSecondAdapter(Object adapter) {
        try { return methods.type(SECOND_ADAPTER).isInstance(adapter); }
        catch (ClassNotFoundException absent) { return false; }
    }

    private void grid(ModuleScope scope, int attempt) throws Exception {
        boolean busy = false;
        Set<Object> notified = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object bar : bars.keys()) {
            Object adapter = field(bar, "mAllAppAdapter");
            Object layout = field(bar, "mLayoutManager");
            if (adapter == null || layout == null) continue;
            if (!notified.add(adapter)) continue;
            Object recycler = field(layout, "mRecyclerView");
            if (recycler != null) {
                try { if (Boolean.TRUE.equals(OemAccess.call(recycler, "isComputingLayout"))) { busy = true; continue; } }
                catch (NoSuchMethodException absent) { /* Older layout managers rely on notify's exception. */ }
            }
            Object frames = field(layout, "mItemFrames");
            if (frames != null) OemAccess.call(frames, "clear");
            if (!notifyAdapter(adapter)) { busy = true; continue; }
            if (recycler != null) OemAccess.call(recycler, "requestLayout");
        }
        for (Object adapter : adapters.keys()) if (notified.add(adapter) && !notifyAdapter(adapter)) busy = true;
        if (busy) {
            if (attempt >= 11) throw new IllegalStateException("All Apps grid remained busy");
            scope.schedule(200, () -> post(scope, () -> grid(scope, attempt + 1)));
        } else pending = false;
    }
    private boolean notifyAdapter(Object adapter) throws Exception {
        try { OemAccess.call(adapter, "notifyDataSetChanged"); return true; }
        catch (IllegalStateException failure) {
            String message = failure.getMessage();
            if (message != null && (message.contains("computing a layout") || message.contains("scrolling"))) return false;
            throw failure;
        }
    }

    private int screen(Object owner, Object view) throws Exception {
        Object value = field(owner, "mScreenId");
        if (value instanceof Integer && physical((Integer) value)) return (Integer) value;
        if (!shared && view != null) {
            Object display = OemAccess.call(view, "getDisplay");
            if (display != null) return (Integer) OemAccess.call(display, "getDisplayId");
        }
        return -1;
    }
    private static Object field(Object owner, String name) {
        try { return owner == null ? null : OemAccess.read(owner, name); }
        catch (ReflectiveOperationException absent) { return null; }
    }
    private static boolean physical(int value) { return value == 0 || value == 1; }
    private static String string(Object value) { return value == null ? "" : value.toString(); }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private void effect(ModuleScope scope, Work action) {
        try { action.run(); }
        catch (Exception failure) { scope.requireRestart(failure); platform.error(failure); }
    }
    private void post(ModuleScope scope, Work action) {
        try { platform.postMain(scope.guard(() -> { try { action.run(); } catch (Exception failure) { scope.requireRestart(failure); platform.error(failure); } })); }
        catch (Exception failure) { scope.requireRestart(failure); }
    }

    private static final class Binding {
        final Class<?> owner;
        final String role;
        final boolean required;
        Binding(Class<?> owner, String role, boolean required) { this.owner = owner; this.role = role; this.required = required; }
    }

    private final class Entries implements LauncherCatalog.Entries<Object> {
        @Override public String packageName(Object bean) throws Exception { return string(OemAccess.call(beanType.cast(bean), "getPackageName")); }
        @Override public String subtype(Object bean) throws Exception { return string(OemAccess.call(beanType.cast(bean), "getSubType")); }
        @Override public void subtype(Object bean, String value) throws Exception { OemAccess.call(beanType.cast(bean), "setSubType", value); }
        @Override public LauncherCatalog.Template template(Object bean) throws Exception {
            if (!Integer.valueOf(1).equals(OemAccess.call(beanType.cast(bean), "getType"))) return null;
            int icon = (Integer) OemAccess.call(bean, "getIcon");
            int name = (Integer) OemAccess.call(bean, "getNameRes");
            return icon > 0 && name > 0 ? new LauncherCatalog.Template(icon, name) : null;
        }
        @Override public Object create(LauncherCatalog.Template template, String name) throws Exception { return OemAccess.construct(beanType, template.icon, template.label, name); }
    }
}
