package ru.voyahtune.agent.modules;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import ru.voyahtune.agent.policy.DriveSelection;
import ru.voyahtune.agent.runtime.AndroidMainThread;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.OemAccess;

public final class AndroidAccPlatform implements AccModule.Platform {
    private final Context context;
    private final MethodAccess methods;

    public AndroidAccPlatform(Context context, MethodAccess methods) throws Exception {
        if (context == null) throw new IllegalArgumentException("Application context required for ACC");
        this.context = context;
        this.methods = methods;
        OemAccess.field(methods.type(AccModule.COMPONENT), "contentResolver");
        methods.require("com.qinggan.provider.QGSettings$System", "getInt", "android.content.ContentResolver", "java.lang.String", "int");
    }

    @Override public boolean supports(Object component) {
        return model(component, "isH97X") || model(component, "isH97Y");
    }

    private boolean model(Object component, String method) {
        try { return Boolean.TRUE.equals(OemAccess.call(component, method)); }
        catch (Exception unavailable) { return false; }
    }

    private ContentResolver resolver(Object component) throws Exception {
        return component == null ? context.getContentResolver() : (ContentResolver) OemAccess.read(component, "contentResolver");
    }

    @Override public int accountType(Object component) throws Exception {
        return (Integer) OemAccess.call(methods.type("com.qinggan.provider.QGSettings$System"), "getInt", resolver(component), "VehicleAccountInfo", 1);
    }

    @Override public boolean nativeCaller() { return trusted("ru.big.town.anative"); }
    @Override public boolean stockCaller() { return trusted("com.qinggan.app.vehiclesetting"); }

    private boolean trusted(String packageName) {
        try { return Binder.getCallingUid() == context.getPackageManager().getApplicationInfo(packageName, 0).uid; }
        catch (Exception unavailable) { return false; }
    }

    @Override public DriveSelection.Targets selected(Object component) throws Exception {
        return AndroidDriveSettings.selected(resolver(component), () -> {
            Object manager = OemAccess.call(methods.type("com.qinggan.account.AccountUserManager"), "getInstance");
            Object account = OemAccess.call(manager, "AccountInfoBean");
            if (account == null || Boolean.TRUE.equals(OemAccess.read(account, "isGuest"))) return "guest";
            Object name = OemAccess.call(account, "getName");
            if (name == null || name.toString().isEmpty()) return "guest";
            Object id = OemAccess.read(account, "accountId");
            return id == null ? null : id.toString();
        });
    }

    private Bundle hook(Object component, String action, Bundle arguments) throws Exception {
        return AndroidDriveSettings.hook(resolver(component), action, arguments);
    }

    private AccModule.Snapshot parse(Bundle snapshot) {
        return new AccModule.Snapshot(snapshot.getInt("acc", -1), snapshot.getLong("revision", -1), snapshot.getLong("cycle", -1),
                snapshot.getBoolean("smartSaveEnabled", false), snapshot.getString("energy"), snapshot.getBoolean("forcedEv", false), snapshot.getString("startup"));
    }

    @Override public AccModule.Snapshot snapshot(Object component) throws Exception { return parse(hook(component, "snapshot", null)); }

    @Override public AccModule.Snapshot observeAcc(Object component, int value) throws Exception {
        Bundle arguments = new Bundle();
        arguments.putInt("acc", value);
        return parse(hook(component, "acc", arguments));
    }

    @Override public boolean dispatchSettings(Object component) throws Exception {
        Bundle response = hook(component, "dispatchSettings", null);
        return response.getBoolean("settingsDispatched", false) || !"pending".equals(response.getString("settingsStartup"));
    }

    @Override public void persist(Object component, int drive, int energy) throws Exception {
        Bundle arguments = new Bundle();
        String mode = DriveSelection.driveName(drive);
        String power = DriveSelection.energyName(energy);
        if (mode != null) arguments.putString("mode", mode);
        if (power != null) arguments.putString("energy", power);
        hook(component, "user", arguments);
    }

    @Override public boolean claim(Object component, long revision) throws Exception {
        Bundle arguments = new Bundle();
        arguments.putLong("revision", revision);
        return hook(component, "claim", arguments).getBoolean("claimed", false);
    }

    @Override public void complete(Object component, long revision, boolean accepted) throws Exception {
        Bundle arguments = new Bundle();
        arguments.putLong("revision", revision);
        arguments.putBoolean("accepted", accepted);
        hook(component, "complete", arguments);
    }

    @Override public Object discover() throws Exception {
        AtomicReference<Object> selected = new AtomicReference<>();
        AndroidMainThread.run(() -> {
            Object thread = OemAccess.call(methods.type("android.app.ActivityThread"), "currentActivityThread");
            if (thread == null) return;
            Object services = OemAccess.read(thread, "mServices");
            if (!(services instanceof Map<?, ?>)) return;
            for (Object service : new ArrayList<>(((Map<?, ?>) services).values())) {
                if (!service.getClass().getName().equals("com.qinggan.canbus.service.CanBusService")) continue;
                Object component = OemAccess.read(service, "mCanBusComponent");
                if (methods.type(AccModule.COMPONENT).isInstance(component)) selected.set(component);
                break;
            }
        });
        return selected.get();
    }

    @Override public int submit(Object component, Object bundle) throws Exception {
        return (Integer) OemAccess.call(component, "setVehicleAndAirConditionBundleState", null, bundle);
    }

    @Override public void error(String operation, Exception failure) { Log.e("vt_acc", operation, failure); }
}
