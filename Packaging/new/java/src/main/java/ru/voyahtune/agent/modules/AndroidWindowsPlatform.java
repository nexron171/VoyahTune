package ru.voyahtune.agent.modules;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.SystemClock;
import android.util.Log;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.OemAccess;

public final class AndroidWindowsPlatform implements WindowsModule.Preparation,
        WindowPrivilegesModule.Platform, WindowMemoryProtectionModule.Platform {
    private final Context context;
    private final MethodAccess methods;
    private final AndroidWindowGeometryPlatform geometry;
    private int nativeUid = -1;
    private WindowPriorityRecalculation priorityRecalculation;

    private AndroidWindowsPlatform(Context context, MethodAccess methods) {
        this.context = java.util.Objects.requireNonNull(context);
        this.methods = methods;
        geometry = new AndroidWindowGeometryPlatform(context, methods);
    }

    public static WindowsModule create(Context context, MethodAccess methods, HookRegistry hooks) {
        AndroidWindowsPlatform platform = new AndroidWindowsPlatform(context, methods);
        return new WindowsModule(platform,
                new WindowPrivilegesModule(methods, hooks, platform),
                new WindowGeometryModule(methods, hooks, platform.geometry),
                new WindowMemoryProtectionModule(methods, hooks, platform),
                new WindowWifiModule(hooks, new AndroidWindowWifiPlatform(context, methods)));
    }

    @Override
    public long now() {
        return SystemClock.elapsedRealtime();
    }

    @Override
    public void pause(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    @Override
    public void probe() throws Exception {
        PackageManager packages = context.getPackageManager();
        if (packages == null) {
            throw new WindowPreparation.Pending("PackageManager pending");
        }
        try {
            nativeUid = packages.getPackageUid("ru.big.town.anative", 0);
        } catch (PackageManager.NameNotFoundException pending) {
            throw new WindowPreparation.Pending("Native package pending");
        }
        if (nativeUid < 0) {
            throw new WindowPreparation.Pending("Native UID unavailable");
        }
        geometry.withWindowManager(() -> {});
    }

    @Override
    public int nativeUid() {
        return nativeUid;
    }

    @Override
    public int callingUid() {
        return Binder.getCallingUid();
    }

    @Override
    public long clearIdentity() {
        return Binder.clearCallingIdentity();
    }

    @Override
    public void restoreIdentity(long identity) {
        Binder.restoreCallingIdentity(identity);
    }

    @Override
    public void prepareMemory() throws Exception {
        Object activityManager = OemAccess.call(
                methods.type("android.os.ServiceManager"), "getService", "activity");
        if (activityManager == null) {
            throw new IllegalStateException("ActivityManager unavailable for priority restoration");
        }
        priorityRecalculation = new WindowPriorityRecalculation(activityManager);
    }

    @Override
    public void recompute() throws Exception {
        priorityRecalculation.recompute();
    }

    @Override
    public void error(Exception failure) {
        Log.w("vt_windows", "Optional window integration unavailable", failure);
    }
}
