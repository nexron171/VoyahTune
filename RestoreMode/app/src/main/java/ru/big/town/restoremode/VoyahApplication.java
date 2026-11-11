package ru.big.town.restoremode;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import ru.big.town.restoremode.dashboard.DashboardTransferPolicy;
import ru.big.town.restoremode.voice.commands.VoiceCommands;

/** Start warmup when VoyahTune becomes visible, not when a provider wakes its process. */
public final class VoyahApplication extends Application {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        if (new java.io.File("/data/local/bin/voyahtune-update.block").exists()) {
            android.os.Process.killProcess(android.os.Process.myPid());
            throw new IllegalStateException("VoyahTune update requires USB repair");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ru.big.town.restoremode.carplay.CarPlayFeature.initialize(this);
        ru.big.town.restoremode.carplay.CarPlayVehicleBridge carPlayBridge =
                new ru.big.town.restoremode.carplay.CarPlayVehicleBridge(this);
        com.shilapi.xcertplay.VoyahCarPlayIntegration.install(carPlayBridge);

        com.shilapi.xcertplay.network.VoyahWifiScanPause.install(carPlayBridge);

        com.shilapi.xcertplay.CarPlayHomeUi.install(
                ru.big.town.restoremode.carplay.CarPlayHomeScreen::new);
        com.shilapi.xcertplay.CarPlayWindowUi.install(
                new ru.big.town.restoremode.carplay.CarPlayWindowPolicy());
        // Voice settings edits (dial entries, steering actions, splits, custom CAN) invalidate
        // the shared command catalog; the next load rebuilds it.
        getSharedPreferences("DrivePreferences", MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(
                        (store, key) -> VoiceCommands.invalidate());
        registerActivityLifecycleCallbacks(
                new ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityStarted(Activity activity) {
                        if (activity instanceof VoiceActivity
                                && ((VoiceActivity) activity).isAnimationPreview()) {
                            return;
                        }
                        VoiceWarmupService.sync(activity);
                    }

                    @Override
                    public void onActivityCreated(Activity activity, Bundle state) {
                        DashboardTransferPolicy.apply(activity);
                    }

                    @Override
                    public void onActivityResumed(Activity activity) {
                        // OEM controllers attach with DecorView, after the resume callback.
                        activity.getWindow()
                                .getDecorView()
                                .post(
                                        () -> {
                                            if (!activity.isFinishing()
                                                    && !activity.isDestroyed()) {
                                                DashboardTransferPolicy.apply(activity);
                                            }
                                        });
                    }

                    @Override
                    public void onActivityPaused(Activity activity) {}

                    @Override
                    public void onActivityStopped(Activity activity) {}

                    @Override
                    public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

                    @Override
                    public void onActivityDestroyed(Activity activity) {}
                });
    }
}
