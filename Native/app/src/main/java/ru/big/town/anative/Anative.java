package ru.big.town.anative;

import android.app.Application;
import android.content.Context;
import android.util.Log;

public class Anative extends Application {
    private NavigationAudioController navigationAudio;

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
            navigationAudio = new NavigationAudioController(this);
        } catch (RuntimeException failure) {
            Log.w("NavigationAudio", "Cannot start application audio routing", failure);
        }
    }

    @Override
    public void onTerminate() {
        if (navigationAudio != null) navigationAudio.close();
        super.onTerminate();
    }
}
