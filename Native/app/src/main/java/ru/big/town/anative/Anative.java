package ru.big.town.anative;

import android.app.Application;
import android.content.Context;
import android.util.Log;

public class Anative extends Application {
    private NavigationAudioController navigationAudio;
    private NavigationMediaDucking navigationMedia;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (new java.io.File("/data/local/bin/voyahtune-update.block").exists()) {
            android.os.Process.killProcess(android.os.Process.myPid());
            throw new IllegalStateException("VoyahTune update requires USB repair");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        GlobalVars.SAVE_CONTEXT = getBaseContext();

        try {
            navigationAudio = new NavigationAudioController(this, navigationMediaDucking());
        } catch (RuntimeException failure) {
            Log.w("NavigationAudio", "Cannot start application audio routing", failure);
        }
    }

    NavigationMediaDucking navigationMediaDucking() {
        if (navigationMedia == null) navigationMedia = new NavigationMediaDucking(this);
        return navigationMedia;
    }

    @Override
    public void onTerminate() {
        if (navigationAudio != null) navigationAudio.close();
        if (navigationMedia != null) navigationMedia.close();
        super.onTerminate();
    }
}
