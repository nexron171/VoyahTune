package ru.voyahtune.agent.modules;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.OemAccess;

public final class AndroidMultidisplayPlatform implements MultidisplayModule.Platform {
    private static final String RELOAD_ACTION = "ru.big.town.anative.MD_RELOAD";
    private static final Pattern INTEGER_PREFIX = Pattern.compile("^[\\s\\p{Z}]*([+-]?[0-9]+)");
    private final Context context;
    private final Class<?> serviceManager;

    public AndroidMultidisplayPlatform(Context context, MethodAccess methods) throws Exception {
        if (context == null) throw new IllegalArgumentException("Application context required for multidisplay");
        this.context = context;
        Class<?> service = null;
        try {
            Method metadata = methods.optional("com.qinggan.os.ServiceManager", "getDpyTopAppInfo", "android.content.Context", "int", "int");
            if (metadata != null) service = methods.type("com.qinggan.os.ServiceManager");
        } catch (ClassNotFoundException absent) {
            // Whitelist widening remains available on firmware without the transfer metadata ABI.
        }
        serviceManager = service;
    }

    @Override
    public boolean enabled() {
        String value = Settings.Global.getString(context.getContentResolver(), "voyahtune_multidisplay");
        if (value == null || value.isEmpty()) return true;
        Matcher prefix = INTEGER_PREFIX.matcher(value);
        if (!prefix.find()) return false;
        try {
            return Long.parseLong(prefix.group(1)) == 1;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    @Override
    public boolean supportsTransfer() {
        return serviceManager != null;
    }

    @Override
    public String top(int display, int field) throws Exception {
        Object value = OemAccess.call(serviceManager, "getDpyTopAppInfo", context, display, field);
        return value == null ? null : value.toString();
    }

    @Override
    public AutoCloseable reloadReceiver(Runnable reload) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (intent != null && RELOAD_ACTION.equals(intent.getAction())) reload.run();
            }
        };
        IntentFilter filter = new IntentFilter(RELOAD_ACTION);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, filter);
        return () -> context.unregisterReceiver(receiver);
    }

    @Override
    public void trace(String event, Object[] arguments) {
        StringBuilder message = new StringBuilder(event);
        for (int index = 0; index < Math.min(arguments.length, 6); index++) {
            String value;
            try {
                value = String.valueOf(arguments[index]);
            } catch (RuntimeException unavailable) {
                value = "<unavailable>";
            }
            message.append(' ').append(value, 0, Math.min(value.length(), 140));
        }
        Log.i("vt_multidisplay", message.toString());
    }
}
