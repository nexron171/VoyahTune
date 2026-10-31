package ru.big.town.common;

import android.media.AudioAttributes;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import java.lang.reflect.Method;

public final class NavigationAudioRouting {
    private NavigationAudioRouting() {}

    public static AudioAttributes attributes(AudioAttributes original, String packageName)
            throws ReflectiveOperationException {
        return attributes(
                original,
                packageName,
                AudioAttributes.Builder.class.getMethod("addTag", String.class));
    }

    public static AudioAttributes attributes(
            AudioAttributes original, String packageName, Method addTag)
            throws ReflectiveOperationException {
        AudioAttributes.Builder builder =
                new AudioAttributes.Builder().setLegacyStreamType(NavigationAudioSettings.STREAM);
        if (original != null) builder.setAllowedCapturePolicy(original.getAllowedCapturePolicy());
        addTag.invoke(builder, NavigationAudioSettings.TAG_PREFIX + packageName);
        return builder.build();
    }

    public static Bundle speechParameters(Bundle original, String packageName)
            throws ReflectiveOperationException {
        return speechParameters(
                original,
                packageName,
                AudioAttributes.Builder.class.getMethod("addTag", String.class));
    }

    public static Bundle speechParameters(Bundle original, String packageName, Method addTag)
            throws ReflectiveOperationException {
        Bundle parameters = original == null ? new Bundle() : new Bundle(original);
        parameters.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, NavigationAudioSettings.STREAM);
        AudioAttributes source = parameters.getParcelable("audioAttributes");
        parameters.putParcelable("audioAttributes", attributes(source, packageName, addTag));
        return parameters;
    }
}
