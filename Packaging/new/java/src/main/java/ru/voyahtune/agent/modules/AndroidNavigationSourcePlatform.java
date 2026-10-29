package ru.voyahtune.agent.modules;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import ru.voyahtune.agent.runtime.AndroidMainThread;

public final class AndroidNavigationSourcePlatform implements NavigationSourceModule.Platform {
    private static final Uri ENDPOINT = Uri.parse("content://ru.big.town.anative.cluster.navigation");
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    public AndroidNavigationSourcePlatform(Context context) {
        if (context == null) throw new IllegalArgumentException("Application context required for navigation source");
        this.context = context;
    }

    @Override public long now() { return SystemClock.elapsedRealtime(); }
    @Override public boolean enabled() { return !"0".equals(Settings.Global.getString(context.getContentResolver(), "voyahtune_cluster_navigation_enabled")); }
    @Override public void postMain(Runnable action) {
        if (!main.post(action)) throw new IllegalStateException("Navigation main looper rejected work");
    }
    @Override public void drainMain(NavigationSourceModule.MainAction action) throws Exception { AndroidMainThread.run(action::run); }
    @Override public void publish(NavigationSourceModule.Sample sample, long sampled) {
        Bundle data = new Bundle();
        data.putBoolean("navigationActive", sample != null);
        data.putInt("navigationIcon", sample == null ? 0 : sample.icon);
        data.putInt("navigationDistance", sample == null ? 0 : sample.distance);
        data.putString("navigationRoad", sample == null ? "" : sample.road);
        data.putLong("navigationTime", sampled);
        context.getContentResolver().call(ENDPOINT, "maneuver", null, data);
    }
    @Override public void error(Exception failure) { Log.e("vt_navigation", "Navigation source unavailable", failure); }
}
