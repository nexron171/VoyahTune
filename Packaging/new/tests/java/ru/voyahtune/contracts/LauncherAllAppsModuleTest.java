package ru.voyahtune.contracts;

import ru.voyahtune.agent.modules.LauncherAllAppsModule;
import ru.voyahtune.agent.modules.LauncherModule;
import ru.voyahtune.agent.policy.LauncherCatalog;
import ru.voyahtune.agent.policy.LauncherCommand;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ResourceGroup;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class LauncherAllAppsModuleTest {
    public static void main(String[] arguments) throws Throwable {
        verify();
        System.out.println("LauncherAllAppsModuleTest: passed");
    }

    static void verify() throws Throwable {
        for (boolean combined : new boolean[] {false, true})
            for (boolean old : new boolean[] {true, false}) {
                try (Harness harness = new Harness(old, combined)) {
                    harness.flush(0);
                    require(
                            harness.apps.snapshot().phase == ModuleRuntime.Phase.READY,
                            "All Apps did not become ready");
                    require(
                            targets(Data.instance.mMainAllApps)
                                    .equals(
                                            List.of(
                                                    "oem.app",
                                                    LauncherCatalog.RESTORE,
                                                    LauncherCatalog.CARPLAY,
                                                    "app.music")),
                            "Driver catalog injection failed");
                    require(
                            targets(Data.instance.mSecondAllApps)
                                    .equals(
                                            List.of(
                                                    "oem.app",
                                                    LauncherCatalog.CARPLAY,
                                                    "app.music",
                                                    LauncherCatalog.PASSENGER)),
                            "Passenger catalog injection failed");
                    Adapter adapter = new Adapter(Data.instance.mMainAllApps);
                    Holder holder = new Holder();
                    Method bind =
                            Adapter.class.getDeclaredMethod(
                                    "onBindViewHolder", Holder.class, int.class);
                    harness.backend.invoke(bind, adapter, holder, 2);
                    require(
                            holder.iconView
                                            .properties
                                            .get("background")
                                            .equals("app:" + LauncherCatalog.CARPLAY)
                                    && holder.iconView.properties.get("drawable") == null
                                    && holder.nameView
                                            .properties
                                            .get("text")
                                            .equals(LauncherCatalog.CARPLAY),
                            "Synthetic cell kept OEM placeholder");
                    harness.backend.invoke(bind, adapter, holder, 0);
                    require(
                            holder.iconView.properties.get("background").equals("OEM background")
                                    && holder.iconView
                                            .properties
                                            .get("drawable")
                                            .equals("OEM image"),
                            "Recycled OEM cell retained custom presentation");
                    Method start =
                            AppLauncher.class.getDeclaredMethod(
                                    "startApp",
                                    LauncherDockContractDriver.Context.class,
                                    Intent.class,
                                    int.class);
                    Intent passenger =
                            new Intent()
                                    .setClassName(
                                            "com.qinggan.app.launcher",
                                            "com.qinggan.secondlauncher.activity.SecondMainActivity");
                    AppLauncher.launched = null;
                    harness.backend.invoke(
                            start, null, new LauncherDockContractDriver.Context(), passenger, 1);
                    require(
                            AppLauncher.launched != null
                                    && (!old
                                            || AppLauncher.launched != passenger
                                                    && AppLauncher.launched.component.activity
                                                            .equals(
                                                                    "com.qinggan.secondlauncher.activity.AllAppActivity"))
                                    && passenger.component.activity.equals(
                                            "com.qinggan.secondlauncher.activity.SecondMainActivity"),
                            "Passenger redirect mutated caller intent or failed to route");
                    harness.settings.put("fullscreen_apps", "app.music");
                    Intent music = new Intent().setClassName("app.music", "app.music.MainActivity");
                    AppLauncher.launched = null;
                    harness.backend.invoke(
                            start, null, new LauncherDockContractDriver.Context(), music, 0);
                    require(
                            AppLauncher.launched == null
                                    && harness.commands.get(harness.commands.size() - 1).kind
                                            == LauncherCommand.Kind.FULLSCREEN,
                            "Fullscreen bridge replayed stock start");
                    harness.backend.invoke(
                            start, null, new LauncherDockContractDriver.Context(), music, 4);
                    require(
                            AppLauncher.launched == music,
                            "Virtual display launch was intercepted");
                    harness.settings.put("fullscreen_apps", "");
                    harness.settings.put("embedded_apps", "app.music");
                    for (int display : new int[] {0, 1}) {
                        AppLauncher.launched = null;
                        harness.backend.invoke(
                                start,
                                null,
                                new LauncherDockContractDriver.Context(),
                                music,
                                display);
                        LauncherCommand command = harness.commands.get(harness.commands.size() - 1);
                        require(
                                AppLauncher.launched == null
                                        && command.kind == LauncherCommand.Kind.EMBEDDED
                                        && command.display == display
                                        && command.target.equals("app.music"),
                                "Embedded application bypassed Native handoff");
                    }
                    harness.backend.invoke(
                            start, null, new LauncherDockContractDriver.Context(), music, 4);
                    require(AppLauncher.launched == music, "Embedded self-launch was intercepted");
                    harness.settings.put("embedded_apps", "app.music.other");
                    AppLauncher.launched = null;
                    harness.backend.invoke(
                            start, null, new LauncherDockContractDriver.Context(), music, 1);
                    require(
                            AppLauncher.launched == music,
                            "Package prefix closed an unrelated window");
                    AllAppsBar bar = new AllAppsBar(adapter);
                    Data.instance.mAllAppDataListeners.add(bar);
                    harness.appsEvents.reload();
                    harness.flush(0);
                    int notifications = adapter.notifications;
                    harness.appsEvents.locale();
                    harness.flush(0);
                    require(
                            adapter.notifications == notifications + 1,
                            "A discovered grid was notified twice");
                    bar.mLayoutManager.mRecyclerView.busy = true;
                    harness.appsEvents.locale();
                    harness.flush(0);
                    require(
                            harness.apps.snapshot().phase != ModuleRuntime.Phase.RESTART_REQUIRED,
                            "Busy OEM grid failed instead of waiting");
                    bar.mLayoutManager.mRecyclerView.busy = false;
                    harness.flush(250);
                    harness.carPlay = false;
                    harness.appsEvents.packages(LauncherCatalog.RESTORE);
                    harness.flush(350);
                    require(
                            !targets(Data.instance.mMainAllApps).contains(LauncherCatalog.CARPLAY),
                            "Package update retained disabled CarPlay entry");
                    harness.apps.close();
                    require(
                            harness.backend.installed.isEmpty(),
                            "Composite cleanup retained native hooks");
                    require(
                            targets(Data.instance.mMainAllApps)
                                            .equals(List.of("oem.app", LauncherCatalog.RESTORE))
                                    && targets(Data.instance.mSecondAllApps)
                                            .equals(List.of("oem.app", LauncherCatalog.RESTORE)),
                            "All Apps cleanup retained injected list entries");
                }
                for (boolean duringLookup : new boolean[] {false, true}) {
                    try (Harness harness = new Harness(old, combined)) {
                        harness.flush(0);
                        harness.removed = !duringLookup;
                        harness.removeDuringLookup = duringLookup;
                        Adapter adapter = new Adapter(Data.instance.mMainAllApps);
                        Holder holder = new Holder();
                        harness.backend.invoke(
                                Adapter.class.getDeclaredMethod(
                                        "onBindViewHolder", Holder.class, int.class),
                                adapter,
                                holder,
                                2);
                        require(
                                harness.apps.snapshot().phase == ModuleRuntime.Phase.READY,
                                "Package removal must not stop either launcher adapter");
                        require(
                                holder.nameView.properties.get("text").equals("OEM name"),
                                "Removed package changed the OEM row");
                    }
                }
                try (Harness harness = new Harness(old, combined)) {
                    harness.flush(0);
                    harness.failIcon = true;
                    Adapter adapter = new Adapter(Data.instance.mMainAllApps);
                    Holder holder = new Holder();
                    harness.backend.invoke(
                            Adapter.class.getDeclaredMethod(
                                    "onBindViewHolder", Holder.class, int.class),
                            adapter,
                            holder,
                            2);
                    require(
                            holder.nameView.properties.get("text").equals("OEM name"),
                            "Presentation failure replayed or lost OEM binding");
                    require(
                            harness.apps.snapshot().phase == ModuleRuntime.Phase.RESTART_REQUIRED,
                            "Partial presentation failure was reported ready");
                }
            }
    }

    private static List<String> targets(List<Bean> list) {
        List<String> result = new ArrayList<>();
        for (Bean bean : list)
            result.add(
                    bean.subtype.startsWith(LauncherCatalog.PREFIX)
                            ? bean.subtype.substring(LauncherCatalog.PREFIX.length())
                            : bean.name);
        return result;
    }

    static final class Harness extends LauncherDockContractDriver.Harness {
        final ModuleRuntime apps;
        Events appsEvents;
        boolean carPlay = true;
        boolean failIcon;
        boolean removed;
        boolean removeDuringLookup;

        Harness(boolean old, boolean combined) {
            super(old, false);
            Data.instance = new Data();
            MethodAccess methods =
                    new MethodAccess(
                            name -> {
                                if (name.startsWith("com.qinggan.launcher.allapp.") && old)
                                    throw new ClassNotFoundException(name);
                                switch (name) {
                                    case "com.qinggan.launcher.allapp.AppBean":
                                    case "com.qinggan.launcher.base.bean.AppBean":
                                        return Bean.class;
                                    case "com.qinggan.launcher.allapp.AllAppDataManager":
                                    case "com.qinggan.launcher.base.allapp.AllAppDataManager":
                                        return Data.class;
                                    case "com.qinggan.launcher.allapp.AllAppAdapter":
                                    case "com.qinggan.launcher.base.adapter.AllAppAdapter":
                                        return Adapter.class;
                                    case "com.qinggan.launcher.allapp.AllAppAdapter$AppViewHolder":
                                    case "com.qinggan.launcher.base.adapter.AllAppAdapter$AppViewHolder":
                                        return Holder.class;
                                    case "com.qinggan.launcher.allapp.AllAppBarView":
                                    case "com.qinggan.launcher.base.allapp.AllAppBarView":
                                        return AllAppsBar.class;
                                    case "com.qinggan.launcher.base.utils.AppLauncher":
                                        return AppLauncher.class;
                                    case "android.content.Context":
                                        return LauncherDockContractDriver.Context.class;
                                    case "android.content.Intent":
                                        return Intent.class;
                                    case "android.view.View":
                                        return LauncherDockContractDriver.View.class;
                                    default:
                                        return LauncherDockContractDriver.Harness.target(name, old);
                                }
                            });
            apps =
                    new ModuleRuntime(
                            "code",
                            scheduler,
                            id ->
                                    combined
                                            ? new LauncherModule(methods, hooks, this)
                                            : new LauncherAllAppsModule(methods, hooks, this, old));
            apps.apply("code", 1, List.of("launcher"), Map.of());
            if (apps.snapshot().phase == ModuleRuntime.Phase.FAILED)
                throw new AssertionError("All Apps preflight failed", apps.snapshot().failure);
        }

        @Override
        public List<String> installed() {
            return List.of(LauncherCatalog.RESTORE, "app.music");
        }

        @Override
        public boolean available(String target) {
            return !removed && (!target.equals(LauncherCatalog.CARPLAY) || carPlay);
        }

        @Override
        public boolean image(Object view) {
            return true;
        }

        @Override
        public Object appIcon(String target) {
            if (removeDuringLookup) removed = true;
            if (removed) throw new IllegalStateException("Package disappeared");
            if (failIcon) throw new IllegalStateException("Icon provider failed");
            return super.appIcon(target);
        }

        @Override
        public String resolvePackage(Object intent) {
            return ((Intent) intent).pkg;
        }

        @Override
        public void watchApps(ResourceGroup resources, Events events) throws Exception {
            appsEvents = events;
            resources.own(() -> {});
        }

        @Override
        public void close() throws Exception {
            apps.close();
            super.close();
        }
    }

    static final class Bean {
        final int icon;
        final int label;
        final String name;
        String subtype = "";

        Bean(int icon, int label, String name) {
            this.icon = icon;
            this.label = label;
            this.name = name;
        }

        String getPackageName() {
            return name;
        }

        String getSubType() {
            return subtype;
        }

        void setSubType(String value) {
            subtype = value;
        }

        int getType() {
            return 1;
        }

        int getIcon() {
            return icon;
        }

        int getNameRes() {
            return label;
        }
    }

    static final class Data {
        static Data instance;
        final List<Bean> mMainAllApps = new ArrayList<>();
        final List<Bean> mSecondAllApps = new ArrayList<>();
        final List<Object> mAllAppDataListeners = new ArrayList<>();

        Data() {
            loadData();
        }

        private static Data getInstance() {
            return instance;
        }

        static List<Bean> getAllApps(int display) {
            return display == 0 ? instance.mMainAllApps : instance.mSecondAllApps;
        }

        void loadData() {
            for (List<Bean> list : List.of(mMainAllApps, mSecondAllApps)) {
                list.clear();
                list.add(new Bean(10, 20, "oem.app"));
                list.add(new Bean(10, 20, LauncherCatalog.RESTORE));
            }
        }

        static void reload() {
            instance.loadData();
        }
    }

    static final class Holder {
        final LauncherDockContractDriver.View iconView = new LauncherDockContractDriver.View(101);
        final LauncherDockContractDriver.View nameView = new LauncherDockContractDriver.View(102);
    }

    static final class Adapter {
        final List<Bean> mAppBeans;
        int notifications;

        Adapter(List<Bean> beans) {
            mAppBeans = beans;
        }

        void onBindViewHolder(Holder holder, int position) {
            holder.iconView.properties.put("background", "OEM background");
            holder.iconView.properties.put("drawable", "OEM image");
            holder.nameView.properties.put("text", "OEM name");
        }

        void onBindViewHolder(Holder holder, int position, List<Object> payload) {
            onBindViewHolder(holder, position);
        }

        void notifyDataSetChanged() {
            notifications++;
        }
    }

    static final class AllAppsBar {
        int mScreenId;
        final Adapter mAllAppAdapter;
        final Layout mLayoutManager = new Layout();

        AllAppsBar(Adapter adapter) {
            mAllAppAdapter = adapter;
        }

        void onClick(LauncherDockContractDriver.View view) {}

        void onAppReload() {}

        void dismiss() {}
    }

    static final class Layout {
        final Recycler mRecyclerView = new Recycler();
        final List<Object> mItemFrames = new ArrayList<>();
    }

    static final class Recycler {
        boolean busy;

        boolean isComputingLayout() {
            return busy;
        }

        void requestLayout() {}
    }

    static final class Component {
        final String pkg;
        final String activity;

        Component(String pkg, String activity) {
            this.pkg = pkg;
            this.activity = activity;
        }

        String getPackageName() {
            return pkg;
        }

        String getClassName() {
            return activity;
        }
    }

    static final class Intent {
        String pkg = "";
        Component component;

        Intent() {}

        Intent(Intent original) {
            pkg = original.pkg;
            component = original.component;
        }

        Component getComponent() {
            return component;
        }

        String getPackage() {
            return pkg;
        }

        Intent setClassName(String pkg, String activity) {
            component = new Component(pkg, activity);
            this.pkg = pkg;
            return this;
        }
    }

    static final class AppLauncher {
        static Intent launched;

        static void startApp(
                LauncherDockContractDriver.Context context, Intent intent, int display) {
            launched = intent;
        }

        static void startApp(
                LauncherDockContractDriver.Context context,
                String pkg,
                String activity,
                int display) {}
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
