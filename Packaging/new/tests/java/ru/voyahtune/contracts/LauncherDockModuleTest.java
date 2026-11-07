package ru.voyahtune.contracts;

import java.lang.reflect.Method;
import ru.voyahtune.agent.policy.LauncherCommand;
import ru.voyahtune.agent.policy.LauncherPolicy;
import ru.voyahtune.agent.modules.LauncherPlatform;
import ru.voyahtune.agent.runtime.ModuleRuntime;

public final class LauncherDockModuleTest {
    public static void main(String[] arguments) throws Throwable { verify(); System.out.println("LauncherDockModuleTest: passed"); }

    static void verify() throws Throwable {
        for (boolean shared : new boolean[] {true, false}) {
            passengerSlotReload(shared);
            try (LauncherDockContractDriver.Harness harness = new LauncherDockContractDriver.Harness(shared)) {
                require(harness.runtime.snapshot().phase == ModuleRuntime.Phase.STARTING, "Dock ready before UI initialization");
                harness.settings.put("dockHomeShortVoyahTune", "1");
                harness.settings.put("dock1", "app.music");
                harness.flush(350);
                require(harness.runtime.snapshot().phase == ModuleRuntime.Phase.READY, "Dock UI initialization did not commit");
                Method click = harness.driver.getClass().getDeclaredMethod("onClick", LauncherDockContractDriver.View.class);
                harness.backend.invoke(click, harness.driver, harness.driver.mScreenUpHomeView);
                require(harness.commands.get(harness.commands.size() - 1).kind == LauncherCommand.Kind.DASHBOARD && harness.driver.stockClicks == 0, "Home action replayed stock launcher");
                int commands = harness.commands.size();
                harness.backend.invoke(click, harness.passenger, harness.passenger.mScreenUpHomeView);
                require(harness.passenger.stockClicks == 1 && harness.commands.size() == commands && harness.passenger.mScreenUpHomeView.anchors.isEmpty(), "Passenger controls received driver hooks");
                harness.backend.invoke(click, harness.driver, harness.driver.mScreenUpItemView1);
                require(harness.commands.get(harness.commands.size() - 1).kind == LauncherCommand.Kind.FREEFORM, "Dock slot did not use Native bridge");
                harness.settings.put("dock1", "ru.big.town.restoremode");
                harness.backend.invoke(click, harness.driver, harness.driver.mScreenUpItemView1);
                require(harness.commands.get(harness.commands.size() - 1).kind == (shared ? LauncherCommand.Kind.FREEFORM : LauncherCommand.Kind.DASHBOARD), "Dashboard slot lost OEM-family routing");
                harness.settings.put("dock1", "app.music");
                harness.tops[0] = new LauncherPolicy.Top(LauncherPolicy.LAUNCHER, "MainActivity", true);
                harness.settings.put("dockLaunchGuard0", (harness.now() + 5000) + "|app.music");
                Method dismiss = harness.driver.getClass().getDeclaredMethod("dismiss");
                harness.backend.invoke(dismiss, harness.driver);
                require(harness.driver.mRootView.parent != null, "Live launch guard required a configuration reload");
                harness.settings.remove("dockLaunchGuard0");
                if (!shared) {
                    RuntimeException failure = new IllegalStateException("OEM click failure");
                    harness.driver.mScreenUpHomeView.clickFailure = failure;
                    LauncherPlatform.LongGesture gesture = (LauncherPlatform.LongGesture) harness.driver.mScreenUpHomeView.properties.get("longClick");
                    try { gesture.click(harness.driver.mScreenUpHomeView); throw new AssertionError("OEM exception was swallowed"); }
                    catch (RuntimeException observed) { require(observed == failure, "OEM exception identity changed"); }
                    harness.driver.mScreenUpHomeView.clickFailure = null;
                    int before = harness.commands.size();
                    harness.backend.invoke(click, harness.driver, harness.driver.mScreenUpHomeView);
                    require(harness.commands.size() == before + 1, "Stock gesture left navigation bypass enabled");
                }
                Method selected = harness.driver.getClass().getDeclaredMethod("updateSelectedApp", String.class, String.class);
                harness.backend.invoke(selected, harness.driver, "app.music", "Activity");
                require(harness.driver.selected.equals("com.qinggan.bluetoothphone"), "Custom slot selection did not map to OEM radio button");
                harness.backend.invoke(selected, harness.driver, LauncherPolicy.NATIVE, "SplitHostActivity");
                require(Boolean.TRUE.equals(harness.driver.mScreenUpItemView1.properties.get("checked")), "SplitHost lost last dock selection");
                harness.settings.put("fullscreen_apps", LauncherPolicy.NATIVE);
                harness.settings.put("carplay_hide_system_bars", "1");
                harness.tops[0] = new LauncherPolicy.Top(LauncherPolicy.NATIVE, ".CarPlayFullscreenActivity", true);
                harness.events.reload();
                harness.flush(350);
                require(harness.driver.mRootView.parent == null && harness.hidden[0] && !harness.statusVisible[0], "Fullscreen CarPlay chrome was not hidden");
                harness.revealUntil[0] = harness.now() + 5000;
                harness.events.chrome();
                harness.flush(0);
                require(harness.driver.mRootView.parent != null && harness.statusVisible[0] && !harness.reported[0], "Temporary reveal did not restore dock and status bar");
                harness.flush(5000);
                require(harness.driver.mRootView.parent == null && harness.hidden[0], "Reveal deadline did not reapply hide policy");
                if (!shared) {
                    harness.tops[0] = new LauncherPolicy.Top("", "", false);
                    harness.events.chrome();
                    harness.flush(2000);
                    require(harness.driver.mRootView.parent != null && !harness.hidden[0], "Lost live foreground retained stale CarPlay hide");
                }
                harness.runtime.close();
                require(harness.backend.installed.isEmpty() && harness.closedWatchers == 1 && harness.statusVisible[0], "Dock cleanup left hooks/observer/status claim");
                for (LauncherDockContractDriver.View view : harness.driver.views()) require(view.anchors.isEmpty(), "Dock cleanup left listener anchor");
                harness.events.reload();
                harness.flush(10000);
                require(harness.runtime.snapshot().phase == ModuleRuntime.Phase.CLOSED, "Stale dock task revived closed module");
            }
        }
    }

    private static void passengerSlotReload(boolean shared) throws Exception {
        java.util.List<String> assignments = new java.util.ArrayList<>();
        try (LauncherDockContractDriver.Harness harness = new LauncherDockContractDriver.Harness(shared) {
            @Override
            public void passengerDock(Object bar, Object source, String first, String second) {
                require(bar == passenger && source == driver, "Passenger settings applied to the wrong bar");
                assignments.clear();
                assignments.add(first);
                assignments.add(second);
            }
        }) {
            harness.settings.put("dock1", "driver.phone");
            harness.settings.put("passengerDockSlot2", "passenger.music");
            harness.flush(350);
            require(assignments.equals(java.util.List.of("none", "passenger.music")),
                    "Driver assignment leaked into the passenger dock");
            harness.settings.remove("passengerDockSlot2");
            harness.settings.put("passengerDockSlot1", "passenger.maps");
            harness.events.reload();
            harness.flush(0);
            harness.flush(350);
            require(assignments.equals(java.util.List.of("passenger.maps", "none")),
                    "Live reload lost the passenger reassignment or reset");
        }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
