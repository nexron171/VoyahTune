// Frida-хук в system_server: разрешает нашему приложению (ru.big.town.anative) создавать trusted
// VirtualDisplay и инжектить касания — единственный путь VD-хостинга на release-keys голове
// (ADD_TRUSTED_DISPLAY/INJECT_EVENTS — чистый signature, whitelist их не выдаёт).
//
// ВАЖНО: хукаем ДВА РЕДКИХ метода, НЕ общий checkComponentPermission (тот на горячем пути —
// тысячи вызовов/сек — и роняет watchdog system_server). Плюс инжектить нужно через -e (eternalize):
// приаттаченный frida-inject держит ptrace на system_server и тоже его дестабилизирует.
//   1) InputManagerService.checkInjectEventsPermission — только при инъекции ввода;
//   2) DisplayManagerService$BinderService.checkCallingPermission — при создании VirtualDisplay (trusted);
//   3) ActivityStackSupervisor.isCallerAllowedToLaunchOnDisplay — при запуске активити на дисплей.
// uid резолвится динамически по имени пакета (устойчиво к переустановке).
Java.perform(function () {
    var OUR_PKG = "ru.big.town.anative";
    var Log = Java.use("android.util.Log");
    var Binder = Java.use("android.os.Binder");
    var ourUid = -1;
    try {
        ourUid = Java.use("android.app.ActivityThread").currentActivityThread()
                 .getSystemContext().getPackageManager().getPackageUid(OUR_PKG, 0);
    } catch (e) {
        ourUid = 10060;
    }
    var installed = [];

    // 1) INJECT_EVENTS — редко (только при инъекции ввода из SplitHostActivity)
    try {
        var IMS = Java.use("com.android.server.input.InputManagerService");
        IMS.checkInjectEventsPermission.implementation = function (pid, uid) {
            if (uid === ourUid) return true;
            return this.checkInjectEventsPermission(pid, uid);
        };
        installed.push("IMS.checkInjectEventsPermission");
    } catch (e) {
        Log.e("VDBYPASS", "IMS hook fail: " + e);
    }

    // 2) ADD_TRUSTED_DISPLAY / INTERNAL_SYSTEM_WINDOW — редко (только при createVirtualDisplay и т.п.)
    try {
        var BS = Java.use("com.android.server.display.DisplayManagerService$BinderService");
        BS.checkCallingPermission.overload('java.lang.String', 'java.lang.String').implementation = function (permission, func) {
            if ((permission === "android.permission.ADD_TRUSTED_DISPLAY"
                 || permission === "android.permission.INTERNAL_SYSTEM_WINDOW")
                && Binder.getCallingUid() === ourUid) {
                return true;
            }
            return this.checkCallingPermission(permission, func);
        };
        installed.push("BinderService.checkCallingPermission");
    } catch (e) {
        Log.e("VDBYPASS", "DMS hook fail: " + e);
    }

    // 3) запуск активити на нашем VirtualDisplay — редко (только при старте активити на дисплей)
    try {
        var ASS = Java.use("com.android.server.wm.ActivityStackSupervisor");
        ASS.isCallerAllowedToLaunchOnDisplay.implementation = function (pid, uid, displayId, aInfo) {
            if (uid === ourUid) return true;
            return this.isCallerAllowedToLaunchOnDisplay(pid, uid, displayId, aInfo);
        };
        installed.push("ASS.isCallerAllowedToLaunchOnDisplay");
    } catch (e) {
        Log.e("VDBYPASS", "ASS hook fail: " + e);
    }

    // Маркер пишет load.bin (root), а не мы: система (uid system) не может писать в /data/local/tmp (EACCES).
    Log.i("VDBYPASS", "hooks installed [" + installed.join(", ") + "] uid=" + ourUid);
});
