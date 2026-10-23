"use strict";

// registerClass writes DEX files as the target UID, including under SELinux enforcing.
if (typeof Java !== "undefined") {
    Java.perform(function () {
        var application = Java.use("android.app.ActivityThread").currentApplication();
        if (application !== null && String(application.getPackageName()) !== "android") {
            Java.classFactory.cacheDir = String(application.getCacheDir().getAbsolutePath());
            console.log("[bootstrap] dex cache=" + Java.classFactory.cacheDir);
        }
    });
}

// Keep Frida's RPC init pending until asynchronous Java hooks publish their markers.
var VoyahBootstrap = (function () {
    var required = VOYAH_READY_MARKERS.slice();
    var observed = {};
    var resolveReady;
    var ready = new Promise(function (resolve) { resolveReady = resolve; });
    var originalLog = console.log;
    console.log = function () {
        var message = Array.prototype.map.call(arguments, String).join(" ");
        required.forEach(function (marker) {
            if (message.indexOf(marker) >= 0) observed[marker] = true;
        });
        originalLog.apply(console, arguments);
        if (required.every(function (marker) { return observed[marker]; })) resolveReady();
    };
    return {
        attach: function () {
            if (typeof rpc === "undefined") return;
            var previous = rpc.exports.init;
            rpc.exports.init = function () {
                var initialization = previous ? previous.apply(this, arguments) : Promise.resolve();
                return Promise.all([initialization, ready]);
            };
        }
    };
})();
