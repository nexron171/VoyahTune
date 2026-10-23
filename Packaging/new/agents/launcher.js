Java.perform(function () {
    "use strict";
    try {
        var adapter = VoyahAdapters.launcher();
        var system = Java.use("java.lang.System");
        var marker = "voyahtune.new.launcher.v1";
        if (String(system.getProperty(marker, "")) !== "installed") {
            if (!VoyahLauncherAdapters[adapter.id](adapter)) throw new Error("adapter installation failed");
            system.setProperty(marker, "installed");
        }
        console.log("[launcher] hook ready v1 adapter=" + adapter.id);
    } catch (error) {
        console.log("[launcher] hook failed v1: " + error);
    }
});
