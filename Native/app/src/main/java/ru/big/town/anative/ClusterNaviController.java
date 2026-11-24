package ru.big.town.anative;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import ru.big.town.common.VehiclePlatform;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Voice control of the configured application on the cluster and the head unit. */
final class ClusterNaviController {
    static final String COMPONENT_SETTING = "voyahtune_cluster_navi_component";
    private static final String CENTRAL_CARD_SETTING = "voyahtune_cluster_navi";
    static final String BROADCAST_ACTION = "ru.big.town.anative.CLUSTER_NAVI";
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

        void forceStopApplication(String packageName);

        void releaseClusterCards(String packageName);

        boolean openMediaCard(String packageName);

        void sendStateBroadcast(int enabled);

        void launchApplication(String packageName);
    }

    static String execute(Context context, String action) {
        return execute(new PlatformBackend(context), VehiclePlatform.current(), action);
    }

    static String execute(Backend backend, VehiclePlatform platform, String action) {
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
                return "Неизвестное действие";
        }
        try {
            String packageName = configuredPackage(backend.configuredComponent());
            if (!backend.canStopApplication(packageName)) {
                return "Нельзя закрыть приложение навигации: " + packageName;
            }
            boolean usesMediaCard = platform.isH97XY();
            if (usesMediaCard) {
                // A pending host could relaunch the app after force-stop.
                backend.releaseClusterCards(packageName);
            }
            backend.forceStopApplication(packageName);
            if (!usesMediaCard) {
                backend.sendStateBroadcast(
                        destination == Destination.CLUSTER ? STATE_ENABLED : STATE_DISABLED);
            } else if (destination == Destination.CLUSTER && !backend.openMediaCard(packageName)) {
                return "Не удалось открыть приложение в медиакарточке";
            }
            if (destination == Destination.HEAD_UNIT) {
                backend.launchApplication(packageName);
            }
            return "";
        } catch (RuntimeException | LinkageError failure) {
            Log.w("VoyahClusterNavi", "Cluster navigation failed for " + action, failure);
            return "Не удалось переключить навигацию на экране водителя";
        }
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
        public void forceStopApplication(String packageName) {
            WidgetSupport.stopApp(context, packageName);
        }

        @Override
        public void releaseClusterCards(String packageName) {
            ContentResolver settings = context.getContentResolver();
            if (Settings.Global.getInt(settings, CENTRAL_CARD_SETTING, STATE_DISABLED)
                    == STATE_ENABLED) {
                // The bridge reads this setting when its display is recreated.
                Settings.Global.putInt(settings, CENTRAL_CARD_SETTING, STATE_DISABLED);
                sendStateBroadcast(STATE_DISABLED);
            }
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
