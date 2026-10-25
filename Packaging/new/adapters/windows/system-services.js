"use strict";

var VoyahWindowAdapters = (function () {
    function selectClassLoader() {
        var serviceClass = "com.android.server.wm.ActivityTaskManagerService";
        try {
            Java.use(serviceClass);
            return;
        } catch (_) {}

        var loaders = Java.enumerateClassLoadersSync();
        for (var index = 0; index < loaders.length; index++) {
            try {
                Java.ClassFactory.get(loaders[index]).use(serviceClass);
                Java.classFactory.loader = loaders[index];
                return;
            } catch (_) {}
        }
        throw new Error("system services class loader is not ready");
    }

    return { selectClassLoader: selectClassLoader };
})();
