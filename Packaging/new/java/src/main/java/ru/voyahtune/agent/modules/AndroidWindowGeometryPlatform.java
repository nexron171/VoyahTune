package ru.voyahtune.agent.modules;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import ru.voyahtune.agent.policy.WindowPolicy;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.runtime.ResourceGroup;

public final class AndroidWindowGeometryPlatform implements WindowGeometryModule.Platform {
    private final Context context;
    private final MethodAccess methods;
    private final Handler main = new Handler(Looper.getMainLooper());

    public AndroidWindowGeometryPlatform(Context context, MethodAccess methods) {
        this.context = java.util.Objects.requireNonNull(context);
        this.methods = methods;
    }

    @Override public WindowPolicy policy() throws Exception {
        return WindowPolicy.read(key -> Settings.Global.getString(context.getContentResolver(), key), liftProperty());
    }

    private int liftProperty() {
        try { return (Integer) OemAccess.call(methods.type("android.os.SystemProperties"), "getInt", "persist.qg.canbus.bcm_screenAutoLiftFdb", 2); }
        catch (Exception unavailable) { return -1; }
    }

    @Override public int lift() throws Exception {
        int property = liftProperty();
        return property == 1 || property == 2 ? property : WindowPolicy.integer(key -> Settings.Global.getString(context.getContentResolver(), key), "voyahtune_screen_lift_type", 2);
    }
    @Override public boolean interactive() {
        PowerManager power = context.getSystemService(PowerManager.class);
        return power == null || power.isInteractive();
    }
    @Override public long now() { return SystemClock.elapsedRealtime(); }

    @Override public void watch(ResourceGroup resources, WindowGeometryModule.Events events) throws Exception {
        register(resources, new IntentFilter("ru.big.town.anative.WIN_RELOAD"), "android.permission.WRITE_SECURE_SETTINGS", intent -> events.reload());
        register(resources, new IntentFilter("action.qg.layout.changed"), null, intent -> events.lift(intent.getIntExtra("type", 2)));
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        register(resources, screen, null, intent -> events.screen(Intent.ACTION_SCREEN_ON.equals(intent.getAction())));
    }

    private interface Receive { void accept(Intent intent); }
    private void register(ResourceGroup resources, IntentFilter filter, String permission, Receive receive) throws Exception {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) { if (intent != null) receive.accept(intent); }
        };
        resources.own(() -> {
            try { context.unregisterReceiver(receiver); }
            catch (IllegalArgumentException absent) {
                if (absent.getMessage() == null || !absent.getMessage().contains("Receiver not registered")) throw absent;
            }
        });
        context.registerReceiver(receiver, filter, permission, main);
    }

    @Override public void withWindowManager(AutoCloseable action) throws Exception {
        Object manager = OemAccess.call(methods.type("android.os.ServiceManager"), "getService", "window");
        if (manager == null) throw new WindowPreparation.Pending("WindowManager pending");
        Object monitor = OemAccess.read(manager, "mGlobalLock");
        if (monitor == null) throw new WindowPreparation.Pending("WindowManager lock pending");
        synchronized (monitor) { action.close(); }
    }

    @Override public void traversal() throws Exception {
        Class<?> services = methods.type("com.android.server.LocalServices");
        for (String owner : new String[] {"com.android.server.wm.WindowManagerInternal", "android.view.WindowManagerInternal"}) {
            try {
                Class<?> contract = methods.type(owner);
                Object manager = OemAccess.call(services, "getService", contract);
                if (manager != null) { OemAccess.call(manager, "requestTraversalFromDisplayManager"); return; }
            } catch (ClassNotFoundException | NoSuchMethodException unsupported) { /* Android 11 OEMs use either contract package. */ }
        }
        throw new IllegalStateException("WindowManager traversal service unavailable");
    }

    @Override public void error(Exception failure) { Log.w("vt_windows", "Window geometry unavailable", failure); }
}
