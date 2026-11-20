package ru.big.town.anative;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/**
 * Navigation card on the instrument cluster. The application is whatever the
 * {@code voyahtune_cluster_navi_component} global setting names; it is always stopped first, so a
 * restart re-reads its state and a stop does not leave the process running in the background. The
 * card itself is switched by the same broadcast RunYN sends to {@code AndroidClusterCardBridge}.
 * Beyond showing and hiding the card, the navigator can be handed back to its own screen: free the
 * card, then launch the application again.
 */
final class ClusterNaviController {
    static final String COMPONENT_SETTING = "voyahtune_cluster_navi_component";
    static final String BROADCAST_ACTION = "ru.big.town.anative.CLUSTER_NAVI";
    static final int STATE_ENABLED = 1;
    static final int STATE_DISABLED = 0;

    /** Mirrors the bridge default, so an unset setting behaves as RunYN and the hook already do. */
    static final String FALLBACK_PACKAGE = "ru.yandex.yandexnavi";

    interface Backend {
        String configuredComponent();

        boolean canStopApplication(String packageName);

        void forceStopApplication(String packageName);

        void sendStateBroadcast(int enabled);

        void launchApplication(String packageName);
    }

    static String execute(Context context, String action) {
        return execute(new PlatformBackend(context), action);
    }

    static String execute(Backend backend, String action) {
        final int enabled;
        final boolean backOnOwnScreen;
        switch (action) {
            case "cluster_navi:start":
                enabled = STATE_ENABLED;
                backOnOwnScreen = false;
                break;
            case "cluster_navi:stop":
                enabled = STATE_DISABLED;
                backOnOwnScreen = false;
                break;
            case "cluster_navi:main_screen":
                // The navigator leaves the cluster: free the card first, otherwise the bridge keeps
                // taking the app back to its VirtualDisplay.
                enabled = STATE_DISABLED;
                backOnOwnScreen = true;
                break;
            default:
                return "Неизвестное действие";
        }
        try {
            String packageName = configuredPackage(backend.configuredComponent());
            // Report a package we are not allowed to stop instead of claiming a restart happened.
            if (!backend.canStopApplication(packageName)) {
                return "Нельзя закрыть приложение навигации: " + packageName;
            }
            backend.forceStopApplication(packageName);
            backend.sendStateBroadcast(enabled);
            if (backOnOwnScreen) backend.launchApplication(packageName);
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

    private static final class PlatformBackend implements Backend {
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
            // Same close path as the task manager's per-card "Закрыть".
            WidgetSupport.stopApp(context, packageName);
        }

        @Override
        public void sendStateBroadcast(int enabled) {
            // Implicit on purpose: the bridge registers its receiver at runtime, and this is the
            // form already proven by "am broadcast -a ru.big.town.anative.CLUSTER_NAVI".
            context.sendBroadcast(new Intent(BROADCAST_ACTION).putExtra("enable", enabled));
        }

        @Override
        public void launchApplication(String packageName) {
            // The component the bridge stores is a launcher activity (RunYN fills it from
            // CATEGORY_LAUNCHER), so the canonical package launch reaches the same entry point and
            // keeps the freeform, fullscreen and cluster-host handling.
            WidgetSupport.openApp(context, packageName);
        }
    }

    private ClusterNaviController() {}
}
