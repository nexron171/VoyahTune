package ru.voyahtune.agent.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import ru.voyahtune.agent.HookBridge;

public final class ReflectionAccess {
    private static final ConcurrentHashMap<Class<?>, Method[]> METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Field[]> FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Constructor<?>[]> CONSTRUCTORS =
            new ConcurrentHashMap<>();

    private ReflectionAccess() {}

    public static Object[][] declaredMembers(Class<?>[] missing, int kind) {
        Object[][] groups;
        try {
            groups = HookBridge.declaredMembersBatch(missing, kind);
        } catch (UnsatisfiedLinkError hostRuntime) {
            groups = new Object[missing.length][];
            for (int index = 0; index < missing.length; index++) {
                groups[index] = kind == 0 ? missing[index].getDeclaredMethods()
                                          : missing[index].getDeclaredConstructors();
            }
        }
        if (groups == null || groups.length != missing.length) {
            throw new IllegalStateException("Incomplete member batch");
        }
        return groups;
    }

    public static Method[] methods(Class<?> owner) {
        Method[] cached = METHODS.get(owner);
        return cached == null ? owner.getDeclaredMethods() : cached;
    }

    public static Method[] allMethods(Class<?> owner) {
        try {
            return METHODS.computeIfAbsent(owner, type -> (Method[]) HookBridge.declaredMembers(type, 0));
        } catch (UnsatisfiedLinkError hostRuntime) {
            return owner.getDeclaredMethods();
        }
    }

    public static Field[] allFields(Class<?> owner) {
        try {
            return FIELDS.computeIfAbsent(owner, type -> (Field[]) HookBridge.declaredMembers(type, 1));
        } catch (UnsatisfiedLinkError hostRuntime) {
            return owner.getDeclaredFields();
        }
    }

    public static Constructor<?>[] allConstructors(Class<?> owner) {
        try {
            return CONSTRUCTORS.computeIfAbsent(
                owner, type -> (Constructor<?>[]) HookBridge.declaredMembers(type, 2));
        } catch (UnsatisfiedLinkError hostRuntime) {
            return owner.getDeclaredConstructors();
        }
    }
}
