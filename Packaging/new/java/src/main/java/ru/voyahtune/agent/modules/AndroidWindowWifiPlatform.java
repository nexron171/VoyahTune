package ru.voyahtune.agent.modules;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import ru.voyahtune.agent.runtime.AndroidMainThread;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.OemAccess;

public final class AndroidWindowWifiPlatform implements WindowWifiModule.Platform {
    private final Context context;
    private final MethodAccess methods;

    public AndroidWindowWifiPlatform(Context context, MethodAccess methods) {
        this.context = java.util.Objects.requireNonNull(context);
        this.methods = methods;
    }

    @Override public long bootCount() { return Settings.Global.getInt(context.getContentResolver(), "boot_count", -1); }
    @Override public long now() { return SystemClock.elapsedRealtime(); }
    @Override public String lease() { return Settings.Global.getString(context.getContentResolver(), "voyahtune_carplay_wifi_scan_lease"); }

    @Override public WindowWifiModule.Endpoint discover() throws Exception {
        Object service = OemAccess.call(methods.type("android.os.ServiceManager"), "getService", "wifi");
        if (service == null) throw new WindowWifiModule.Pending("Wi-Fi service unavailable");
        Object client = OemAccess.read(service, "mClientModeImpl");
        if (client == null) throw new WindowWifiModule.Pending("Wi-Fi client pending");
        Object manager = OemAccess.read(client, "mWifiConnectivityManager");
        if (manager == null) throw new WindowWifiModule.Pending("Wi-Fi connectivity manager pending");
        MethodAccess wifi = new MethodAccess(name -> Class.forName(name, false, manager.getClass().getClassLoader()));
        Method setter = wifi.require(manager.getClass().getName(), "setAutoJoinEnabledExternal", "boolean");
        Field enabled = OemAccess.field(manager.getClass(), "mAutoJoinEnabledExternal");
        if (setter.getReturnType() != void.class || enabled.getType() != boolean.class) throw new NoSuchMethodException("Wi-Fi autojoin ABI mismatch");
        Object eventHandler = OemAccess.read(manager, "mEventHandler");
        if (!(eventHandler instanceof Handler)) throw new IllegalStateException("Wi-Fi event handler unavailable");
        Handler handler = (Handler) eventHandler;
        return new WindowWifiModule.Endpoint() {
            @Override public Object manager() { return manager; }
            @Override public Method setter() { return setter; }
            @Override public boolean enabled() throws Exception { return enabled.getBoolean(manager); }
            @Override public void setEnabled(boolean desired) throws Exception { OemAccess.call(manager, setter.getName(), desired); }
            @Override public boolean post(Runnable action, long delay) { return handler.postDelayed(action, delay); }
            @Override public void remove(Runnable action) { handler.removeCallbacks(action); }
            @Override public void drain(AutoCloseable cleanup) throws Exception { AndroidMainThread.run(handler, cleanup::close); }
        };
    }

    @Override public void error(Exception failure) { Log.w("vt_windows", "CarPlay Wi-Fi scan pause unavailable", failure); }
}
