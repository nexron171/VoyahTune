package ru.voyahtune.agent.modules;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.IntegerSetting;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.runtime.ResourceGroup;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AndroidClusterPlatform implements ClusterModule.Platform {
    static final String SERVICE = "com.qinggan.cluster.service.InstrumentClusterService";
    final Context context;
    final MethodAccess methods;
    final HookRegistry hooks;
    private final Handler main = new Handler(Looper.getMainLooper());

    public AndroidClusterPlatform(Context context, MethodAccess methods, HookRegistry hooks) {
        if (context == null)
            throw new IllegalArgumentException("Application context required for cluster");
        this.context = context;
        this.methods = methods;
        this.hooks = hooks;
    }

    @Override
    public boolean enabled() {
        return !IntegerSetting.matches(
                Settings.Global.getString(
                        context.getContentResolver(), "voyahtune_cluster_navigation_enabled"),
                0);
    }

    @Override
    public boolean transliteration() {
        return IntegerSetting.matches(
                Settings.Global.getString(
                        context.getContentResolver(),
                        "voyahtune_cluster_navigation_transliteration"),
                1);
    }

    List<?> services() throws Exception {
        Object thread =
                OemAccess.call(methods.type("android.app.ActivityThread"), "currentActivityThread");
        if (thread == null) return List.of();
        Object services = OemAccess.read(thread, "mServices");
        return services instanceof Map<?, ?>
                ? new ArrayList<>(((Map<?, ?>) services).values())
                : List.of();
    }

    @Override
    public Object findTarget() throws Exception {
        Class<?> contract = methods.type(ClusterModule.CONTRACT);
        for (Object service : services()) {
            if (service == null
                    || !service.getClass().getName().contains("InstrumentClusterService")) continue;
            for (Class<?> type = service.getClass();
                    type != null && type.getName().startsWith("com.qinggan.cluster.");
                    type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    try {
                        field.setAccessible(true);
                        Object candidate = field.get(service);
                        if (contract.isInstance(candidate)) return candidate;
                    } catch (IllegalAccessException inaccessible) {
                        error(inaccessible);
                    }
                }
            }
        }
        return null;
    }

    Object clusterService() throws Exception {
        for (Object service : services())
            if (service != null
                    && service.getClass().getName().contains("InstrumentClusterService"))
                return service;
        throw new IllegalStateException("InstrumentClusterService is not initialized");
    }

    @Override
    public void postMain(Runnable action) {
        if (!main.post(action))
            throw new IllegalStateException("Cluster main looper rejected work");
    }

    @Override
    public void watch(ResourceGroup resources, Runnable changed) throws Exception {
        ContentObserver observer =
                new ContentObserver(main) {
                    @Override
                    public void onChange(boolean selfChange) {
                        changed.run();
                    }
                };
        resources.own(() -> context.getContentResolver().unregisterContentObserver(observer));
        context.getContentResolver()
                .registerContentObserver(
                        Settings.Global.getUriFor("voyahtune_cluster_navigation_enabled"),
                        false,
                        observer);
    }

    @Override
    public ClusterModule.Card card() {
        return new AndroidClusterCardBridge(this);
    }

    @Override
    public void error(Exception failure) {
        Log.e("vt_cluster", "Cluster integration unavailable", failure);
    }
}
