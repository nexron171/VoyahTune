package ru.voyahtune.agent.modules;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.ClusterCardController;
import ru.voyahtune.agent.policy.IntegerSetting;
import ru.voyahtune.agent.runtime.AndroidMainThread;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.runtime.ResourceGroup;

final class AndroidClusterCardBridge implements ClusterModule.Card {
    private static final String FRAGMENT = "com.qinggan.cluster.display.fragments.ClusterNaviFragment";
    private static final String PROTOCOL = "com.qinggan.cluster.service.protocol.voyah.VoyahInstrumentCluster";
    private static final String ACTION = "ru.big.town.anative.CLUSTER_NAVI";
    private static final String ENABLED = "voyahtune_cluster_navi";
    private static final String COMPONENT = "voyahtune_cluster_navi_component";
    private static final String THEME = "voyahtune_cluster_navi_theme";
    private final AndroidClusterPlatform platform;
    private ClusterCardController controller;
    private ModuleScope scope;
    private Method create;
    private Method presentation;
    private Object themeState;
    private volatile boolean closing;

    AndroidClusterCardBridge(AndroidClusterPlatform platform) { this.platform = platform; }

    @Override public void prepare() throws Exception {
        create = platform.methods.require(FRAGMENT, "createVirtualDisplay", "android.view.Surface", "int", "int");
        OemAccess.field(platform.methods.type(FRAGMENT), "mVirtualDisplay");
        OemAccess.field(platform.methods.type(AndroidClusterPlatform.SERVICE), "mInstrumentCluster");
        OemAccess.field(platform.methods.type(AndroidClusterPlatform.SERVICE), "mLocalCallback");
        themeState = OemAccess.read(platform.methods.type("com.qinggan.cluster.ClusterState"), "NAVI_SHOW_THEM");
        try { presentation = platform.methods.optional(PROTOCOL, "argsCallback", "int", "[Ljava.lang.Object;"); }
        catch (ClassNotFoundException absent) { presentation = null; }
    }

    @Override public void install(ModuleScope scope, ResourceGroup resources) throws Exception {
        this.scope = scope;
        controller = new ClusterCardController(new ClusterCardController.IO() {
            @Override public long now() { return SystemClock.elapsedRealtime(); }
            @Override public boolean enabled() { return IntegerSetting.matches(read(ENABLED), 1); }
            @Override public void enabled(boolean value) { write(ENABLED, value ? "1" : "0"); }
            @Override public void component(String component) { write(COMPONENT, component); }
            @Override public void theme(String theme) { write(THEME, theme); }
            @Override public int display() throws Exception {
                Object virtual = OemAccess.read(platform.methods.type(FRAGMENT), "mVirtualDisplay");
                if (virtual == null) return -1;
                Object display = OemAccess.call(virtual, "getDisplay");
                return display == null ? -1 : (Integer) OemAccess.call(display, "getDisplayId");
            }
            @Override public boolean navigatorRunning() {
                ActivityManager manager = platform.context.getSystemService(ActivityManager.class);
                List<ActivityManager.RunningAppProcessInfo> running = manager.getRunningAppProcesses();
                if (running != null) for (ActivityManager.RunningAppProcessInfo process : running) if (AndroidClusterCardBridge.this.component().getPackageName().equals(process.processName)) return true;
                return false;
            }
            @Override public void launch(int display, boolean restart) throws Exception {
                onMain(() -> {
                    ComponentName component = AndroidClusterCardBridge.this.component();
                    if (restart) {
                        try { OemAccess.call(platform.context.getSystemService(ActivityManager.class), "forceStopPackage", component.getPackageName()); }
                        catch (Exception unavailable) { platform.error(unavailable); }
                    }
                    Intent intent = new Intent().setComponent(component).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(display);
                    OemAccess.call(options, "setLaunchWindowingMode", 5);
                    options.setLaunchBounds(new Rect(480, 0, 1440, 650));
                    platform.context.startActivity(intent, options.toBundle());
                });
            }
            @Override public void announce(int state) throws Exception {
                onMain(() -> {
                    Object protocol = OemAccess.read(platform.clusterService(), "mInstrumentCluster");
                    if (protocol == null) throw new IllegalStateException("Cluster protocol is not initialized");
                    OemAccess.call(protocol, "syncReadyState", ClusterCardController.NAVI, state);
                });
            }
            @Override public void switchCard(String category) throws Exception {
                onMain(() -> {
                    Object service = platform.clusterService();
                    Object callback = OemAccess.read(service, "mLocalCallback");
                    Object protocol = OemAccess.read(service, "mInstrumentCluster");
                    if (callback != null) OemAccess.call(callback, "onCurrentItem", category);
                    if (protocol != null) OemAccess.call(protocol, "notifyReadyToShow", category);
                });
            }
            @Override public void applyTheme() throws Exception {
                onMain(() -> {
                    Object protocol = OemAccess.read(platform.clusterService(), "mInstrumentCluster");
                    if (protocol == null) throw new IllegalStateException("Cluster protocol is not initialized");
                    OemAccess.call(protocol, "setClusterState", themeState, resolveTheme());
                });
            }
            @Override public void schedule(long delay, Runnable task) throws Exception { scope.schedule(delay, task); }
            @Override public void error(Exception failure) { platform.error(failure); }
        });
        resources.own(() -> { closing = true; controller.close(); });
        resources.own(platform.hooks.install("cluster-card", create, scope.callback(invocation -> {
            Object result = invocation.proceed();
            try { controller.displayRecreated(); }
            catch (Exception unavailable) { platform.error(unavailable); }
            return result;
        })));
        if (presentation != null) {
            try {
                resources.own(platform.hooks.install("cluster-card", presentation, scope.callback(invocation -> {
                    try {
                        Object[] values = (Object[]) invocation.arguments()[1];
                        if (invocation.arguments()[0].equals(0) && values != null && values.length >= 2) {
                            if (IntegerSetting.matches(String.valueOf(values[1]), 1)) controller.presentation(String.valueOf(values[0]), 1);
                        }
                    } catch (Exception unavailable) { platform.error(unavailable); }
                    return invocation.proceed();
                })));
            } catch (HookRegistry.RejectedException unsupported) { platform.error(unsupported); }
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null || !ACTION.equals(intent.getAction())) return;
                scope.listener(() -> {
                    try {
                        int enable = intent.getIntExtra("enable", -1);
                        controller.broadcast(intent.getStringExtra("component"), intent.getStringExtra("theme"), enable == -1 ? null : enable,
                                intent.getIntExtra("restart", 1) == 1);
                    } catch (Exception unavailable) { platform.error(unavailable); }
                }).run();
            }
        };
        resources.own(() -> {
            try { platform.context.unregisterReceiver(receiver); }
            catch (IllegalArgumentException absent) { /* Registration can fail before creating the local receiver. */ }
        });
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) platform.context.registerReceiver(receiver, filter, "android.permission.WRITE_SECURE_SETTINGS", null, Context.RECEIVER_EXPORTED);
        else platform.context.registerReceiver(receiver, filter, "android.permission.WRITE_SECURE_SETTINGS", null);
    }

    private ComponentName component() {
        String value = read(COMPONENT);
        ComponentName selected = value == null ? null : ComponentName.unflattenFromString(value);
        return selected == null ? new ComponentName("ru.yandex.yandexnavi", "ru.yandex.yandexnavi.core.NavigatorActivity") : selected;
    }
    private String read(String key) { return Settings.Global.getString(platform.context.getContentResolver(), key); }
    private void write(String key, String value) {
        if (!Settings.Global.putString(platform.context.getContentResolver(), key, value)) throw new IllegalStateException("Cluster setting write rejected");
    }
    private int resolveTheme() {
        String value = read(THEME);
        String theme = value == null ? "night" : value.trim().toLowerCase(Locale.ROOT);
        if (theme.equals("day")) return 1;
        if (!theme.equals("auto")) return 2;
        return (platform.context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES ? 2 : 1;
    }
    private void onMain(NavigationSourceModule.MainAction action) throws Exception {
        if (closing) { AndroidMainThread.run(action::run); return; }
        platform.postMain(scope.listener(() -> {
            try { action.run(); }
            catch (Exception unavailable) { platform.error(unavailable); }
        }));
    }
    @Override public void configure(ModuleScope scope) throws Exception { controller.configure(); }
}
