package ru.voyahtune.agent.modules;

import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;

import ru.voyahtune.agent.policy.LauncherCatalog;
import ru.voyahtune.agent.policy.LauncherCommand;
import ru.voyahtune.agent.policy.LauncherPolicy;
import ru.voyahtune.agent.runtime.AndroidMainThread;
import ru.voyahtune.agent.runtime.AndroidViewOwnership;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.runtime.ResourceGroup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AndroidLauncherPlatform implements LauncherPlatform {
    private static final String PREFIX = "voyahtune_";
    private static final String REVEAL = "carplay_chrome_reveal_until_";
    private static final String RESTORE = LauncherCatalog.RESTORE;
    private static final String NATIVE = LauncherPolicy.NATIVE;
    private static final String LAUNCHER = LauncherPolicy.LAUNCHER;
    private static final String LEGACY_CARPLAY = RESTORE + "/com.shilapi.xcertplay.DiPlayActivity";
    private static final String CARPLAY_ACTIVITY =
            "ru.big.town.restoremode.carplay.CarPlayLauncherActivity";
    private static final String DASHBOARD = RESTORE + ".MainActivity";
    private static final String PASSENGER = RESTORE + ".passenger.PassengerDashboardActivity";
    private static final String DOCK_RELOAD = NATIVE + ".DOCK_RELOAD";
    private static final String WIN_RELOAD = NATIVE + ".WIN_RELOAD";
    private static final int ACTIVITY_FLAGS =
            Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP;
    private static final List<String> SETTINGS =
            List.of(
                    "dock1",
                    "dock2",
                    "dock1Icon",
                    "dock2Icon",
                    "passengerDockSlot1",
                    "passengerDockSlot2",
                    "fullscreen_apps",
                    "dockpin",
                    "freeform",
                    "dock1HasSplit",
                    "dock2HasSplit",
                    "dock1LongAction",
                    "dock2LongAction",
                    "dockHomeShortVoyahTune",
                    "dockAllAppsShortVoyahTune",
                    "floathome",
                    "screen_lift_type",
                    "carplay_hide_system_bars",
                    "dockLaunchGuard0",
                    "dockLaunchGuard1");
    private final Context context;
    private final MethodAccess methods;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AndroidViewOwnership views = new AndroidViewOwnership();
    private final SharedPreferences chrome;
    private final Map<String, Drawable.ConstantState> icons = new LinkedHashMap<>();
    private final Map<String, CharSequence> labels = new LinkedHashMap<>();
    private List<String> installed;
    private Object statusBus;
    private PassengerDockLayout passengerLayout;

    public AndroidLauncherPlatform(Context context, MethodAccess methods) {
        if (context == null)
            throw new IllegalArgumentException("Launcher application context required");
        this.context =
                context.getApplicationContext() == null ? context : context.getApplicationContext();
        this.methods = methods;
        chrome = this.context.getSharedPreferences(PREFIX + "carplay_chrome", Context.MODE_PRIVATE);
    }

    @Override
    public Map<String, String> settings() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : SETTINGS) values.put(key, setting(key));
        return values;
    }

    @Override
    public String setting(String key) {
        String value = Settings.Global.getString(context.getContentResolver(), PREFIX + key);
        return value == null || value.isEmpty() ? "none" : value;
    }

    @Override
    public LauncherPolicy.Top top(int display, boolean shared) {
        try {
            Object value =
                    OemAccess.call(
                            methods.type("com.qinggan.launcher.base.utils.AppUtils"),
                            "getTopAppInfo",
                            context,
                            display,
                            4);
            String separator = "/";
            if (!shared) {
                separator = "|";
                try {
                    Object configured =
                            OemAccess.read(
                                    methods.type("com.qinggan.account.AccountConstantUtil"),
                                    "SEPARATOR");
                    if (configured != null) separator = configured.toString();
                } catch (ReflectiveOperationException absent) {
                    /* Older SDKs use the literal pipe separator. */
                }
            }
            LauncherPolicy.Top parsed =
                    LauncherPolicy.top(value == null ? "" : value.toString(), separator, true);
            return parsed.packageName.isEmpty() ? new LauncherPolicy.Top("", "", false) : parsed;
        } catch (Exception unavailable) {
            return new LauncherPolicy.Top("", "", false);
        }
    }

    @Override
    public long now() {
        return SystemClock.elapsedRealtime();
    }

    @Override
    public int lift() {
        try {
            Object value =
                    OemAccess.call(
                            methods.type("android.os.SystemProperties"),
                            "getInt",
                            "persist.qg.canbus.bcm_screenAutoLiftFdb",
                            0);
            if (Integer.valueOf(1).equals(value) || Integer.valueOf(2).equals(value))
                return (Integer) value;
        } catch (Exception unavailable) {
            /* Persisted UI setting covers boot before the property appears. */
        }
        return "1".equals(setting("screen_lift_type")) ? 1 : 2;
    }

    @Override
    public long revealRemaining(int display) {
        long remaining =
                Settings.Global.getLong(context.getContentResolver(), PREFIX + REVEAL + display, 0)
                        - now();
        return remaining > 0 && remaining <= 10000 ? remaining : 0;
    }

    @Override
    public boolean chromeHidden(int display) {
        return chrome.getBoolean("hidden" + display, false);
    }

    @Override
    public void chromeHidden(int display, boolean hidden) {
        if (chromeHidden(display) != hidden
                && !chrome.edit().putBoolean("hidden" + display, hidden).commit())
            throw new IllegalStateException("Launcher chrome ownership persistence failed");
    }

    @Override
    public synchronized boolean syncStatusBar(int display, boolean visible) {
        try {
            if (statusBus == null)
                statusBus = OemAccess.construct(methods.type("com.qinggan.bus.QGBus"), context);
            Object event = OemAccess.construct(methods.type("com.qinggan.bus.QGBusEvent"));
            Bundle data = new Bundle();
            data.putBoolean("isVisible", visible);
            data.putInt("displayId", display);
            OemAccess.call(event, "setDestination", "com.qinggan.systemui");
            OemAccess.call(event, "setEventType", "VOYAHTUNE_CARPLAY_STATUS_BAR_SYNC");
            OemAccess.call(event, "setData", data);
            OemAccess.call(statusBus, "publish", event);
            return true;
        } catch (Exception failure) {
            error(failure);
            return false;
        }
    }

    @Override
    public void reportDockHidden(int display, boolean hidden) {
        try {
            String key = PREFIX + "carplay_dock_hidden_" + display;
            int value = hidden ? 1 : 0;
            if (Settings.Global.getInt(context.getContentResolver(), key, -1) != value
                    && !Settings.Global.putInt(context.getContentResolver(), key, value))
                throw new IllegalStateException("Dock visibility report rejected");
        } catch (Exception failure) {
            error(failure);
        }
    }

    @Override
    public Object model() throws Exception {
        return OemAccess.read(context, "mLauncherModel");
    }

    @Override
    public List<?> roots() throws Exception {
        Object windows =
                OemAccess.call(methods.type("android.view.WindowManagerGlobal"), "getInstance");
        return new ArrayList<>((List<?>) OemAccess.call(windows, "getWindowViews"));
    }

    @Override
    public synchronized List<String> installed() {
        if (installed != null) return installed;
        PackageManager packages = context.getPackageManager();
        List<String> result = new ArrayList<>();
        for (ApplicationInfo application : packages.getInstalledApplications(0)) {
            String name = application.packageName;
            if (application.enabled
                    && !name.equals(LAUNCHER)
                    && ((application.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                            || name.equals(RESTORE))
                    && packages.getLaunchIntentForPackage(name) != null) result.add(name);
        }
        installed = List.copyOf(result);
        return installed;
    }

    private static String normalize(String target) {
        return target.equals(LEGACY_CARPLAY) ? LauncherCatalog.CARPLAY : target;
    }

    private static ComponentName component(String target) {
        target = normalize(target);
        if (target.equals(RESTORE)) return new ComponentName(RESTORE, DASHBOARD);
        return target.contains("/") ? ComponentName.unflattenFromString(target) : null;
    }

    @Override
    public boolean available(String target) {
        if (target == null || target.isEmpty() || target.equals("none")) return false;
        PackageManager packages = context.getPackageManager();
        try {
            ComponentName component = component(target);
            if (component == null) {
                ApplicationInfo application = packages.getApplicationInfo(target, 0);
                return application.enabled && packages.getLaunchIntentForPackage(target) != null;
            }
            ActivityInfo activity = packages.getActivityInfo(component, 0);
            int state = packages.getComponentEnabledSetting(component);
            boolean enabled =
                    state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            || state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                                    && activity.enabled;
            return enabled && activity.applicationInfo.enabled && activity.exported;
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException absent) {
            return false;
        }
    }

    @Override
    public synchronized Object appIcon(String target) throws Exception {
        target = normalize(target);
        Drawable.ConstantState cached = icons.get(target);
        if (cached != null) return cached.newDrawable(context.getResources()).mutate();
        PackageManager packages = context.getPackageManager();
        ComponentName component = component(target);
        Drawable drawable =
                component == null
                        ? packages.getApplicationIcon(target)
                        : packages.getActivityIcon(component);
        cacheIcon(target, drawable);
        return drawable.mutate();
    }

    @Override
    public synchronized CharSequence label(String target) throws Exception {
        target = normalize(target);
        CharSequence cached = labels.get(target);
        if (cached != null) return cached;
        PackageManager packages = context.getPackageManager();
        ComponentName component = component(target);
        CharSequence label =
                component == null
                        ? packages.getApplicationInfo(target, 0).loadLabel(packages)
                        : packages.getActivityInfo(component, 0).loadLabel(packages);
        if (labels.size() >= 64) labels.remove(labels.keySet().iterator().next());
        labels.put(target, label);
        return label;
    }

    @Override
    public synchronized void invalidatePackage(String name) {
        installed = null;
        icons.clear();
        labels.clear();
    }

    private void cacheIcon(String key, Drawable drawable) {
        Drawable.ConstantState state = drawable.getConstantState();
        if (state != null) {
            if (icons.size() >= 64) icons.remove(icons.keySet().iterator().next());
            icons.put(key, state);
        }
    }

    @Override
    public synchronized Object icon(String target, int slot, String style) throws Exception {
        String resource =
                style.equals("navigation")
                        ? "dock_navigation_icon"
                        : style.equals("music")
                                ? "dock_music_icon"
                                : normalize(target).equals(LauncherCatalog.CARPLAY)
                                        ? "carplay_dock_logo"
                                        : "";
        if (!resource.isEmpty()) {
            try {
                Resources resources =
                        context.getPackageManager().getResourcesForApplication(RESTORE);
                int id = resources.getIdentifier(resource, "drawable", RESTORE);
                if (id != 0) return resources.getDrawable(id, null).mutate();
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException absent) {
                /* Missing optional artwork falls back to the installed activity icon. */
            }
        }
        Drawable original = (Drawable) appIcon(target);
        Bitmap bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        original.setBounds(0, 0, 50, 50);
        original.draw(canvas);
        BitmapDrawable drawable = new BitmapDrawable(context.getResources(), bitmap);
        drawable.setGravity(Gravity.CENTER);
        drawable.setBounds(0, 0, 50, 50);
        return drawable;
    }

    @Override
    public void passengerDock(Object bar, Object driver, String first, String second) throws Exception {
        if (passengerLayout != null && !passengerLayout.matches(bar)) clearPassengerDock();
        if (passengerLayout == null) passengerLayout = PassengerDockLayout.attach(bar, driver, this, methods);
        if (passengerLayout != null) {
            try {
                passengerLayout.update(driver, first, second);
            } catch (Exception failure) {
                clearPassengerDock();
                throw failure;
            }
        }
    }

    @Override
    public void passengerSelection(String packageName) {
        if (passengerLayout != null) passengerLayout.selected(packageName);
    }

    @Override
    public void clearPassengerDock() throws Exception {
        if (passengerLayout != null) {
            passengerLayout.close();
            passengerLayout = null;
        }
    }

    @Override
    public boolean execute(LauncherCommand command) {
        try {
            switch (command.kind) {
                case DASHBOARD:
                    Intent dashboard =
                            new Intent(RESTORE + ".OPEN_DASHBOARD")
                                    .setClassName(
                                            RESTORE, command.display == 1 ? PASSENGER : DASHBOARD);
                    start(dashboard, command.display, true);
                    return true;
                case TASK_MANAGER:
                    start(
                            new Intent().setClassName(RESTORE, RESTORE + ".TaskManagerActivity"),
                            command.display,
                            false);
                    return true;
                case STOCK_HOME:
                    Intent home =
                            new Intent(Intent.ACTION_MAIN)
                                    .addCategory(Intent.CATEGORY_HOME)
                                    .setClassName(LAUNCHER, LAUNCHER + ".activity.MainActivity");
                    try {
                        start(home, command.display, false);
                    } catch (ActivityNotFoundException | SecurityException rejected) {
                        start(
                                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                                command.display,
                                false);
                    }
                    return true;
                case PASSENGER_ALL_APPS:
                    launchOem(
                            new Intent()
                                    .setClassName(
                                            LAUNCHER,
                                            "com.qinggan.secondlauncher.activity.AllAppActivity"),
                            1);
                    return true;
                case APP:
                    ComponentName component = component(command.target);
                    Intent app =
                            component == null
                                    ? context.getPackageManager()
                                            .getLaunchIntentForPackage(command.target)
                                    : new Intent().setComponent(component);
                    if (app == null) return false;
                    launchOem(app, command.display);
                    return true;
                default:
                    String action;
                    switch (command.kind) {
                        case EMBEDDED:
                            action = "OPEN_ON_DISPLAY";
                            break;
                        case FREEFORM:
                            action = "OPEN_FREEFORM";
                            break;
                        case FULLSCREEN:
                            action = "OPEN_FULLSCREEN";
                            break;
                        case DOCK_SPLIT:
                            action = "OPEN_DOCK_SPLIT";
                            break;
                        case DOCK_LONG_PRESS:
                            action = "OPEN_DOCK_LONG_PRESS";
                            break;
                        default:
                            throw new IllegalArgumentException("Unsupported launcher command");
                    }
                    Intent bridge =
                            new Intent(NATIVE + "." + action)
                                    .setClassName(NATIVE, NATIVE + ".SetModesReceiverDynamic");
                    bridge.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    bridge.putExtra("pkg", command.target);
                    bridge.putExtra("display", command.display);
                    if (command.slot > 0) bridge.putExtra("slot", command.slot);
                    context.sendBroadcast(bridge);
                    return true;
            }
        } catch (Exception failure) {
            error(failure);
            return false;
        }
    }

    private void start(Intent intent, int display, boolean fullscreen) {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(display);
        Bundle bundle = options.toBundle();
        if (fullscreen) bundle.putInt("android.activity.windowingMode", 1);
        context.startActivity(intent.addFlags(ACTIVITY_FLAGS), bundle);
    }

    private void launchOem(Intent intent, int display) throws Exception {
        OemAccess.call(
                methods.type("com.qinggan.launcher.base.utils.AppLauncher"),
                "startApp",
                context,
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                display);
    }

    @Override
    public String resolvePackage(Object raw) {
        Intent intent = (Intent) raw;
        if (intent.getPackage() != null) return intent.getPackage();
        ResolveInfo resolved =
                context.getPackageManager()
                        .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved == null || resolved.activityInfo == null
                ? ""
                : resolved.activityInfo.packageName;
    }

    @Override
    public Object listener(LongGesture gesture) {
        return (View.OnLongClickListener) gesture::click;
    }

    @Override
    public Object read(Object view, String property) throws Exception {
        return views.read(view, property);
    }

    @Override
    public void write(Object view, String property, Object value) {
        views.write(view, property, value);
    }

    @Override
    public AutoCloseable retain(Object view, Object state) {
        return views.retain(view, state);
    }

    @Override
    public boolean image(Object view) {
        return view instanceof ImageView;
    }

    @Override
    public void invalidate(Object view) {
        ((View) view).invalidate();
    }

    @Override
    public boolean layout(Object view) {
        return ((View) view).getLayoutParams() != null;
    }

    @Override
    public void postMain(Runnable action) {
        if (!main.post(action))
            throw new IllegalStateException("Launcher main looper rejected work");
    }

    @Override
    public void drainMain(AutoCloseable cleanup) throws Exception {
        AndroidMainThread.run(cleanup::close);
    }

    @Override
    public void watch(ResourceGroup resources, Events events) throws Exception {
        BroadcastReceiver receiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ignored, Intent intent) {
                        if (intent != null
                                && (DOCK_RELOAD.equals(intent.getAction())
                                        || WIN_RELOAD.equals(intent.getAction()))) events.reload();
                    }
                };
        IntentFilter filter = new IntentFilter(DOCK_RELOAD);
        filter.addAction(WIN_RELOAD);
        register(resources, receiver, filter, "android.permission.WRITE_SECURE_SETTINGS");
        ContentObserver observer =
                new ContentObserver(main) {
                    @Override
                    public void onChange(boolean selfChange) {
                        events.reload();
                        events.chrome();
                    }
                };
        resources.own(() -> context.getContentResolver().unregisterContentObserver(observer));
        for (String key :
                List.of("carplay_hide_system_bars", "fullscreen_apps", REVEAL + 0, REVEAL + 1))
            context.getContentResolver()
                    .registerContentObserver(
                            Settings.Global.getUriFor(PREFIX + key), false, observer);
        watchApps(resources, events);
    }

    @Override
    public void watchApps(ResourceGroup resources, Events events) throws Exception {
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        register(resources, appEvents(events), packages, null);
        register(
                resources, appEvents(events), new IntentFilter(Intent.ACTION_LOCALE_CHANGED), null);
    }

    private static BroadcastReceiver appEvents(Events events) {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                if (intent == null) return;
                if (Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) events.locale();
                else
                    events.packages(
                            intent.getData() == null
                                    ? ""
                                    : intent.getData().getSchemeSpecificPart());
            }
        };
    }

    private void register(
            ResourceGroup resources,
            BroadcastReceiver receiver,
            IntentFilter filter,
            String permission)
            throws Exception {
        boolean[] registered = {false};
        resources.own(
                () -> {
                    if (registered[0]) {
                        context.unregisterReceiver(receiver);
                        registered[0] = false;
                    }
                });
        context.registerReceiver(receiver, filter, permission, main);
        registered[0] = true;
    }

    @Override
    public void error(Exception failure) {
        Log.e("vt_launcher", "Launcher integration failed", failure);
    }
}
