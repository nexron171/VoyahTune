package ru.voyahtune.agent.modules;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;

import ru.voyahtune.agent.policy.LauncherCatalog;
import ru.voyahtune.agent.policy.LauncherCommand;
import ru.voyahtune.agent.policy.LauncherNavigation;
import ru.voyahtune.agent.policy.LauncherPolicy;
import ru.voyahtune.agent.policy.PassengerDockGeometry;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ViewOwnership;

import java.util.ArrayList;
import java.util.List;

final class PassengerDockLayout implements AutoCloseable {
    private final ViewGroup group;
    private final LauncherPlatform platform;
    private final ViewOwnership owned;
    private final View[] slots = new View[2];
    private final String[] targets = new String[2];
    private final List<View> inserted = new ArrayList<>();
    private final View air;
    private final View home;
    private final View vehicle;
    private final View raisedPanel;
    private final View loweredPanel;
    private final View temperature;
    private final int raisedHeight;
    private final int loweredHeight;
    private final View.OnLayoutChangeListener layoutListener;
    private boolean closed;
    private View menu;
    private NavigationButton homeNavigation;
    private NavigationButton menuNavigation;
    private final boolean separateBar;

    private PassengerDockLayout(
            Object bar, ViewGroup group, LauncherPlatform platform, MethodAccess methods)
            throws Exception {
        this.group = group;
        this.platform = platform;
        owned = new ViewOwnership(new PassengerDockViewAccess(platform));
        slots[0] = field(bar, "mScreenUpItemView1");
        slots[1] = field(bar, "mScreenUpItemView2");
        separateBar = slots[0] == null && slots[1] == null;
        air = field(bar, separateBar ? "mScreenUpAirView" : "mScreenUpItemView3");
        home = field(bar, "mScreenUpHomeView");
        vehicle = field(bar, separateBar ? "mScreenUpSeatView" : "mScreenUpItemView4");
        raisedPanel = field(bar, "mScreenUpView");
        loweredPanel = field(bar, "mScreenDownView");
        temperature = field(bar, "mScreenUpTemperatureContentView");
        raisedHeight = height(raisedPanel);
        loweredHeight = height(loweredPanel);
        layoutListener =
                (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (!closed) {
                        try {
                            updateGeometry();
                        } catch (Exception failure) {
                            platform.error(failure);
                        }
                    }
                };
        menu = group.findViewById(resource("id", "screen_up_all_app_icon"));
    }

    static PassengerDockLayout attach(
            Object bar, Object driver, LauncherPlatform platform, MethodAccess methods)
            throws Exception {
        ViewGroup group = (ViewGroup) field(bar, "mScreenUpRadioGroup");
        if (group == null) return null;
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
        if (!(group instanceof LinearLayout)
                || raisedHeight <= 0
                || loweredHeight <= 0
                || home == null
                || vehicle == null) {
            return false;
        }
        if (air == null || air.getParent() != group) return false;
        if (separateBar) {
            Context context = group.getContext();
            int layout = resource("layout", "navigation_bar_main_up");
            if (layout == 0) return false;
            View template = LayoutInflater.from(context).inflate(layout, group, false);
            for (int i = 0; i < slots.length; i++) {
                slots[i] = template.findViewById(resource("id", "screen_up_icon_" + (i + 1)));
                if (slots[i] == null) return false;
            }
            for (View slot : slots) insertBefore(slot, air);
        } else if (slots[0] == null || slots[1] == null) {
            return false;
        }
        if (home != null)
            homeNavigation = new NavigationButton(home, LauncherNavigation.Button.HOME);
        raisedPanel.addOnLayoutChangeListener(layoutListener);
        group.addOnLayoutChangeListener(layoutListener);
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
        updateGeometry();
    }

    private void updateGeometry() throws Exception {
        View currentMenu = group.findViewById(resource("id", "screen_up_all_app_icon"));
        if (currentMenu != menu) {
            updateNavigation();
        }
        if (menu == null) {
            return;
        }
        View[] buttons = {home, slots[0], slots[1], air, vehicle, menu};
        int[] heights = new int[buttons.length];
        for (int index = 0; index < buttons.length; index++) {
            if (buttons[index].getParent() != group) {
                return;
            }
            heights[index] = height(buttons[index]);
            if (heights[index] <= 0) {
                return;
            }
        }
        boolean lowered = platform.lift() == 1;
        int availableHeight = lowered ? loweredHeight : raisedHeight;
        int[] positions =
                PassengerDockGeometry.positions(
                        raisedHeight, availableHeight, heights, assigned(0), assigned(1), lowered);
        owned.write(raisedPanel, "height", availableHeight);
        owned.write(raisedPanel, "visibility", View.VISIBLE);
        owned.write(loweredPanel, "visibility", View.GONE);
        owned.write(group, "height", ViewGroup.LayoutParams.MATCH_PARENT);
        owned.write(group, "dockGravity", Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        owned.write(
                group,
                "dockPadding",
                List.of(group.getPaddingLeft(), 0, group.getPaddingRight(), 0));
        int previousBottom = 0;
        for (int index = 0; index < buttons.length; index++) {
            boolean visible = positions[index] >= 0;
            owned.write(buttons[index], "visibility", visible ? View.VISIBLE : View.GONE);
            if (visible) {
                owned.write(buttons[index], "topMargin", positions[index] - previousBottom);
                owned.write(buttons[index], "bottomMargin", 0);
                previousBottom = positions[index] + heights[index];
            }
        }
        if (temperature != null && temperature.getParent() == raisedPanel) {
            owned.write(temperature, "topMargin", positions[3]);
        }
    }

    private boolean assigned(int index) {
        return targets[index] != null && !targets[index].isEmpty();
    }

    private static int height(View view) {
        if (view == null || view.getLayoutParams() == null) {
            return 0;
        }
        int configured = view.getLayoutParams().height;
        return configured > 0 ? configured : view.getHeight();
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

    @Override
    public void close() throws Exception {
        closed = true;
        if (raisedPanel != null) {
            raisedPanel.removeOnLayoutChangeListener(layoutListener);
        }
        group.removeOnLayoutChangeListener(layoutListener);
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
