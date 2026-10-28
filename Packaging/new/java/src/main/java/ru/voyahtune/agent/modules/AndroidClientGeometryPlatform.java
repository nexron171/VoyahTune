package ru.voyahtune.agent.modules;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.voyahtune.agent.runtime.AndroidMainThread;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.runtime.ResourceGroup;

public final class AndroidClientGeometryPlatform implements ClientGeometryModule.Platform {
    private static final Pattern INTEGER = Pattern.compile("^[\\s\\p{Z}\\uFEFF]*([+-]?[0-9]+)");
    private final Context context;
    private final MethodAccess methods;
    private final Handler main = new Handler(Looper.getMainLooper());

    public AndroidClientGeometryPlatform(Context context, MethodAccess methods) {
        this.context = java.util.Objects.requireNonNull(context);
        this.methods = methods;
    }

    @Override public String packageName() { return context.getPackageName(); }
    @Override public boolean fullscreen() {
        String csv = Settings.Global.getString(context.getContentResolver(), "voyahtune_fullscreen_apps");
        if (csv != null) for (String name : csv.split(",")) if (name.trim().equals(packageName())) return true;
        return false;
    }
    @Override public int density() {
        String value = Settings.Global.getString(context.getContentResolver(), "voyahtune_dpi_" + packageName());
        if (value == null) return 0;
        Matcher matcher = INTEGER.matcher(value);
        if (!matcher.find()) return 0;
        try {
            int density = Integer.parseInt(matcher.group(1));
            return density >= 100 && density <= 640 ? density : 0;
        } catch (NumberFormatException invalid) { return 0; }
    }

    private Object manager() throws Exception { return OemAccess.call(methods.type("android.view.WindowManagerGlobal"), "getInstance"); }
    @Override public List<?> roots() throws Exception { return (List<?>) OemAccess.call(manager(), "getWindowViews"); }
    @Override public void update(Object view, Object attributes) throws Exception { OemAccess.call(manager(), "updateViewLayout", view, attributes); }
    @Override public void postMain(Runnable action) { if (!main.post(action)) throw new IllegalStateException("Client main looper unavailable"); }
    @Override public void drainMain(AutoCloseable cleanup) throws Exception { AndroidMainThread.run(cleanup::close); }
    @Override public void watch(ResourceGroup resources, Runnable changed) throws Exception {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) { if (intent != null && "ru.big.town.anative.WIN_RELOAD".equals(intent.getAction())) changed.run(); }
        };
        resources.own(() -> {
            try { context.unregisterReceiver(receiver); }
            catch (IllegalArgumentException absent) {
                if (absent.getMessage() == null || !absent.getMessage().contains("Receiver not registered")) throw absent;
            }
        });
        context.registerReceiver(receiver, new IntentFilter("ru.big.town.anative.WIN_RELOAD"), "android.permission.WRITE_SECURE_SETTINGS", main);
    }
    @Override public void error(Exception failure) { Log.w("vt_app_client", "Client geometry unavailable", failure); }
}
