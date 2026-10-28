package ru.voyahtune.contracts;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Executable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import ru.voyahtune.agent.HookBridge;
import ru.voyahtune.agent.HookRegistry;

final class FixtureHookBackend implements HookRegistry.Backend {
    final Map<Method, HookRegistry.Dispatch> installed = new LinkedHashMap<>();
    final Map<Constructor<?>, Method> constructorBackups = new LinkedHashMap<>();
    final Map<Constructor<?>, HookRegistry.Dispatch> constructors = new LinkedHashMap<>();
    final boolean nativeHooks;
    Predicate<Method> accept = target -> true;

    FixtureHookBackend(boolean nativeHooks) {
        this.nativeHooks = nativeHooks;
    }

    @Override
    public Method hook(Executable executable, HookRegistry.Dispatch dispatch, Method callbackMethod) {
        if (executable instanceof Constructor<?>) {
            Constructor<?> constructor = (Constructor<?>) executable;
            Method backup = nativeHooks ? HookBridge.hook(constructor, dispatch, callbackMethod) : constructorBackups.get(constructor);
            if (backup != null) constructors.put(constructor, dispatch);
            return backup;
        }
        Method target = (Method) executable;
        if (!accept.test(target)) {
            return null;
        }
        Method backup = nativeHooks ? HookBridge.hook(target, dispatch, callbackMethod) : target;
        if (backup != null) {
            installed.put(target, dispatch);
        }
        return backup;
    }

    @Override
    public boolean unhook(Executable executable) {
        if (executable instanceof Constructor<?>) {
            boolean removed = !nativeHooks || HookBridge.unhook(executable);
            if (removed) constructors.remove(executable);
            return removed;
        }
        Method target = (Method) executable;
        boolean removed = !nativeHooks || HookBridge.unhook(target);
        if (removed) {
            installed.remove(target);
        }
        return removed;
    }

    Object invoke(Method target, Object receiver, Object... arguments) throws Throwable {
        HookRegistry.Dispatch dispatch = installed.get(target);
        if (dispatch != null && !nativeHooks) {
            int offset = Modifier.isStatic(target.getModifiers()) ? 0 : 1;
            Object[] packed = new Object[arguments.length + offset];
            if (offset != 0) {
                packed[0] = receiver;
            }
            System.arraycopy(arguments, 0, packed, offset, arguments.length);
            return dispatch.onCall(packed);
        }
        try {
            target.setAccessible(true);
            return target.invoke(receiver, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
