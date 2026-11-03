package ru.voyahtune.agent.modules;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import ru.voyahtune.agent.policy.LauncherCommand;
import ru.voyahtune.agent.policy.LauncherPolicy;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.runtime.ResourceGroup;
import ru.voyahtune.agent.runtime.ViewOwnership;

final class PassengerDockNavigation implements AutoCloseable {
    private final MethodAccess methods;
    private final LauncherPlatform platform;
    private final Object bar;
    private final Object group;
    private final Object button;
    private final Object air;
    private final Object temperature;
    private final boolean created;
    private final ViewOwnership ownership;
    private final ResourceGroup resources = new ResourceGroup();
    private Object clickListener;
    private boolean managed;

    private PassengerDockNavigation(MethodAccess methods, LauncherPlatform platform, Object bar, Object group,
            Object button, boolean created) throws Exception {
        this.methods = methods;
        this.platform = platform;
        this.bar = bar;
        this.group = group;
        this.button = button;
        this.created = created;
        air = PassengerDockModule.field(bar, "mScreenUpAirView");
        temperature = PassengerDockModule.field(bar, "mScreenUpTemperatureContentView");
        ownership = new ViewOwnership(new Properties(platform));
        resources.own(ownership);
    }

    static PassengerDockNavigation attach(MethodAccess methods, LauncherPlatform platform,
            ModuleScope scope, Object bar, Object group) throws Exception {
        Object context = OemAccess.call(group, "getContext");
        Object resources = OemAccess.call(context, "getResources");
        String packageName = (String) OemAccess.call(context, "getPackageName");
        int id = (Integer) OemAccess.call(resources, "getIdentifier", "screen_up_all_app_icon",
                "id", packageName);
        if (id == 0) return null;
        Object button = OemAccess.call(group, "findViewById", id);
        boolean created = button == null;
        if (created) {
            int layout = (Integer) OemAccess.call(resources, "getIdentifier",
                    "navigation_bar_main_up", "layout", packageName);
            if (layout == 0) return null;
            Object inflater = OemAccess.call(methods.type("android.view.LayoutInflater"), "from", context);
            Object root = OemAccess.call(inflater, "inflate", layout, group, false);
            button = OemAccess.call(root, "findViewById", id);
            if (button == null) return null;
            Object parent = OemAccess.call(button, "getParent");
            if (parent != null) OemAccess.call(parent, "removeView", button);
        }
        PassengerDockNavigation navigation =
                new PassengerDockNavigation(methods, platform, bar, group, button, created);
        try {
            navigation.install(methods, platform, scope);
            return navigation;
        } catch (Exception failure) {
            try {
                navigation.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
                scope.requireRestart(cleanup);
            }
            throw failure;
        }
    }

    private void install(MethodAccess methods, LauncherPlatform platform, ModuleScope scope)
            throws Exception {
        clickListener = listener(methods, "OnClickListener", "onClick", scope, platform, () -> {
            if (!platform.execute(new LauncherCommand(LauncherCommand.Kind.PASSENGER_ALL_APPS,
                    1, "", 0))) throw new IllegalStateException("Passenger catalog launch failed");
            ownership.write(button, "checked", true);
        });
        Object layoutListener = listener(methods, "OnLayoutChangeListener", "onLayoutChange",
                scope, platform, this::alignTemperature);
        observe(group, layoutListener);
        if (air != null) observe(air, layoutListener);
        if (created) {
            bindButtonField();
            // The OEM touch handler resolves this resource ID; generated IDs crash it.
            resources.own(() -> {
                if (OemAccess.call(button, "getParent") == group)
                    OemAccess.call(group, "removeView", button);
            });
            OemAccess.call(group, "addView", button);
            managed = true;
        }
    }

    private void bindButtonField() throws Exception {
        Field field;
        try {
            field = OemAccess.field(bar.getClass(), "mScreenUpAllAppView");
        } catch (NoSuchFieldException absent) {
            return;
        }
        Object previous = field.get(bar);
        resources.own(() -> {
            if (field.get(bar) == button) field.set(bar, previous);
        });
        field.set(bar, button);
    }

    private void observe(Object view, Object listener) throws Exception {
        resources.own(() -> OemAccess.call(view, "removeOnLayoutChangeListener", listener));
        OemAccess.call(view, "addOnLayoutChangeListener", listener);
    }

    private static Object listener(MethodAccess methods, String type, String event,
            ModuleScope scope, LauncherPlatform platform, ModuleScope.ConfigurationAction action)
            throws ClassNotFoundException {
        Class<?> listenerType = methods.type("android.view.View$" + type);
        return Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[] {listenerType},
                (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "equals": return proxy == arguments[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "VoyahTunePassenger" + type;
                        default:
                            if (method.getName().equals(event)) scope.listener(() -> {
                                try {
                                    action.apply();
                                } catch (Exception failure) {
                                    error(platform, failure);
                                }
                            }).run();
                            return null;
                    }
                });
    }

    boolean matches(Object candidate) throws Exception {
        return candidate == group && OemAccess.call(button, "getParent") == group;
    }

    void reconcile() throws Exception {
        managed |= (Integer) OemAccess.call(button, "getVisibility") != 0;
        if (!managed) return;
        ownership.write(button, "onClick", clickListener);
        ownership.write(button, "visibility", 0);
        Object seat = PassengerDockModule.field(bar, "mScreenUpSeatView");
        Object spacing = seat == null ? air : seat;
        if (spacing != null) ownership.write(button, "topMargin",
                OemAccess.read(OemAccess.call(spacing, "getLayoutParams"), "topMargin"));
        if (created) refreshTheme();
        LauncherPolicy.Top top = platform.top(1, false);
        if (top != null && top.packageName.equals(LauncherPolicy.LAUNCHER)
                && top.activity.endsWith(".AllAppActivity"))
            ownership.write(button, "checked", true);
        alignTemperature();
    }

    private void refreshTheme() {
        try {
            Object context = OemAccess.call(button, "getContext");
            Object resources = OemAccess.call(context, "getResources");
            int drawable = (Integer) OemAccess.call(resources, "getIdentifier",
                    "navigation_bar_icon_all_app_selector", "drawable",
                    OemAccess.call(context, "getPackageName"));
            if (drawable == 0) return;
            Object manager = OemAccess.call(methods.type("com.qinggan.theme.SkinResourceManager"),
                    "getInstance", context);
            OemAccess.call(button, "setBackground", OemAccess.call(manager, "getDrawable", drawable));
        } catch (ClassNotFoundException absent) {
            // The inflated OEM selector remains usable without a skin manager.
        } catch (Exception failure) {
            error(platform, failure);
        }
    }

    private void alignTemperature() throws Exception {
        if (!managed || air == null || temperature == null
                || (Integer) OemAccess.call(group, "getHeight") <= 0
                || (Integer) OemAccess.call(air, "getHeight") <= 0
                || Boolean.TRUE.equals(OemAccess.call(group, "isLayoutRequested"))
                || Boolean.TRUE.equals(OemAccess.call(air, "isLayoutRequested"))) return;
        Object parent = OemAccess.call(temperature, "getParent");
        if (parent == null) return;
        int top = 0;
        Object current = air;
        for (int depth = 0; current != parent; depth++) {
            if (current == null || depth >= 16) return;
            top += (Integer) OemAccess.call(current, "getTop");
            current = OemAccess.call(current, "getParent");
        }
        ownership.write(temperature, "topMargin", top);
    }

    @Override
    public void close() throws Exception {
        resources.close();
    }

    private static void error(LauncherPlatform platform, Exception failure) {
        try {
            platform.error(failure);
        } catch (RuntimeException ignored) {
            // Optional callbacks must not break OEM view dispatch.
        }
    }

    private static final class Properties implements ViewOwnership.Access {
        private final LauncherPlatform platform;

        Properties(LauncherPlatform platform) {
            this.platform = platform;
        }

        @Override
        public Object read(Object view, String property) throws Exception {
            switch (property) {
                case "visibility": return OemAccess.call(view, "getVisibility");
                case "checked": return OemAccess.call(view, "isChecked");
                case "topMargin": return OemAccess.read(OemAccess.call(view, "getLayoutParams"), "topMargin");
                case "onClick":
                    Object info = PassengerDockModule.field(view, "mListenerInfo");
                    return info == null ? null : OemAccess.read(info, "mOnClickListener");
                default: throw new IllegalArgumentException("Unknown passenger property: " + property);
            }
        }

        @Override
        public void write(Object view, String property, Object value) throws Exception {
            if (value == null ? read(view, property) == null : value.equals(read(view, property))) return;
            switch (property) {
                case "visibility": OemAccess.call(view, "setVisibility", value); break;
                case "checked": OemAccess.call(view, "setChecked", value); break;
                case "onClick": OemAccess.call(view, "setOnClickListener", value); break;
                case "topMargin":
                    Object parameters = OemAccess.call(view, "getLayoutParams");
                    OemAccess.write(parameters, "topMargin", value);
                    OemAccess.call(view, "setLayoutParams", parameters);
                    break;
                default: throw new IllegalArgumentException("Unknown passenger property: " + property);
            }
        }

        @Override
        public AutoCloseable retain(Object view, Object state) throws Exception {
            return platform.retain(view, state);
        }
    }
}
