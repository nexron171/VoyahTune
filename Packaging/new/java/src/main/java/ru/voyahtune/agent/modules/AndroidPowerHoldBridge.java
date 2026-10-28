package ru.voyahtune.agent.modules;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Method;
import ru.voyahtune.agent.CloseOperation;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.PowerHoldController;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;

final class AndroidPowerHoldBridge {
    private static final String MANAGER = "com.qinggan.scene.powerhold.PowerHoldModeManager";
    private static final String PERMISSION = "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";
    private static final String REQUEST = "ru.big.town.anative.POWER_HOLD_OEM_REQUEST";
    private static final String STATUS = "ru.big.town.anative.POWER_HOLD_OEM_STATUS";

    private AndroidPowerHoldBridge() {}

    static void install(Context context, MethodAccess methods, ModuleScope scope, HookRegistry hooks) throws Exception {
        Class<?> managerClass;
        Object parking;
        Object batteryState;
        Method notification;
        try {
            managerClass = methods.type(MANAGER);
            notification = methods.require(MANAGER, "notifyActiveStatusChanged", "boolean");
            methods.require(MANAGER, "getInstance");
            methods.require(MANAGER, "getPowerHoldModeStatus");
            methods.require(MANAGER, "enterPowerHoldMode", "boolean", "int");
            parking = OemAccess.read(methods.type("com.qinggan.canbus.GearState"), "Parking");
            batteryState = OemAccess.read(methods.type("com.qinggan.canbus.VehicleState"), "BMS_SOC_DISPLAY");
            OemAccess.field(managerClass, "mContext");
            OemAccess.field(managerClass, "mCanBusManager");
        } catch (ReflectiveOperationException unsupported) {
            log(unsupported);
            return;
        }
        PowerHoldController controller = new PowerHoldController(() -> {
            Object manager = ready(managerClass);
            Object canBus = OemAccess.read(manager, "mCanBusManager");
            return new PowerHoldController.Manager() {
                @Override public Boolean parking() throws Exception {
                    Object gear = OemAccess.call(canBus, "getGearStatus");
                    return gear == null ? null : gear.equals(parking);
                }
                @Override public int battery() throws Exception { return (Integer) OemAccess.call(canBus, "getVehicleState", batteryState); }
                @Override public int status() throws Exception { return (Integer) OemAccess.call(manager, "getPowerHoldModeStatus"); }
                @Override public void activate(boolean engine) throws Exception { OemAccess.call(manager, "enterPowerHoldMode", engine, -1); }
            };
        }, SystemClock::elapsedRealtime);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent intent) {
                if (intent == null || !REQUEST.equals(intent.getAction())) return;
                Bundle response = new Bundle();
                response.putInt("protocol", 1);
                response.putInt("outcome", 5);
                scope.listener(() -> {
                    PowerHoldController.Response result = controller.request(intent.getStringExtra("operation"),
                            intent.getLongExtra("deadline", 0), intent.getBooleanExtra("engineExtender", false));
                    response.putInt("outcome", result.outcome);
                    if (result.status != null) response.putInt("status", result.status);
                }).run();
                setResultExtras(response);
            }
        };
        try {
            IntentFilter filter = new IntentFilter(REQUEST);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, PERMISSION, null, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(receiver, filter, PERMISSION, null);
        } catch (RuntimeException unavailable) {
            log(unavailable);
            return;
        }
        CloseOperation receiverClose = new CloseOperation();
        AutoCloseable registration = () -> receiverClose.run(() -> context.unregisterReceiver(receiver));
        scope.own(registration);
        try {
            scope.own(hooks.install("drive-reset-power-hold", notification, scope.callback(invocation -> {
                Object result = invocation.proceed();
                try { publish(context, invocation.receiver()); }
                catch (Exception unavailable) { log(unavailable); }
                return result;
            })));
        } catch (HookRegistry.RejectedException unsupported) {
            registration.close();
            log(unsupported);
            return;
        }
        scope.schedule(0, () -> {
            try { publish(context, ready(managerClass)); }
            catch (Exception unavailable) { log(unavailable); }
        });
    }

    private static Object ready(Class<?> managerClass) throws Exception {
        Object manager = OemAccess.call(managerClass, "getInstance");
        if (manager == null || OemAccess.read(manager, "mContext") == null || OemAccess.read(manager, "mCanBusManager") == null) {
            throw new IllegalStateException("Power Hold manager is not initialized");
        }
        return manager;
    }

    private static void publish(Context context, Object manager) throws Exception {
        Intent intent = new Intent(STATUS).setPackage("ru.big.town.anative");
        intent.putExtra("status", (Integer) OemAccess.call(manager, "getPowerHoldModeStatus"));
        context.sendBroadcast(intent, PERMISSION);
    }

    private static void log(Exception failure) {
        Log.e("vt_power_hold", "OEM Power Hold bridge unavailable", failure);
    }
}
