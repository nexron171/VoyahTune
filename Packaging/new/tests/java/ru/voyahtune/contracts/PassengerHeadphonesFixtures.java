package ru.voyahtune.contracts;

import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.PassengerHeadphonesModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

final class PassengerHeadphonesFixtures implements AutoCloseable, PassengerHeadphonesModule.Platform {
    static final String PLAYER = "org.example.player";
    static final String OTHER = "org.example.other";
    static final String STOCK = "stock.player";
    static final String STOCK_GROUP = "stock.player;stock.service";

    final Backend backend = new Backend();
    final HookRegistry hooks = new HookRegistry(backend);
    final ManualScheduler scheduler = new ManualScheduler();
    final Map<String, Integer> applications = new ConcurrentHashMap<>();
    final Map<String, Integer> displays = new ConcurrentHashMap<>();
    final Map<String, Integer> nativeRoutes = new ConcurrentHashMap<>();
    final Set<Object> stoppedCollectors = ConcurrentHashMap.newKeySet();
    final Engine engine = new Engine(this);
    final Collector collector = engine.u;
    final Focus focus = collector.f;
    Object currentEngine = engine;
    final ModuleRuntime runtime;
    boolean engineAvailable = true;
    boolean lookupFailure;
    Runnable duringSnapshot = () -> {};
    Runnable afterGroup = () -> {};
    Runnable afterMediaMatch = () -> {};
    RuntimeException switchFailure;
    BiConsumer<String, Integer> beforeNativeRoute = (name, channel) -> {};

    PassengerHeadphonesFixtures() {
        this(Service.class);
    }

    PassengerHeadphonesFixtures(Class<?> service) {
        applications.put(PLAYER, 0);
        applications.put(OTHER, 0);
        MethodAccess methods = new MethodAccess(name -> {
            switch (name) {
                case "com.qinggan.audiopolicy.service.OSAudioPolicyService": return service;
                case "android.os.Bundle": return Bundle.class;
                case "android.os.IBinder": return Binder.class;
                case "com.qinggan.audiopolicy.IAudioPolicyCallback": return Callback.class;
                case "be":
                case "ru.voyahtune.contracts.be": return Configuration.class;
                default: return Class.forName(name);
            }
        });
        runtime = new ModuleRuntime("code", scheduler,
                id -> new PassengerHeadphonesModule(methods, hooks, this));
    }

    void enable() {
        runtime.apply("code", 1, List.of("passenger-headphones"), Map.of());
        scheduler.advance(0);
    }

    void disable() {
        runtime.apply("code", 2, List.of(), Map.of());
    }

    void display(String packageName, int display) throws Exception {
        Bundle bundle = new Bundle();
        bundle.putCharSequence("appinfo", packageName);
        bundle.putShort("disp", (short) display);
        call(collector, "a", new Class<?>[] {Bundle.class}, bundle);
    }

    boolean matches(String packageName, int usage, int content, int display) throws Exception {
        return (Boolean) call(collector, "a",
                new Class<?>[] {String.class, int.class, int.class, int.class},
                packageName, usage, content, display);
    }

    int request(String packageName, int usage, int content) throws Exception {
        return request(packageName, packageName, usage, content);
    }

    int request(String clientId, String packageName, int usage, int content) throws Exception {
        return (Integer) call(focus, "b", Focus.REQUEST,
                clientId, null, null, usage, content, packageName, 1, 0, 0);
    }

    void connect(boolean connected) throws Exception {
        collector.connected = connected;
        call(collector, "c", new Class<?>[] {boolean.class}, connected);
    }

    void abandon(String clientId) {
        focus.g.removeIf(client -> client.clientId.equals(clientId));
    }

    Object call(Object receiver, String name, Class<?>[] types, Object... arguments) throws Exception {
        Class<?> owner = receiver instanceof Class<?> ? (Class<?>) receiver : receiver.getClass();
        Method method = owner.getDeclaredMethod(name, types);
        try {
            return backend.invoke(method, receiver instanceof Class<?> ? null : receiver, arguments);
        } catch (Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new Exception(failure);
        }
    }

    String group(String packageName) throws Exception {
        return (String) call(collector, "a", new Class<?>[] {String.class}, packageName);
    }

    int volume(String packageName) throws Exception {
        return engine.b(3, packageName);
    }

    void volume(String packageName, int value) throws Exception {
        engine.a(3, value, 0, false, packageName);
    }

    boolean receivesVolume(String packageName, boolean headphones) throws Exception {
        boolean passenger = engine.passengerVolume(packageName);
        return headphones == passenger;
    }

    @Override
    public int read(String packageName) {
        Integer flags = applications.get(packageName);
        if (lookupFailure || flags == null) throw new IllegalArgumentException("Package unavailable");
        return flags;
    }

    @Override
    public Object engine() {
        return engineAvailable ? currentEngine : null;
    }

    @Override
    public Map<String, Integer> displays() {
        Map<String, Integer> snapshot = new HashMap<>(displays);
        duringSnapshot.run();
        return snapshot;
    }

    @Override
    public void onCollector(Object target, ModuleScope.ConfigurationAction action) throws Exception {
        if (stoppedCollectors.contains(target)) throw new IllegalStateException("OEM collector looper stopped");
        action.apply();
    }

    Engine restartService() throws Exception {
        stoppedCollectors.add(collector);
        Engine replacement = new Engine(this);
        currentEngine = replacement;
        call(new Service(), "onCreate", new Class<?>[0]);
        return replacement;
    }

    @Override
    public void close() throws Exception {
        afterGroup = () -> {};
        afterMediaMatch = () -> {};
        switchFailure = null;
        beforeNativeRoute = (name, channel) -> {};
        runtime.close();
        hooks.close();
    }

    public interface Binder {}

    public interface Callback {}

    public static final class Bundle {
        final Map<String, Object> values = new HashMap<>();

        public void putCharSequence(String key, CharSequence value) { values.put(key, value); }
        public CharSequence getCharSequence(String key) { return (CharSequence) values.get(key); }
        public void putShort(String key, short value) { values.put(key, value); }
        public short getShort(String key) { return (Short) values.getOrDefault(key, (short) 0); }
    }

    public static final class Service {
        private Engine b;

        public void onCreate() {}
    }

    public static final class IncompatibleService {
        private String b;
    }

    public static final class Configuration {
        private static final String[] e = {"stock.volume"};

        public static boolean f(String packageName) {
            return e[0].equals(packageName);
        }
    }

    public static final class Engine {
        private final Collector u;
        final PassengerHeadphonesFixtures fixture;
        int cabinVolume = 8;
        int headphoneVolume = 4;
        int cabinNotifications;

        Engine(PassengerHeadphonesFixtures fixture) {
            this.fixture = fixture;
            u = new Collector(this);
        }

        boolean passengerVolume(String packageName) throws Exception {
            return (Boolean) fixture.call(Configuration.class, "f", new Class<?>[] {String.class}, packageName)
                    && u.e() && u.c(packageName);
        }

        public int b(int stream, String packageName) throws Exception {
            return passengerVolume(packageName) ? headphoneVolume : cabinVolume;
        }

        public void a(int stream, int volume, int flags, boolean sync, String packageName) throws Exception {
            if (passengerVolume(packageName)) headphoneVolume = volume;
            else cabinVolume = volume;
        }

        public void X() { cabinNotifications++; }
    }

    // OEM boundaries retain the original whitelist, attributes, cache and separate focus lanes.
    public static final class Collector {
        private final String[] n = {STOCK_GROUP, "stock.volume"};
        private final ConcurrentHashMap<String, Integer> c = new ConcurrentHashMap<>();
        private final Engine h;
        private final Focus f;
        final Map<String, Integer> routes;
        boolean connected = true;
        RuntimeException lookupFailure;

        Collector(Engine engine) {
            h = engine;
            routes = engine.fixture.nativeRoutes;
            f = new Focus(this);
        }

        public String a(String packageName) {
            if (lookupFailure != null) throw lookupFailure;
            if (packageName == null || packageName.isEmpty()) return null;
            for (String group : n) {
                if (group.contains(packageName)) return group;
            }
            return null;
        }

        private boolean d(String packageName) {
            return a(packageName) != null;
        }

        public boolean a(String packageName, int usage, int content) throws Exception {
            return usage == 1 && (content == 0 || content == 2 || content == 19)
                    && (Boolean) h.fixture.call(this, "d", new Class<?>[] {String.class}, packageName);
        }

        public boolean a(String packageName, int usage, int content, int display) throws Exception {
            return (Boolean) h.fixture.call(this, "a", new Class<?>[] {String.class, int.class, int.class},
                    packageName, usage, content) && display == b(packageName);
        }

        public int b(String packageName) throws Exception {
            String group = h.fixture.group(packageName);
            return group == null ? 0 : c.getOrDefault(group, 0);
        }

        public boolean c(String packageName) { return c.getOrDefault(packageName, 0) == 1; }
        public boolean e() { return connected; }

        public void b(String group, int display) {
            h.fixture.beforeNativeRoute.accept(group, display);
            if (h.fixture.switchFailure != null) throw h.fixture.switchFailure;
            if (group != null) {
                for (String packageName : group.split(";")) routes.put(packageName, display == 1 ? 1 : 0);
            }
        }

        void route(String group, int display) throws Exception {
            h.fixture.call(this, "b", new Class<?>[] {String.class, int.class}, group, display);
        }

        public void a(Bundle bundle) throws Exception {
            String packageName = (String) bundle.getCharSequence("appinfo");
            int display = bundle.getShort("disp");
            String group = h.fixture.group(packageName);
            h.fixture.afterGroup.run();
            if (group == null || (display != 0 && display != 1)) return;
            c.put(group, display);
            if (connected && display == 1) {
                Client client = f.find(group);
                if (client != null && (Boolean) h.fixture.call(this, "a",
                        new Class<?>[] {String.class, int.class, int.class},
                        client.packageName, client.usage, client.content)) {
                    f.g.remove(client);
                    f.source(client);
                }
            } else if (connected && display == 0) {
                Client client = f.b();
                if (client != null) f.c(client);
            }
            route(group, connected ? display : 0);
        }

        private void c(boolean connected) throws Exception {
            if (connected) {
                Client client = f.f();
                if (client != null && h.fixture.matches(client.packageName, client.usage, client.content, 1)) {
                    h.fixture.afterMediaMatch.run();
                    f.g.remove(client);
                    f.source(client);
                }
            } else {
                Client client = f.b();
                if (client != null) {
                    client.a(301);
                    route(h.fixture.group(client.packageName), 0);
                }
            }
        }
    }

    public static final class Focus {
        static final Class<?>[] REQUEST = {String.class, Binder.class, Callback.class,
                int.class, int.class, String.class, int.class, int.class, int.class};
        private static final Object e = new Object();
        private static final Object m = new Object();
        private final Collector d;
        private final Stack<Client> g = new Stack<>();
        private Client n;

        Focus(Collector collector) { d = collector; }

        public Client a() { synchronized (m) { return n; } }

        public Client b() {
            synchronized (m) {
                Client previous = n;
                n = null;
                return previous;
            }
        }

        public Client f() {
            synchronized (g) {
                for (int index = g.size() - 1; index >= 0; index--) {
                    if (g.get(index).usage == 1) return g.get(index);
                }
                return null;
            }
        }

        Client find(String group) {
            for (Client client : new ArrayList<>(g)) {
                if (group.contains(client.packageName)) return client;
            }
            return null;
        }

        public int b(String clientId, Binder token, Callback callback, int usage, int content,
                String packageName, int gain, int flags, int extra) throws Exception {
            return (Integer) d.h.fixture.call(this, "b", REQUEST,
                    clientId, token, callback, usage, content, packageName, gain, flags, extra);
        }

        public int requestCabinOrSource(String clientId, Binder token, Callback callback, int usage, int content,
                String packageName, int gain, int flags, int extra) throws Exception {
            Client client = new Client(clientId, packageName, usage, content);
            if (d.connected && d.h.fixture.matches(packageName, usage, content, 1)) {
                d.h.fixture.afterMediaMatch.run();
                return source(client);
            }
            for (Client previous : new ArrayList<>(g)) previous.a(301);
            g.removeIf(previous -> previous.clientId.equals(clientId));
            g.push(client);
            client.a(307);
            if (usage == 1) d.route(d.h.fixture.group(packageName), 0);
            return 17;
        }

        int source(Client client) throws Exception {
            return (Integer) d.h.fixture.call(this, "a", REQUEST, client.clientId, null, null,
                    client.usage, client.content, client.packageName, 1, 0, 0);
        }

        public int a(String clientId, Binder token, Callback callback, int usage, int content,
                String packageName, int gain, int flags, int extra) throws Exception {
            synchronized (m) {
                if (n != null && !n.clientId.equals(clientId)) n.a(301);
                n = new Client(clientId, packageName, usage, content);
                n.a(307);
                d.route(d.h.fixture.group(packageName), 1);
                return 1;
            }
        }

        public int c(Client client) throws Exception {
            return (Integer) d.h.fixture.call(this, "b", REQUEST, client.clientId, null, null,
                    client.usage, client.content, client.packageName, 1, 0, 0);
        }
    }

    public static final class Client {
        public final Callback a = null;
        final String clientId;
        final String packageName;
        final int usage;
        final int content;
        int state;

        Client(String clientId, String packageName, int usage, int content) {
            this.clientId = clientId;
            this.packageName = packageName;
            this.usage = usage;
            this.content = content;
        }

        public String l() { return packageName; }
        public int k() { return usage; }
        public void a(int state) { this.state = state; }
    }

    static final class Backend implements HookRegistry.Backend {
        final Map<Method, HookRegistry.Dispatch> installed = new ConcurrentHashMap<>();

        @Override
        public Method hook(Executable target, HookRegistry.Dispatch dispatch, Method callback) {
            installed.put((Method) target, dispatch);
            return original((Method) target);
        }

        private static Method original(Method method) {
            // Reflection from production code must enter the same hook as Binder and OEM callers.
            if (method.getDeclaringClass() == Focus.class && method.getName().equals("b")
                    && method.getParameterCount() == Focus.REQUEST.length) {
                try {
                    return Focus.class.getDeclaredMethod("requestCabinOrSource", Focus.REQUEST);
                } catch (NoSuchMethodException impossible) {
                    throw new AssertionError(impossible);
                }
            }
            return method;
        }

        @Override
        public boolean unhook(Executable target) {
            installed.remove(target);
            return true;
        }

        Object invoke(Method method, Object receiver, Object... arguments) throws Throwable {
            HookRegistry.Dispatch dispatch = installed.get(method);
            if (dispatch != null) {
                int offset = Modifier.isStatic(method.getModifiers()) ? 0 : 1;
                Object[] packed = new Object[arguments.length + offset];
                if (offset == 1) packed[0] = receiver;
                System.arraycopy(arguments, 0, packed, offset, arguments.length);
                return dispatch.onCall(packed);
            }
            method = original(method);
            method.setAccessible(true);
            try {
                return method.invoke(receiver, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }
}
