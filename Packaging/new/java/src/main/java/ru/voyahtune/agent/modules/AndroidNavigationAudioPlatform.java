package ru.voyahtune.agent.modules;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import ru.big.town.common.NavigationAudioRouting;
import ru.big.town.common.NavigationAudioSettings;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.OemAccess;

import java.lang.reflect.Method;

public final class AndroidNavigationAudioPlatform implements NavigationAudioModule.Platform {
    private final Context context;
    private final Method addTag;

    public AndroidNavigationAudioPlatform(Context context, MethodAccess methods) throws Exception {
        this.context = context;
        addTag =
                methods.require(
                        "android.media.AudioAttributes$Builder", "addTag", "java.lang.String");
        addTag.setAccessible(true);
    }

    @Override
    public boolean enabled() {
        return NavigationAudioSettings.contains(
                Settings.Global.getString(
                        context.getContentResolver(), NavigationAudioSettings.GLOBAL_PACKAGES),
                context.getPackageName());
    }

    @Override
    public Object attributes(Object original) throws Exception {
        return NavigationAudioRouting.attributes(
                (AudioAttributes) original, context.getPackageName(), addTag);
    }

    @Override
    public void preparePlayer(Object player) throws Exception {
        AudioAttributes original = (AudioAttributes) OemAccess.read(player, "mAttributes");
        ((MediaPlayer) player).setAudioAttributes((AudioAttributes) attributes(original));
    }

    @Override
    public Object speechParameters(Object original) throws Exception {
        return NavigationAudioRouting.speechParameters(
                (Bundle) original, context.getPackageName(), addTag);
    }

    @Override
    public void created(Object player) throws Exception {
        if (player instanceof MediaSession) {
            ((MediaSession) player).setPlaybackToLocal((AudioAttributes) attributes(null));
            return;
        }
        if (!(player instanceof AudioTrack)) return;
        AudioTrack track = (AudioTrack) player;
        String message = "package=" + context.getPackageName()
                + " requested=" + NavigationAudioSettings.STREAM + " actual=" + track.getStreamType()
                + " initialized=" + (track.getState() == AudioTrack.STATE_INITIALIZED);
        Log.i("vt_navigation_audio", message);
    }
}
