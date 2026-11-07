package ru.voyahtune.contracts;

import ru.voyahtune.agent.modules.PassengerDockModule;
import ru.voyahtune.agent.policy.LauncherCommand;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PassengerDockModuleTest {
    public static void main(String[] arguments) throws Throwable {
        preservesCustomizedGeometryBeforeNextFrame();
        restoresHiddenButton();
        preservesVisibleButton();
        createsMissingButton();
        toleratesMissingCapabilities();
        followsLateAndRecreatedNavigation();
        restoresOnlyOwnedState();
        rollsBackFailedInstallation();
        toleratesRejectedLifecycleHook();
        preservesStateWhenLaunchFails();
        System.out.println("PassengerDockModuleTest: passed");
    }

    private static void preservesCustomizedGeometryBeforeNextFrame() throws Throwable {
        try (Harness harness = new Harness(true, true)) {
            Navigation navigation = harness.navigation;
            View slot = new View(Resources.FIRST_SLOT, navigation.context);
            slot.parameters.topMargin = 32;
            navigation.mScreenUpRadioGroup.addView(slot);
            navigation.mScreenUpAllAppView.visibility = 0;
            navigation.mScreenUpAllAppView.parameters.topMargin = 32;
            navigation.mScreenUpAirView.parameters.topMargin = 32;
            harness.start();
            Method refresh = Navigation.class.getDeclaredMethod("initAllAppShow");
            harness.backend.invoke(refresh, navigation);
            require(
                    navigation.mScreenUpAllAppView.visibility == 0
                            && navigation.mScreenUpAllAppView.parameters.topMargin == 32
                            && navigation.mScreenUpAirView.parameters.topMargin == 32,
                    "OEM refresh changed customized positions before the next frame");
            harness.flush(0);
            require(
                    navigation.mScreenUpAllAppView.parameters.topMargin == 32,
                    "Deferred recovery moved customized buttons");
        }
    }

    private static void restoresHiddenButton() throws Throwable {
        try (Harness harness = new Harness(true, true)) {
            Navigation navigation = harness.navigation;
            View button = navigation.mScreenUpAllAppView;
            harness.start();
            require(button.visibility == 0, "Hidden OEM button was not restored");
            require(
                    navigation.mScreenUpRadioGroup.children.size() == 4,
                    "Restoring an OEM button created a duplicate");
            button.performClick();
            require(
                    harness.commands.size() == 1
                            && harness.commands.get(0).kind
                                    == LauncherCommand.Kind.PASSENGER_ALL_APPS
                            && harness.commands.get(0).display == 1
                            && button.checked,
                    "Passenger click did not launch its catalog on display 1");

            navigation.mScreenUpRadioGroup.layoutRequested = true;
            navigation.mScreenUpRadioGroup.top = 5;
            navigation.mScreenUpAirView.top = 209;
            harness.invoke("initAllAppShow");
            require(button.visibility == 0, "OEM visibility refresh hid the button again");
            navigation.mScreenUpRadioGroup.layout();
            require(
                    navigation.mScreenUpTemperatureContentView.parameters.topMargin == 214,
                    "Temperature overlay did not follow the completed layout");
            navigation.mScreenUpAirView.top = 160;
            navigation.mScreenUpAirView.layout();
            require(
                    navigation.mScreenUpTemperatureContentView.parameters.topMargin == 165,
                    "Temperature overlay ignored a later child layout");

            harness.invoke("updateTheme");
            require(button.background.equals("new-theme"), "OEM button lost its theme update");
            require(
                    navigation.mScreenUpRadioGroup.children.size() == 4,
                    "Theme refresh duplicated the passenger button");
            harness.passengerRuntime.close();
            require(
                    button.visibility == 8
                            && button.mListenerInfo.mOnClickListener == navigation.stockClick,
                    "Closing the session did not restore the OEM button");
            require(!button.checked, "Closing the session left owned button selection behind");
            require(
                    navigation.mScreenUpTemperatureContentView.parameters.topMargin == 315,
                    "Closing the session left the temperature displaced");
            require(
                    navigation.mScreenUpRadioGroup.layoutListeners.isEmpty()
                            && navigation.mScreenUpAirView.layoutListeners.isEmpty()
                            && button.anchors.isEmpty()
                            && harness.backend.installed.isEmpty(),
                    "Closing the session leaked callbacks or ownership");
            harness.flush(20000);
            require(button.visibility == 8, "A stale boot retry revived a closed passenger module");
        }
    }

    private static void preservesVisibleButton() throws Throwable {
        try (Harness harness = new Harness(true, true)) {
            View button = harness.navigation.mScreenUpAllAppView;
            button.visibility = 0;
            harness.start();
            button.performClick();
            require(
                    harness.navigation.stockClicks == 1 && harness.commands.isEmpty(),
                    "Working OEM click was replaced");
            require(
                    harness.navigation.mScreenUpTemperatureContentView.parameters.topMargin == 315,
                    "Working OEM geometry was modified");
            harness.invoke("initAllAppShow");
            button.performClick();
            require(
                    button.visibility == 0 && harness.commands.size() == 1,
                    "A previously visible button was not recovered after OEM hiding");
        }
    }

    private static void createsMissingButton() throws Throwable {
        try (Harness harness = new Harness(false, true)) {
            harness.start();
            Navigation navigation = harness.navigation;
            View button = navigation.mScreenUpAllAppView;
            require(
                    button != null
                            && button.id == Resources.ALL_APPS
                            && navigation.mScreenUpRadioGroup.children.size() == 4,
                    "Missing button was not created with its OEM resource ID");
            require(
                    button.parameters.topMargin == 111,
                    "Passenger spacing did not use the existing pixel geometry");
            require(
                    button.background.equals("current-theme"),
                    "Created button did not use the current OEM skin");
            button.performClick();
            require(harness.commands.size() == 1, "Created button did not launch the catalog");
            harness.invoke("updateTheme");
            require(
                    button.background.equals("new-theme"),
                    "Created button was not bound to OEM theme state");
            harness.invoke("initScreenUpViews");
            require(
                    navigation.mScreenUpRadioGroup.children.size() == 4,
                    "Reinitializing the same group created a duplicate");
            harness.passengerRuntime.close();
            require(
                    navigation.mScreenUpAllAppView == null
                            && button.parent == null
                            && navigation.mScreenUpRadioGroup.children.size() == 3,
                    "Created button or OEM field survived session cleanup");
        }
    }

    private static void toleratesMissingCapabilities() throws Throwable {
        for (int missing = 0; missing < 5; missing++) {
            try (Harness harness = new Harness(false, missing != 0)) {
                harness.classAvailable = missing != 1;
                harness.navigation.context.resources.templateAvailable = missing != 2;
                harness.activityAvailable = missing != 3;
                harness.navigation.context.resources.buttonIdAvailable = missing != 4;
                harness.start();
                require(
                        harness.passengerRuntime.snapshot().phase == ModuleRuntime.Phase.READY,
                        "Optional passenger capability disabled the launcher session");
                require(
                        harness.navigation.mScreenUpAirView.parent.children.size() == 3,
                        "Missing capability produced an unusable button");
            }
        }
    }

    private static void followsLateAndRecreatedNavigation() throws Throwable {
        try (Harness harness = new Harness(true, true)) {
            Navigation first = harness.navigation;
            harness.modelAvailable = false;
            harness.start();
            require(
                    first.mScreenUpAllAppView.visibility == 8,
                    "Absent model was unexpectedly accessed");
            harness.modelAvailable = true;
            harness.flush(800);
            require(first.mScreenUpAllAppView.visibility == 0, "Late model was not reconciled");
            harness.navigation = new Navigation(true, true);
            harness.invoke("initScreenUpViews");
            require(
                    harness.navigation.mScreenUpAllAppView.visibility == 0,
                    "A recreated passenger navbar was not reconciled");
            harness.passengerRuntime.close();
            require(
                    first.mScreenUpAllAppView.visibility == 8
                            && harness.navigation.mScreenUpAllAppView.visibility == 8,
                    "Cleanup did not restore both live navigation instances");
        }
    }

    private static void restoresOnlyOwnedState() throws Throwable {
        try (Harness harness = new Harness(true, true)) {
            harness.start();
            View button = harness.navigation.mScreenUpAllAppView;
            ClickListener replacement = view -> harness.navigation.stockClicks += 2;
            button.setOnClickListener(replacement);
            harness.navigation.mScreenUpTemperatureContentView.parameters.topMargin = 123;
            harness.passengerRuntime.close();
            require(
                    button.mListenerInfo.mOnClickListener == replacement
                            && harness.navigation
                                            .mScreenUpTemperatureContentView
                                            .parameters
                                            .topMargin
                                    == 123,
                    "Cleanup overwrote a newer OEM listener or layout");
        }
    }

    private static void rollsBackFailedInstallation() throws Throwable {
        try (Harness harness = new Harness(false, true)) {
            harness.navigation.mScreenUpAirView.failObserve = true;
            harness.start();
            require(
                    harness.passengerRuntime.snapshot().phase == ModuleRuntime.Phase.READY
                            && harness.navigation.mScreenUpAllAppView == null
                            && harness.navigation.mScreenUpRadioGroup.children.size() == 3
                            && harness.navigation.mScreenUpRadioGroup.layoutListeners.isEmpty(),
                    "Partial passenger setup failed to roll back");
        }
    }

    private static void toleratesRejectedLifecycleHook() throws Throwable {
        try (Harness harness = new Harness(true, true)) {
            harness.backend.accept = method -> !method.getName().equals("initAllAppShow");
            harness.start();
            require(
                    harness.navigation.mScreenUpAllAppView.visibility == 0
                            && harness.passengerRuntime.snapshot().phase
                                    == ModuleRuntime.Phase.READY,
                    "A rejected optional hook disabled passenger initialization");
            harness.navigation.initAllAppShow();
            harness.invoke("updateTheme");
            require(
                    harness.navigation.mScreenUpAllAppView.visibility == 0,
                    "An available lifecycle hook did not recover the button");
        }
    }

    private static void preservesStateWhenLaunchFails() throws Throwable {
        try (Harness harness = new Harness(true, true)) {
            harness.launchSucceeded = false;
            harness.start();
            harness.navigation.mScreenUpAllAppView.performClick();
            require(
                    !harness.navigation.mScreenUpAllAppView.checked
                            && harness.navigation.stockClicks == 0,
                    "Failed launch selected the button or replayed the OEM action");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Harness extends LauncherDockContractDriver.Harness {
        Navigation navigation;
        ModuleRuntime passengerRuntime;
        boolean classAvailable = true;
        boolean activityAvailable = true;
        boolean modelAvailable = true;
        boolean launchSucceeded = true;

        Harness(boolean existing, boolean groupAvailable) {
            super(false, false);
            navigation = new Navigation(existing, groupAvailable);
        }

        void start() {
            MethodAccess access =
                    new MethodAccess(
                            name -> {
                                switch (name) {
                                    case "com.qinggan.launcher.navigation.NavigationBarSecond":
                                        if (!classAvailable) throw new ClassNotFoundException(name);
                                        return Navigation.class;
                                    case "com.qinggan.secondlauncher.activity.AllAppActivity":
                                        if (!activityAvailable)
                                            throw new ClassNotFoundException(name);
                                        return Navigation.class;
                                    case "android.view.View$OnClickListener":
                                        return ClickListener.class;
                                    case "android.view.View$OnLayoutChangeListener":
                                        return LayoutListener.class;
                                    case "android.view.LayoutInflater":
                                        return Inflater.class;
                                    case "com.qinggan.theme.SkinResourceManager":
                                        return SkinManager.class;
                                    default:
                                        return Class.forName(name);
                                }
                            });
            PassengerDockModule module = new PassengerDockModule(access, hooks, this);
            passengerRuntime = new ModuleRuntime("code", scheduler, id -> module);
            passengerRuntime.apply("code", 1, List.of("passenger"), Map.of());
            flush(0);
        }

        void invoke(String name) throws Throwable {
            Method method = Navigation.class.getDeclaredMethod(name);
            backend.invoke(method, navigation);
            flush(0);
        }

        @Override
        public Object model() {
            return modelAvailable ? new PassengerModel(navigation) : null;
        }

        @Override
        public boolean available(String target) {
            return activityAvailable;
        }

        @Override
        public boolean execute(LauncherCommand command) {
            return launchSucceeded && super.execute(command);
        }

        @Override
        public AutoCloseable retain(Object raw, Object state) {
            View view = (View) raw;
            view.anchors.add(state);
            return () -> view.anchors.remove(state);
        }

        @Override
        public void close() throws Exception {
            if (passengerRuntime != null) passengerRuntime.close();
            super.close();
        }
    }

    private static final class PassengerModel {
        final Object mSecondScreenNavigationBar;

        PassengerModel(Navigation navigation) {
            mSecondScreenNavigationBar = new Controller(navigation);
        }
    }

    private static final class Controller {
        final Object mNavigationBar;

        Controller(Navigation navigation) {
            mNavigationBar = navigation;
        }
    }

    public interface ClickListener {
        void onClick(View view);
    }

    public interface LayoutListener {
        void onLayoutChange(
                View view,
                int left,
                int top,
                int right,
                int bottom,
                int oldLeft,
                int oldTop,
                int oldRight,
                int oldBottom);
    }

    static final class Parameters {
        int topMargin;

        Parameters(int topMargin) {
            this.topMargin = topMargin;
        }
    }

    static final class ListenerInfo {
        ClickListener mOnClickListener;
    }

    public static final class View {
        final int id;
        final Context context;
        final List<View> children = new ArrayList<>();
        final List<LayoutListener> layoutListeners = new ArrayList<>();
        final List<Object> anchors = new ArrayList<>();
        final ListenerInfo mListenerInfo = new ListenerInfo();
        View parent;
        Parameters parameters = new Parameters(111);
        int visibility;
        int top;
        boolean layoutRequested;
        boolean checked;
        boolean failObserve;
        String background = "original-theme";

        View(int id, Context context) {
            this.id = id;
            this.context = context;
        }

        int getId() {
            return id;
        }

        Context getContext() {
            return context;
        }

        View getParent() {
            return parent;
        }

        int getVisibility() {
            return visibility;
        }

        void setVisibility(int value) {
            visibility = value;
        }

        int getTop() {
            return top;
        }

        int getHeight() {
            return 90;
        }

        boolean isLayoutRequested() {
            return layoutRequested;
        }

        Parameters getLayoutParams() {
            return parameters;
        }

        void setLayoutParams(Parameters value) {
            parameters = value;
        }

        int getChildCount() {
            return children.size();
        }

        View getChildAt(int index) {
            return children.get(index);
        }

        void addView(View view) {
            children.add(view);
            view.parent = this;
        }

        void removeView(View view) {
            children.remove(view);
            view.parent = null;
        }

        void setOnClickListener(ClickListener listener) {
            mListenerInfo.mOnClickListener = listener;
        }

        void setChecked(boolean value) {
            checked = value;
        }

        boolean isChecked() {
            return checked;
        }

        void setBackground(String value) {
            background = value;
        }

        void performClick() {
            mListenerInfo.mOnClickListener.onClick(this);
        }

        void addOnLayoutChangeListener(LayoutListener listener) {
            if (failObserve) throw new IllegalStateException("Layout observation unavailable");
            layoutListeners.add(listener);
        }

        void removeOnLayoutChangeListener(LayoutListener listener) {
            layoutListeners.remove(listener);
        }

        void layout() {
            layoutRequested = false;
            for (LayoutListener listener : List.copyOf(layoutListeners))
                listener.onLayoutChange(this, 0, top, 116, top + 90, 0, 0, 116, 90);
        }

        View findViewById(int target) {
            if (id == target) return this;
            for (View child : children) {
                View found = child.findViewById(target);
                if (found != null) return found;
            }
            return null;
        }
    }

    static final class Resources {
        static final int ALL_APPS = 0x7f010001;
        static final int FIRST_SLOT = 0x7f010002;
        boolean templateAvailable = true;
        boolean buttonIdAvailable = true;
        String theme = "current-theme";

        int getIdentifier(String name, String type, String packageName) {
            if (name.equals("screen_up_icon_1")) return FIRST_SLOT;
            if (name.equals("screen_up_all_app_icon") && buttonIdAvailable) return ALL_APPS;
            if (name.equals("navigation_bar_main_up") && templateAvailable) return 1;
            if (name.equals("navigation_bar_icon_all_app_selector")) return 2;
            return 0;
        }
    }

    static final class Context {
        final Resources resources = new Resources();

        Resources getResources() {
            return resources;
        }

        String getPackageName() {
            return "com.qinggan.app.launcher";
        }
    }

    static final class Inflater {
        final Context context;

        Inflater(Context context) {
            this.context = context;
        }

        static Inflater from(Context context) {
            return new Inflater(context);
        }

        View inflate(int resource, View parent, boolean attach) {
            View root = new View(0, context);
            root.addView(new View(Resources.ALL_APPS, context));
            return root;
        }
    }

    static final class SkinManager {
        final Context context;

        SkinManager(Context context) {
            this.context = context;
        }

        static SkinManager getInstance(Context context) {
            return new SkinManager(context);
        }

        String getDrawable(int resource) {
            return context.resources.theme;
        }
    }

    static final class Navigation {
        final Context context = new Context();
        final int mScreenId = 1;
        final View mScreenUpRadioGroup;
        final View mScreenUpAirView = new View(2, context);
        final View mScreenUpSeatView = new View(3, context);
        final View mScreenUpTemperatureContentView = new View(4, context);
        int stockClicks;
        final ClickListener stockClick = view -> stockClicks++;
        View mScreenUpAllAppView;

        Navigation(boolean existing, boolean groupAvailable) {
            View root = new View(0, context);
            View group = new View(5, context);
            mScreenUpRadioGroup = groupAvailable ? group : null;
            root.addView(group);
            root.addView(mScreenUpTemperatureContentView);
            group.addView(new View(1, context));
            group.addView(mScreenUpAirView);
            group.addView(mScreenUpSeatView);
            mScreenUpTemperatureContentView.parameters.topMargin = 315;
            if (existing) {
                mScreenUpAllAppView = new View(Resources.ALL_APPS, context);
                mScreenUpAllAppView.visibility = 8;
                mScreenUpAllAppView.setOnClickListener(stockClick);
                group.addView(mScreenUpAllAppView);
            }
        }

        void initScreenUpViews() {}

        void initAllAppShow() {
            if (mScreenUpAllAppView != null) {
                mScreenUpAllAppView.visibility = 8;
                mScreenUpAllAppView.parameters.topMargin = 111;
                mScreenUpAirView.parameters = mScreenUpAllAppView.parameters;
            }
            mScreenUpTemperatureContentView.parameters.topMargin = 315;
        }

        void updateTheme() {
            context.resources.theme = "new-theme";
            if (mScreenUpAllAppView != null) mScreenUpAllAppView.background = "new-theme";
        }
    }
}
