package ru.voyahtune.contracts;

import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.NavigationAudioModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class NavigationAudioModuleTest {
    public static void main(String[] arguments) throws Throwable {
        try (Harness harness = new Harness()) {
            Attributes original = new Attributes(3);
            Track track = new Track();
            harness.construct(Track.class.getConstructor(Attributes.class), track, original);
            require(
                    track.attributes.stream == 1 && original.stream == 3,
                    "AudioTrack route did not change or caller attributes were mutated");
            Pool pool = new Pool();
            harness.construct(
                    Pool.class.getConstructor(int.class, Attributes.class), pool, 5, original);
            require(
                    pool.attributes.stream == 1 && pool.streams == 5,
                    "SoundPool attributes argument was not found");

            Player player = new Player();
            harness.call(Player.class, "prepare", player);
            require(
                    player.attributes.stream == 1 && player.prepares == 1,
                    "Default MediaPlayer route was not assigned before preparation");
            harness.call(Player.class, "prepareAsync", player);
            require(player.prepares == 2, "Async preparation did not reach the player");
            harness.call(Player.class, "setAudioAttributes", player, original);
            require(
                    player.attributes.stream == 1 && original.stream == 3,
                    "Explicit player attributes bypassed navigation routing");
            harness.call(Player.class, "setAudioStreamType", player, 3);
            require(player.stream == 1, "Legacy player stream bypassed navigation routing");
            Session session = new Session();
            harness.construct(Session.class.getConstructor(), session);
            require(session.attributes.stream == 1, "Default MediaSession described the music channel");
            harness.call(Session.class, "setPlaybackToLocal", session, original);
            require(session.attributes.stream == 1, "MediaSession described the wrong channel");

            Manager manager = new Manager();
            require(
                    (Integer) harness.call(Manager.class, "requestAudioFocus", manager, 1) == 1
                            && manager.requests == 0,
                    "Selected application stole music focus instead of leaving it to Native");
            harness.call(Manager.class, "abandonAudioFocus", manager, 1);
            harness.call(Manager.class, "abandonAudioFocusRequest", manager, 1);
            require(manager.releases == 0, "Application abandoned the central focus request");

            Parameters parameters = new Parameters();
            parameters.values.put("voice", "saved");
            parameters.values.put("stream", 3);
            Parameters speech =
                    (Parameters) harness.call(Speech.class, "getParams", new Speech(), parameters);
            require(
                    speech != parameters
                            && speech.values.get("stream").equals(1)
                            && speech.values.get("voice").equals("saved")
                            && parameters.values.get("stream").equals(3),
                    "TTS routing lost caller settings or mutated shared defaults");

            harness.enabled = false;
            Track unselected = new Track();
            harness.construct(Track.class.getConstructor(Attributes.class), unselected, original);
            require(unselected.attributes == original, "Deselected application was still rerouted");
            require(
                    (Integer) harness.call(Manager.class, "requestAudioFocus", manager, 1) == 7
                            && manager.requests == 1,
                    "Deselected application did not regain stock focus behavior");
            harness.runtime.apply("code", 2, List.of(), Map.of());
            require(
                    harness.backend.installed.isEmpty() && harness.backend.constructors.isEmpty(),
                    "Disabling navigation audio left live hooks");
        }
        System.out.println("NavigationAudioModuleTest: passed");
    }

    public static final class Attributes {
        final int stream;

        Attributes(int stream) {
            this.stream = stream;
        }
    }

    public static final class Track {
        Attributes attributes;

        Track() {}

        public Track(Attributes attributes) {
            initialize(attributes);
        }

        public void initialize(Attributes attributes) {
            this.attributes = attributes;
        }
    }

    public static final class Pool {
        Attributes attributes;
        int streams;

        Pool() {}

        public Pool(int streams, Attributes attributes) {
            initialize(streams, attributes);
        }

        public void initialize(int streams, Attributes attributes) {
            this.streams = streams;
            this.attributes = attributes;
        }
    }

    public static final class Player {
        Attributes attributes;
        int prepares;
        int stream;

        public void prepare() {
            prepares++;
        }

        public void prepareAsync() {
            prepares++;
        }

        public void setAudioAttributes(Attributes attributes) {
            this.attributes = attributes;
        }

        public void setAudioStreamType(int stream) {
            this.stream = stream;
        }
    }

    public static final class Session {
        Attributes attributes;

        public void initialize() {}

        public void setPlaybackToLocal(Attributes attributes) {
            this.attributes = attributes;
        }
    }

    public static final class Manager {
        int requests;
        int releases;

        public int requestAudioFocus(int gain) {
            requests++;
            return 7;
        }

        public int abandonAudioFocus(int listener) {
            releases++;
            return 7;
        }

        public int abandonAudioFocusRequest(int request) {
            releases++;
            return 7;
        }
    }

    public static final class Parameters {
        final Map<String, Object> values = new HashMap<>();
    }

    public static final class Speech {
        public Parameters getParams(Parameters parameters) {
            return parameters;
        }
    }

    private static final class Harness implements AutoCloseable, NavigationAudioModule.Platform {
        final FixtureHookBackend backend = new FixtureHookBackend(false);
        final HookRegistry hooks = new HookRegistry(backend);
        final ModuleRuntime runtime;
        boolean enabled = true;

        Harness() throws Exception {
            backend.constructorBackups.put(
                    Track.class.getConstructor(Attributes.class),
                    Track.class.getMethod("initialize", Attributes.class));
            backend.constructorBackups.put(
                    Pool.class.getConstructor(int.class, Attributes.class),
                    Pool.class.getMethod("initialize", int.class, Attributes.class));
            backend.constructorBackups.put(Session.class.getConstructor(),
                    Session.class.getMethod("initialize"));
            MethodAccess methods =
                    new MethodAccess(
                            name -> {
                                switch (name) {
                                    case "android.media.AudioTrack":
                                        return Track.class;
                                    case "android.media.SoundPool":
                                        return Pool.class;
                                    case "android.media.AudioAttributes":
                                        return Attributes.class;
                                    case "android.media.MediaPlayer":
                                        return Player.class;
                                    case "android.media.AudioManager":
                                        return Manager.class;
                                    case "android.media.session.MediaSession":
                                        return Session.class;
                                    case "android.speech.tts.TextToSpeech":
                                        return Speech.class;
                                    case "android.os.Bundle":
                                        return Parameters.class;
                                    default:
                                        return Class.forName(name);
                                }
                            });
            runtime =
                    new ModuleRuntime(
                            "code",
                            new ManualScheduler(),
                            id -> new NavigationAudioModule(methods, hooks, this));
            runtime.apply("code", 1, List.of("navigation-audio.app"), Map.of());
            require(
                    runtime.snapshot().phase == ModuleRuntime.Phase.READY,
                    "Audio module not ready");
        }

        void construct(Constructor<?> constructor, Object instance, Object... arguments)
                throws Throwable {
            Object[] packed = new Object[arguments.length + 1];
            packed[0] = instance;
            System.arraycopy(arguments, 0, packed, 1, arguments.length);
            backend.constructors.get(constructor).onCall(packed);
        }

        Object call(Class<?> owner, String name, Object receiver, Object... arguments)
                throws Throwable {
            for (Method method : owner.getDeclaredMethods()) {
                if (method.getName().equals(name))
                    return backend.invoke(method, receiver, arguments);
            }
            throw new NoSuchMethodException(name);
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public Object attributes(Object original) {
            return new Attributes(1);
        }

        @Override
        public void preparePlayer(Object player) {
            ((Player) player).attributes = new Attributes(1);
        }

        @Override
        public Object speechParameters(Object original) {
            Parameters copied = new Parameters();
            if (original != null) copied.values.putAll(((Parameters) original).values);
            copied.values.put("stream", 1);
            return copied;
        }

        @Override
        public void created(Object player) {
            if (player instanceof Session) ((Session) player).attributes = new Attributes(1);
        }

        @Override
        public void close() throws Exception {
            runtime.close();
            hooks.close();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
