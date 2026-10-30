package ru.voyahtune.agent.modules;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;

public final class ClientInputMethodModule implements ModuleRuntime.Module {
    public interface Platform {
        List<?> roots() throws Exception;
        Object managerForView(Object view) throws Exception;
        void error(Exception failure);
    }

    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final Platform platform;
    private Method hide;
    private Method fallback;
    private final AtomicBoolean warned = new AtomicBoolean();

    public ClientInputMethodModule(MethodAccess methods, HookRegistry hooks, Platform platform) {
        this.methods = methods;
        this.hooks = hooks;
        this.platform = platform;
    }

    @Override
    public void prepare(Map<String, String> configuration) throws Exception {
        hide = methods.require("android.view.inputmethod.InputMethodManager", "hideSoftInputFromWindow",
                "android.os.IBinder", "int", "android.os.ResultReceiver");
        fallback = methods.require("android.view.inputmethod.InputMethodManager",
                "getFallbackInputMethodManagerIfNecessary", "android.view.View");
    }

    @Override
    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        scope.own(hooks.install("client-ime", hide, scope.callback(invocation -> {
            Object target = managerForToken(invocation.arguments()[0]);
            if (target == null || target == invocation.receiver()) {
                return invocation.proceed();
            }
            // Dispatch through the owning IMM so its local state and server checks stay consistent.
            invocation.disableFallback();
            return OemAccess.call(target, "hideSoftInputFromWindow", invocation.arguments());
        })));
        scope.own(hooks.install("client-ime", fallback, scope.callback(invocation -> {
            Object target = managerForView(invocation.arguments()[0]);
            if (target == null) {
                return invocation.proceed();
            }
            return target == invocation.receiver() ? null : target;
        })));
    }

    private Object managerForToken(Object token) {
        if (token == null) {
            return null;
        }
        try {
            for (Object view : platform.roots()) {
                if (token == OemAccess.call(view, "getWindowToken")) {
                    return platform.managerForView(view);
                }
            }
        } catch (Exception unavailable) {
            error(unavailable);
        }
        return null;
    }

    private Object managerForView(Object view) {
        try {
            return view == null ? null : platform.managerForView(view);
        } catch (Exception unavailable) {
            error(unavailable);
            return null;
        }
    }

    private void error(Exception failure) {
        if (warned.compareAndSet(false, true)) {
            try {
                platform.error(failure);
            } catch (RuntimeException ignored) {
                // Diagnostics must not block stock input handling.
            }
        }
    }

    @Override
    public void configure(ModuleScope scope, Map<String, String> configuration) {}
}
