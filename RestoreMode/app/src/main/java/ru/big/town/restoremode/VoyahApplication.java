package ru.big.town.restoremode;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Start warmup when VoyahTune becomes visible, not when a provider wakes its process. */
public final class VoyahApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) {
                if (activity instanceof VoiceActivity && ((VoiceActivity) activity).isAnimationPreview()) return;
                VoiceWarmupService.sync(activity);
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }
}
