package ru.voyahtune.contracts;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.ClientInputMethodModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;

public final class ClientInputMethodModuleTest {
    public static void main(String[] arguments) throws Throwable {
        try (Harness harness = new Harness()) {
            Manager cached = new Manager();
            Manager virtual = new Manager();
            Token token = new Token();
            View view = new View(token, virtual);
            harness.views.add(view);
            ResultReceiver receiver = new ResultReceiver();
            require((Boolean) harness.backend.invoke(harness.hide, cached, token, 2, receiver),
                    "Hide request did not reach the window's IME");
            require(cached.hides == 0 && virtual.hides == 1 && virtual.token == token
                            && virtual.flags == 2 && virtual.receiver == receiver,
                    "Cached physical-display IME handled virtual-window hide or lost arguments");
            require(harness.backend.invoke(harness.fallback, cached, view) == virtual,
                    "Stale view context prevented routing show/restart to the attached display");
            require(harness.backend.invoke(harness.fallback, virtual, view) == null,
                    "Correct manager was redirected to itself");
            harness.backend.invoke(harness.hide, virtual, token, 0, null);
            require(virtual.hides == 2, "Correct manager did not handle hide exactly once");

            Manager moved = new Manager();
            view.manager = moved;
            harness.backend.invoke(harness.hide, cached, token, 0, null);
            require(moved.hides == 1 && virtual.hides == 2, "Reparent retained stale display routing");
            moved.result = false;
            require(!(Boolean) harness.backend.invoke(harness.hide, cached, token, 0, null),
                    "Stock rejection was replaced with success");
            moved.failure = new IllegalStateException("IME failure");
            try {
                harness.backend.invoke(harness.hide, cached, token, 0, null);
                throw new AssertionError("IME failure suppressed");
            } catch (IllegalStateException expected) {
                require(expected == moved.failure, "IME exception changed");
            }
            require(cached.hides == 0, "Failed dispatch retried through stale manager");
            moved.failure = null;

            view.manager = null;
            harness.backend.invoke(harness.hide, cached, token, 0, null);
            require(cached.hides == 1, "Physical or foreign window did not retain stock behavior");
            require(harness.backend.invoke(harness.fallback, cached, view) == null,
                    "Foreign view acquired a replacement manager");
            view.manager = moved;
            harness.backend.invoke(harness.hide, cached, new Token(), 0, null);
            harness.backend.invoke(harness.hide, cached, null, 0, null);
            require(cached.hides == 3, "Unknown/null token redirected to unrelated window");
            harness.views.clear();
            harness.backend.invoke(harness.hide, cached, token, 0, null);
            require(cached.hides == 4, "Detached window retained routing");
            harness.views.add(view);
            harness.failLookup = true;
            harness.backend.invoke(harness.hide, cached, token, 0, null);
            require(cached.hides == 5 && harness.errors == 1, "Metadata failure blocked stock IME");
            harness.runtime.close();
            require(harness.backend.installed.isEmpty(), "Module shutdown retained IME hooks");
        }
        System.out.println("ClientInputMethodModuleTest: passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static final class Token {}
    public static final class ResultReceiver {}

    public static final class View {
        final Token token;
        Manager manager;

        View(Token token, Manager manager) {
            this.token = token;
            this.manager = manager;
        }

        public Token getWindowToken() {
            return token;
        }
    }

    public static final class Manager {
        int hides;
        int flags;
        Token token;
        ResultReceiver receiver;
        boolean result = true;
        IllegalStateException failure;

        public boolean hideSoftInputFromWindow(Token token, int flags, ResultReceiver receiver) {
            hides++;
            this.token = token;
            this.flags = flags;
            this.receiver = receiver;
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        public Manager getFallbackInputMethodManagerIfNecessary(View view) {
            return null;
        }
    }

    private static final class Harness implements AutoCloseable, ClientInputMethodModule.Platform {
        final FixtureHookBackend backend = new FixtureHookBackend(false);
        final HookRegistry hooks = new HookRegistry(backend);
        final List<View> views = new ArrayList<>();
        final ModuleRuntime runtime;
        final Method hide;
        final Method fallback;
        boolean failLookup;
        int errors;

        Harness() throws Exception {
            MethodAccess methods = new MethodAccess(name -> {
                switch (name) {
                    case "android.view.inputmethod.InputMethodManager": return Manager.class;
                    case "android.os.IBinder": return Token.class;
                    case "android.os.ResultReceiver": return ResultReceiver.class;
                    case "android.view.View": return View.class;
                    default: return Class.forName(name);
                }
            });
            hide = Manager.class.getMethod("hideSoftInputFromWindow", Token.class, int.class, ResultReceiver.class);
            fallback = Manager.class.getMethod("getFallbackInputMethodManagerIfNecessary", View.class);
            runtime = new ModuleRuntime("code", new ManualScheduler(),
                    id -> new ClientInputMethodModule(methods, hooks, this));
            runtime.apply("code", 1, List.of("client.app"), Map.of());
            require(runtime.snapshot().phase == ModuleRuntime.Phase.READY, "IME module not ready");
        }

        @Override
        public List<?> roots() throws Exception {
            if (failLookup) {
                throw new IllegalStateException("Window roots unavailable");
            }
            return views;
        }

        @Override
        public Object managerForView(Object view) {
            return view == null ? null : ((View) view).manager;
        }

        @Override
        public void error(Exception failure) {
            errors++;
        }

        @Override
        public void close() throws Exception {
            runtime.close();
            hooks.close();
        }
    }
}
