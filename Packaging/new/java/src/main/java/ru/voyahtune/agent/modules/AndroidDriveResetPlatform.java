package ru.voyahtune.agent.modules;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import java.util.Map;
import java.util.WeakHashMap;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.DriveSelection;
import ru.voyahtune.agent.runtime.AndroidMainThread;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;

public final class AndroidDriveResetPlatform implements DriveResetModule.Platform {
    private final Context context;
    private final MethodAccess methods;
    private final Map<View, Integer> menuVisibility = new WeakHashMap<>();

    public AndroidDriveResetPlatform(Context context, MethodAccess methods) {
        if (context == null) throw new IllegalArgumentException("Application context required for drive reset");
        this.context = context;
        this.methods = methods;
    }

    @Override public boolean supportsReset() {
        return model("com.qinggan.utils.AppCommonUtils", "is97X") || model("com.qinggan.app.vehiclesetting.utils.Utils", "is97Y");
    }

    @Override public boolean supportsUserSelection() {
        return supportsReset() || model("com.qinggan.utils.AppCommonUtils", "is97C");
    }

    private boolean model(String owner, String method) {
        try {
            return Boolean.TRUE.equals(OemAccess.call(methods.type(owner), method));
        } catch (Exception unsupported) {
            return false;
        }
    }

    @Override public DriveSelection.Targets selected() throws Exception {
        return AndroidDriveSettings.selected(context.getContentResolver(), () -> {
            Object account = OemAccess.call(methods.type("com.qinggan.app.vehiclesetting.utils.Utils"), "getAccountId");
            return account == null ? null : account.toString();
        });
    }

    @Override public void save(String key, String value) {
        Bundle arguments = new Bundle();
        arguments.putString(key, value);
        AndroidDriveSettings.hook(context.getContentResolver(), "user", arguments);
    }

    @Override public void energyMenu(Object activity) throws Exception {
        Context activityContext = (Context) activity;
        int id = activityContext.getResources().getIdentifier("rb_menu_energy", "id", "com.qinggan.app.vehiclesetting");
        View menu = id == 0 ? null : (View) OemAccess.call(activity, "findViewById", id);
        if (menu == null) return;
        boolean enabled = false;
        long identity = Binder.clearCallingIdentity();
        try {
            Bundle settings = activityContext.getContentResolver().call(AndroidDriveSettings.ENDPOINT, "stockEnergyStatistics", null, null);
            enabled = settings != null && settings.getBoolean("enabled", false);
        } catch (RuntimeException unavailable) {
            error("energy-menu-settings", unavailable);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        int visibility = enabled || model("com.qinggan.utils.AppCommonUtils", "is97C") ? View.VISIBLE : View.GONE;
        AndroidMainThread.run(() -> {
            menuVisibility.putIfAbsent(menu, menu.getVisibility());
            menu.setVisibility(visibility);
        });
    }

    @Override public void optionalBridges(ModuleScope scope, HookRegistry hooks) throws Exception {
        AndroidPowerHoldBridge.install(context, methods, scope, hooks);
    }

    @Override public void error(String operation, Exception failure) {
        Log.e("vt_drive_reset", operation, failure);
    }

    @Override public void close() throws Exception {
        AndroidMainThread.run(() -> {
            for (Map.Entry<View, Integer> entry : menuVisibility.entrySet()) entry.getKey().setVisibility(entry.getValue());
            menuVisibility.clear();
        });
    }
}
