package ru.voyahtune.contracts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.policy.LauncherNavigation;
import ru.voyahtune.agent.policy.LauncherPresentation;

public final class LauncherPresentationTest {
    public static void main(String[] arguments) throws Throwable {
        verify();
        System.out.println("LauncherPresentationTest: passed");
    }

    static void verify() throws Throwable {
        LauncherPresentationContractDriver.Access access = new LauncherPresentationContractDriver.Access();
        LauncherPresentation presentation = new LauncherPresentation(access);
        LauncherPresentationContractDriver.View view = new LauncherPresentationContractDriver.View();
        Object stock = new Object();
        Object listener = new Object();
        view.properties.put("longClickable", false);
        view.properties.put("longClick", stock);
        presentation.listener(0, view, listener);
        presentation.listener(0, view, listener);
        require(view.anchors.size() == 1, "Repeated repaint duplicated view ownership");
        presentation.icons(0, List.of(view), List.of("app.music"), List.of("navigation"));
        presentation.icons(0, List.of(view), List.of("none"), List.of("navigation"));
        require(view.properties.get("background").equals("OEM") && view.properties.get("longClick") == listener, "Removing assignment dropped gesture or retained custom icon");
        view.properties.put("background", "new theme");
        presentation.icons(0, List.of(view), List.of("app.music"), List.of("music"));
        presentation.close();
        require(view.properties.get("background").equals("new theme") && view.properties.get("longClick") == stock
                && Boolean.FALSE.equals(view.properties.get("longClickable")) && view.anchors.isEmpty(), "Presentation cleanup lost latest theme or OEM listener");

        presentation = new LauncherPresentation(access);
        Map<String, Object> views = new HashMap<>();
        for (String name : List.of("up", "down", "group", "home", "slot1", "slot2", "allApps", "extra1", "extra2", "temperature")) {
            LauncherPresentationContractDriver.View item = new LauncherPresentationContractDriver.View();
            item.properties.put("visibility", 0);
            item.height = name.equals("group") ? -1 : 720;
            views.put(name, item);
        }
        presentation.lift(0, views, 1, false, false, true);
        require(access.read(views.get("up"), "height").equals(560) && access.read(views.get("group"), "height").equals(-2)
                && access.read(views.get("slot1"), "visibility").equals(8) && access.read(views.get("slot2"), "visibility").equals(0)
                && access.read(views.get("temperature"), "visibility").equals(8), "Compact driver layout changed assignment semantics");
        presentation.lift(1, views, 2, false, true, true);
        require(access.read(views.get("up"), "height").equals(560), "Passenger changed driver layout");
        presentation.lift(0, views, 1, true, false, true);
        require(access.read(views.get("up"), "visibility").equals(8) && access.read(views.get("down"), "visibility").equals(8), "Fullscreen repaint exposed detached dock children");
        presentation.close();
        require(access.read(views.get("up"), "height").equals(720) && access.read(views.get("group"), "height").equals(-1)
                && access.read(views.get("temperature"), "visibility").equals(0), "Lift cleanup retained custom geometry");
        LauncherNavigation navigation = new LauncherNavigation(false);
        Throwable original = new IllegalStateException("OEM click failed");
        Throwable observed = null;
        try { navigation.stock(() -> { require(navigation.bypassing(), "Long click did not enter stock bypass"); throw original; }); }
        catch (Throwable caught) { observed = caught; }
        require(observed == original && !navigation.bypassing(), "Stock bypass changed exception or leaked after failure");
        LauncherNavigation shared = new LauncherNavigation(true);
        require(shared.action(0, LauncherNavigation.Button.HOME, false, false) == LauncherNavigation.Action.DASHBOARD
                && shared.action(0, LauncherNavigation.Button.HOME, true, true) == LauncherNavigation.Action.OEM_HOME
                && shared.action(0, LauncherNavigation.Button.ALL_APPS, true, true) == LauncherNavigation.Action.TASK_MANAGER,
                "Shared navigation gestures lost OEM differences");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
