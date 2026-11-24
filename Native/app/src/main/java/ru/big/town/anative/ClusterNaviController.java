package ru.big.town.anative;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import ru.big.town.common.ClusterAppAction;
import ru.big.town.common.VehiclePlatform;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Routes built-in navigation commands and per-assignment applications to the cluster. */
final class ClusterNaviController {
    static final String COMPONENT_SETTING = "voyahtune_cluster_navi_component";
    private static final String CENTRAL_CARD_SETTING = "voyahtune_cluster_navi";
    static final String BROADCAST_ACTION = "ru.big.town.anative.CLUSTER_NAVI";
    static final String OPEN_APP_BROADCAST = "ru.big.town.anative.CLUSTER_OPEN_APP";
    static final int STATE_ENABLED = 1;
    static final int STATE_DISABLED = 0;

    /** Mirrors the bridge default, so an unset setting behaves as RunYN and the hook already do. */
    static final String FALLBACK_PACKAGE = "ru.yandex.yandexnavi";

    private enum Destination {
        CLUSTER,
        HEAD_UNIT,
        CLOSED
    }

    interface Backend {
        String configuredComponent();

        boolean canStopApplication(String packageName);

        String launcherComponent(String packageName);

        void forceStopApplication(String packageName);

        void releaseCentralCard(boolean selectMediaCard);

        void closeMediaCard(String packageName);

        boolean openMediaCard(String packageName);

        void openCentralApp(String component);

        void sendStateBroadcast(int enabled);

        void launchApplication(String packageName);
    }

    static void submit(Context context, String action, Consumer<ActionResult> completion) {
        String[] error = {"Не удалось переключить приложение на приборной панели"};
        ApplyEngine.postIndependentUserCommand(
                action,
                () ->
                        error[0] =
                                execute(
                                        new PlatformBackend(context),
                                        VehiclePlatform.current(),
                                        action),
                () -> completion.accept(ActionResult.from(action, error[0])));
    }

    static String execute(Backend backend, VehiclePlatform platform, String action) {
        ClusterAppAction assignment = ClusterAppAction.parse(action);
        final Destination destination;
        switch (action) {
            case "cluster_navi:start":
                destination = Destination.CLUSTER;
                break;
            case "cluster_navi:stop":
                destination = Destination.CLOSED;
                break;
            case "cluster_navi:main_screen":
                destination = Destination.HEAD_UNIT;
                break;
            default:
                if (assignment == null) return "Неизвестное действие";
                destination = Destination.CLUSTER;
        }
        try {
            String packageName =
                    assignment == null
                            ? configuredPackage(backend.configuredComponent())
                            : assignment.packageName;
            if (!backend.canStopApplication(packageName)) {
                return assignment == null
                        ? "Нельзя закрыть приложение навигации: " + packageName
                        : "Приложение недоступно для запуска на приборной панели: " + packageName;
            }
            String launcher = assignment == null ? null : backend.launcherComponent(packageName);
            if (assignment != null && launcher == null) {
                return "Не удалось открыть приложение: " + packageName;
            }
            boolean usesMediaCard =
                    assignment == null
                            ? platform.isH97XY()
                            : assignment.placementFor(platform) == ClusterAppAction.Placement.MEDIA;
            if (usesMediaCard) {
                backend.releaseCentralCard(!platform.isH97XY());
            }
            // A pending media host could relaunch the app on its old display after force-stop.
            backend.closeMediaCard(packageName);
            backend.forceStopApplication(packageName);
            if (destination == Destination.CLUSTER) {
                return openOnCluster(backend, usesMediaCard, packageName, launcher);
            }
            if (!usesMediaCard) backend.sendStateBroadcast(STATE_DISABLED);
            if (destination == Destination.HEAD_UNIT) {
                backend.launchApplication(packageName);
            }
            return "";
        } catch (RuntimeException | LinkageError failure) {
            Log.w("VoyahClusterNavi", "Cluster navigation failed for " + action, failure);
            return "Не удалось переключить навигацию на экране водителя";
        }
    }

    private static String openOnCluster(
            Backend backend, boolean usesMediaCard, String packageName, String assignedComponent) {
        if (usesMediaCard) {
            return backend.openMediaCard(packageName)
                    ? ""
                    : "Не удалось открыть приложение в медиакарточке";
        }
        if (assignedComponent != null) {
            backend.openCentralApp(assignedComponent);
        } else {
            backend.sendStateBroadcast(STATE_ENABLED);
        }
        return "";
    }

    static String configuredPackage(String configuredComponent) {
        ComponentName component =
                configuredComponent == null
                        ? null
                        : ComponentName.unflattenFromString(configuredComponent);
        return component == null ? FALLBACK_PACKAGE : component.getPackageName();
    }

    static final class PlatformBackend implements Backend {
        private final Context context;

        PlatformBackend(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override
        public String configuredComponent() {
            return Settings.Global.getString(context.getContentResolver(), COMPONENT_SETTING);
        }

        @Override
        public boolean canStopApplication(String packageName) {
            return WidgetSupport.isExternal(context, packageName);
        }

        @Override
        public String launcherComponent(String packageName) {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
            ComponentName component = launch == null ? null : launch.getComponent();
            return component != null && packageName.equals(component.getPackageName())
                    ? component.flattenToShortString()
                    : null;
        }

        @Override
        public void forceStopApplication(String packageName) {
            WidgetSupport.stopApp(context, packageName);
        }

        @Override
        public void releaseCentralCard(boolean selectMediaCard) {
            ContentResolver settings = context.getContentResolver();
            if (Settings.Global.getInt(settings, CENTRAL_CARD_SETTING, STATE_DISABLED)
                    == STATE_ENABLED) {
                // The bridge reads this setting when its display is recreated.
                Settings.Global.putInt(settings, CENTRAL_CARD_SETTING, STATE_DISABLED);
                selectMediaCard = true;
            }
            if (selectMediaCard) sendStateBroadcast(STATE_DISABLED);
        }

        @Override
        public void closeMediaCard(String packageName) {
            CompletableFuture.runAsync(
                            () -> ClusterMediaHostActivity.closeForPackage(packageName),
                            mainExecutor())
                    .join();
        }

        @Override
        public boolean openMediaCard(String packageName) {
            return CompletableFuture.supplyAsync(
                            () -> ClusterMediaHostActivity.launch(context, packageName),
                            mainExecutor())
                    .join();
        }

        @Override
        public void openCentralApp(String component) {
            context.sendBroadcast(
                    new Intent(OPEN_APP_BROADCAST)
                            .setPackage("com.qinggan.cluster")
                            .putExtra("component", component));
        }

        @Override
        public void sendStateBroadcast(int enabled) {
            // The cluster bridge registers its receiver at runtime.
            context.sendBroadcast(new Intent(BROADCAST_ACTION).putExtra("enable", enabled));
        }

        @Override
        public void launchApplication(String packageName) {
            CompletableFuture.runAsync(
                            () -> WidgetSupport.openApp(context, packageName), mainExecutor())
                    .join();
        }

        private Executor mainExecutor() {
            // Host lifecycle operations must finish in command order on the activity thread.
            return Looper.myLooper() == context.getMainLooper()
                    ? Runnable::run
                    : context.getMainExecutor();
        }
    }

    private ClusterNaviController() {}
}
