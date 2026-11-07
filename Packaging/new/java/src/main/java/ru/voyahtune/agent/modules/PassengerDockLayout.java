package ru.voyahtune.agent.modules;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;

import ru.voyahtune.agent.policy.LauncherCatalog;
import ru.voyahtune.agent.policy.LauncherCommand;
import ru.voyahtune.agent.policy.LauncherNavigation;
import ru.voyahtune.agent.policy.LauncherPolicy;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.runtime.ViewOwnership;

import java.util.ArrayList;
import java.util.List;

final class PassengerDockLayout implements AutoCloseable {
    private final ViewGroup group;
    private final LauncherPlatform platform;
    private final MethodAccess methods;
    private final ViewOwnership owned;
    private final View[] slots = new View[2];
    private final String[] targets = new String[2];
    private final List<View> inserted = new ArrayList<>();
    private final View air;
    private final View seat;
    private final View home;
    private View menu;
    private NavigationButton homeNavigation;
    private NavigationButton menuNavigation;
    private View vehicle;
    private boolean replacedSeat;
    private final boolean separateBar;

    private PassengerDockLayout(
            Object bar, ViewGroup group, LauncherPlatform platform, MethodAccess methods)
            throws Exception {
        this.group = group;
        this.platform = platform;
        this.methods = methods;
        owned = new ViewOwnership(platform);
        slots[0] = field(bar, "mScreenUpItemView1");
        slots[1] = field(bar, "mScreenUpItemView2");
        separateBar = slots[0] == null && slots[1] == null;
        air = field(bar, separateBar ? "mScreenUpAirView" : "mScreenUpItemView3");
        seat = field(bar, "mScreenUpSeatView");
        home = field(bar, "mScreenUpHomeView");
        menu = group.findViewById(resource("id", "screen_up_all_app_icon"));
        vehicle = field(bar, "mScreenUpItemView4");
    }

    static PassengerDockLayout attach(
            Object bar, Object driver, LauncherPlatform platform, MethodAccess methods)
            throws Exception {
        ViewGroup group = (ViewGroup) field(bar, "mScreenUpRadioGroup");
        if (group == null || field(driver, "mScreenUpItemView4") == null) return null;
        PassengerDockLayout layout = new PassengerDockLayout(bar, group, platform, methods);
        try {
            if (!layout.install()) {
                layout.close();
                return null;
            }
            return layout;
        } catch (Exception failure) {
            layout.close();
            throw failure;
        }
    }

    boolean matches(Object bar) throws Exception {
        return field(bar, "mScreenUpRadioGroup") == group;
    }

    private boolean install() throws Exception {
        View originalVehicle = separateBar ? seat : vehicle;
        if (air == null
                || originalVehicle == null
                || air.getParent() != group
                || originalVehicle.getParent() != group) return false;
        if (separateBar) {
            Context context = group.getContext();
            int layout = resource("layout", "navigation_bar_main_up");
            if (layout == 0) return false;
            View template = LayoutInflater.from(context).inflate(layout, group, false);
            for (int i = 0; i < slots.length; i++) {
                slots[i] = template.findViewById(resource("id", "screen_up_icon_" + (i + 1)));
                if (slots[i] == null) return false;
            }
            vehicle = template.findViewById(resource("id", "screen_up_icon_4"));
            if (vehicle == null) return false;
            for (View slot : slots) insertBefore(slot, air);
            insertBefore(vehicle, seat);
            // Keep the OEM field alive for theme and seat events, outside the visible radio group.
            group.removeView(seat);
            replacedSeat = true;
        } else {
            if (slots[0] == null || slots[1] == null) return false;
            bind(air, () -> openSubtype("AC"));
            owned.write(air, "description", "Климат");
            if (menu != null) {
                owned.write(menu, "description", "Меню приложений");
            }
        }
        if (home != null)
            homeNavigation = new NavigationButton(home, LauncherNavigation.Button.HOME);
        bind(vehicle, () -> openSubtype("Vehicle"));
        owned.write(vehicle, "description", "Машина");
        return true;
    }

    private void insertBefore(View child, View before) {
        ((ViewGroup) child.getParent()).removeView(child);
        group.addView(child, group.indexOfChild(before));
        inserted.add(child);
    }

    void update(Object driver, String first, String second) throws Exception {
        updateNavigation();
        updateSlot(0, first);
        updateSlot(1, second);
        View stockVehicle = field(driver, "mScreenUpItemView4");
        stockIcon(vehicle, stockVehicle);
        if (separateBar) {
            // The driver template supplies the spacing for six rows at this OEM density.
            int margin = ((ViewGroup.MarginLayoutParams) slots[0].getLayoutParams()).topMargin;
            owned.write(air, "topMargin", margin);
            if (menu != null) owned.write(menu, "topMargin", margin);
        } else {
            owned.write(air, "background", platform.icon("com.qinggan.app.vehicle", 0, "default"));
            namedIcon(home, "home_icon_bg");
            namedIcon(menu, "all_app_icon_bg");
        }
    }

    private void updateNavigation() throws Exception {
        View currentMenu = group.findViewById(resource("id", "screen_up_all_app_icon"));
        if (menuNavigation == null || menu != currentMenu) {
            if (menuNavigation != null) menuNavigation.close();
            menu = currentMenu;
            menuNavigation =
                    menu == null
                            ? null
                            : new NavigationButton(menu, LauncherNavigation.Button.ALL_APPS);
        }
        if (homeNavigation != null) homeNavigation.bind();
        if (menuNavigation != null) {
            menuNavigation.bind();
            owned.write(menu, "visibility", View.VISIBLE);
        }
    }

    private final class NavigationButton implements AutoCloseable {
        private final View view;
        private final LauncherNavigation.Button role;
        private final View.OnClickListener click = ignored -> invoke(false);
        private final View.OnLongClickListener longClick =
                ignored -> {
                    invoke(true);
                    return true;
                };
        private View.OnClickListener stockClick;

        NavigationButton(View view, LauncherNavigation.Button role) {
            this.view = view;
            this.role = role;
        }

        void bind() throws Exception {
            View.OnClickListener current = (View.OnClickListener) platform.read(view, "onClick");
            if (current != click) stockClick = current;
            owned.write(view, "onClick", click);
            owned.write(view, "longClickable", true);
            owned.write(view, "longClick", longClick);
        }

        private void invoke(boolean longPress) {
            try {
                LauncherPolicy policy = new LauncherPolicy(false, platform.settings());
                if (policy.dashboardShort(role) != longPress) {
                    launch(LauncherCommand.Kind.DASHBOARD, "");
                } else if (role == LauncherNavigation.Button.ALL_APPS) {
                    launch(LauncherCommand.Kind.PASSENGER_ALL_APPS, "");
                } else if (stockClick != null) {
                    stockClick.onClick(view);
                }
            } catch (Exception failure) {
                platform.error(failure);
            }
        }

        @Override
        public void close() throws Exception {
            // Setting a long-click listener can enable longClickable; restore the flag last.
            owned.restore(view, "longClick");
            owned.restore(view, "longClickable");
            owned.restore(view, "onClick");
        }
    }

    private void updateSlot(int index, String configured) throws Exception {
        View slot = slots[index];
        String target = platform.available(configured) ? configured : "";
        targets[index] = target;
        owned.write(slot, "visibility", target.isEmpty() ? View.GONE : View.VISIBLE);
        if (target.isEmpty()) {
            owned.write(slot, "background", null);
            owned.write(slot, "description", null);
            owned.write(slot, "onClick", null);
            if (slot instanceof RadioButton) owned.write(slot, "checked", false);
            return;
        }
        owned.write(slot, "background", platform.icon(target, index + 1, "default"));
        owned.write(slot, "description", platform.label(target));
        LauncherCommand.Kind kind =
                LauncherCatalog.RESTORE.equals(target)
                        ? LauncherCommand.Kind.DASHBOARD
                        : LauncherCommand.Kind.EMBEDDED;
        bind(slot, () -> launch(kind, target));
    }

    void selected(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        for (int i = 0; i < slots.length; i++) {
            if (packageName.equals(targets[i]) && slots[i] instanceof RadioButton) {
                ((RadioButton) slots[i]).setChecked(true);
                return;
            }
        }
        if ("com.qinggan.app.vehiclesetting".equals(packageName)
                && vehicle instanceof RadioButton) {
            ((RadioButton) vehicle).setChecked(true);
        } else if ("com.qinggan.app.vehicle".equals(packageName) && air instanceof RadioButton) {
            ((RadioButton) air).setChecked(true);
        }
    }

    private void namedIcon(View button, String name) throws Exception {
        int icon = resource("drawable", name);
        if (button != null && icon != 0) owned.write(button, "background", drawable(icon));
    }

    private void stockIcon(View button, View stock) throws Exception {
        Object icon = stock.getTag(resource("id", "screen_up_item_icon"));
        if (icon instanceof Integer && (Integer) icon != 0)
            owned.write(button, "background", drawable((Integer) icon));
    }

    private Drawable drawable(int id) throws Exception {
        Context context = group.getContext();
        try {
            Object manager =
                    OemAccess.call(
                            methods.type("com.qinggan.theme.SkinResourceManager"),
                            "getInstance",
                            context);
            return (Drawable) OemAccess.call(manager, "getDrawable", id);
        } catch (ReflectiveOperationException unavailable) {
            return context.getDrawable(id);
        }
    }

    private int resource(String kind, String name) {
        Context context = group.getContext();
        return context.getResources().getIdentifier(name, kind, context.getPackageName());
    }

    private void bind(View button, Action action) throws Exception {
        owned.write(
                button,
                "onClick",
                (View.OnClickListener)
                        view -> {
                            try {
                                action.run();
                            } catch (Exception failure) {
                                platform.error(failure);
                            }
                        });
    }

    private void launch(LauncherCommand.Kind kind, String target) throws Exception {
        if (!platform.execute(new LauncherCommand(kind, 1, target, 0))) {
            throw new IllegalStateException("Passenger dock launch failed for " + kind);
        }
    }

    private void openSubtype(String subtype) throws Exception {
        OemAccess.call(
                methods.type("com.qinggan.launcher.base.utils.AppLauncher"),
                "startAppBySubType",
                group.getContext(),
                subtype,
                1);
    }

    @Override
    public void close() throws Exception {
        if (replacedSeat && seat.getParent() == null) {
            group.addView(seat, group.indexOfChild(vehicle));
            replacedSeat = false;
        }
        if (homeNavigation != null) homeNavigation.close();
        if (menuNavigation != null) menuNavigation.close();
        for (View child : inserted) group.removeView(child);
        owned.close();
    }

    private static View field(Object owner, String name) throws Exception {
        return (View) PassengerDockModule.field(owner, name);
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }
}
