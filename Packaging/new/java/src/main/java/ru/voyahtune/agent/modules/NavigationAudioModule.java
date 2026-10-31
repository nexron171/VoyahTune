package ru.voyahtune.agent.modules;

import ru.big.town.common.NavigationAudioSettings;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.ReflectionAccess;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class NavigationAudioModule implements ModuleRuntime.Module {
    public interface Platform {
        boolean enabled();

        Object attributes(Object original) throws Exception;

        void preparePlayer(Object player) throws Exception;

        Object speechParameters(Object original) throws Exception;

        default void created(Object player) throws Exception {}
    }

    private enum Kind {
        ATTRIBUTES,
        STREAM,
        PREPARE,
        FOCUS,
        SPEECH,
        CREATED
    }

    private static final class Target {
        final Executable executable;
        final Kind kind;
        final int argument;

        Target(Executable executable, Kind kind, int argument) {
            this.executable = executable;
            this.kind = kind;
            this.argument = argument;
        }
    }

    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final Platform platform;
    private final List<Target> targets = new ArrayList<>();

    public NavigationAudioModule(MethodAccess methods, HookRegistry hooks, Platform platform) {
        this.methods = methods;
        this.hooks = hooks;
        this.platform = platform;
    }

    @Override
    public void prepare(Map<String, String> configuration) throws Exception {
        targets.clear();
        constructors("android.media.AudioTrack");
        constructors("android.media.SoundPool");
        for (Constructor<?> constructor : ReflectionAccess.allConstructors(
                methods.type("android.media.session.MediaSession"))) {
            targets.add(new Target(constructor, Kind.CREATED, 0));
        }
        target(
                "android.media.MediaPlayer",
                "setAudioAttributes",
                Kind.ATTRIBUTES,
                "android.media.AudioAttributes");
        target("android.media.MediaPlayer", "setAudioStreamType", Kind.STREAM, "int");
        target("android.media.MediaPlayer", "prepare", Kind.PREPARE);
        target("android.media.MediaPlayer", "prepareAsync", Kind.PREPARE);
        target(
                "android.media.session.MediaSession",
                "setPlaybackToLocal",
                Kind.ATTRIBUTES,
                "android.media.AudioAttributes");
        target("android.speech.tts.TextToSpeech", "getParams", Kind.SPEECH, "android.os.Bundle");
        for (String name :
                new String[] {
                    "requestAudioFocus", "abandonAudioFocus", "abandonAudioFocusRequest"
                }) {
            List<Method> overloads = methods.overloads("android.media.AudioManager", name);
            if (overloads.isEmpty()) throw new NoSuchMethodException(name);
            for (Method method : overloads) {
                if (method.getReturnType() == int.class) {
                    targets.add(new Target(method, Kind.FOCUS, 0));
                }
            }
        }
    }

    private void constructors(String className) throws Exception {
        Class<?> attributes = methods.type("android.media.AudioAttributes");
        boolean found = false;
        for (Constructor<?> constructor :
                ReflectionAccess.allConstructors(methods.type(className))) {
            Class<?>[] parameters = constructor.getParameterTypes();
            for (int index = 0; index < parameters.length; index++) {
                if (parameters[index] == attributes) {
                    targets.add(new Target(constructor, Kind.ATTRIBUTES, index));
                    found = true;
                    break;
                }
            }
        }
        if (!found) throw new NoSuchMethodException(className + " AudioAttributes constructor");
    }

    private void target(String owner, String name, Kind kind, String... parameters)
            throws Exception {
        targets.add(new Target(methods.require(owner, name, parameters), kind, 0));
    }

    @Override
    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        for (Target target : targets) {
            scope.own(
                    hooks.install(
                            "navigation-audio",
                            target.executable,
                            scope.callback(invocation -> invoke(target, invocation))));
        }
    }

    private Object invoke(Target target, HookRegistry.Invocation invocation) throws Throwable {
        if (!platform.enabled()) return invocation.proceed();
        switch (target.kind) {
            case ATTRIBUTES:
                invocation.arguments()[target.argument] =
                        platform.attributes(invocation.arguments()[target.argument]);
                break;
            case STREAM:
                invocation.arguments()[0] = NavigationAudioSettings.STREAM;
                break;
            case PREPARE:
                platform.preparePlayer(invocation.receiver());
                break;
            case SPEECH:
                return platform.speechParameters(invocation.proceed());
            case FOCUS:
                // Native owns transient navigation focus for actual playback, including TTS.
                return 1;
            case CREATED:
                break;
        }
        Object result = invocation.proceed();
        if (target.executable instanceof Constructor<?>) platform.created(invocation.receiver());
        return result;
    }

    @Override
    public void configure(ModuleScope scope, Map<String, String> configuration) {}
}
