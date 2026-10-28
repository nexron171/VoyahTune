package ru.voyahtune.agent.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import ru.voyahtune.agent.HookBridge;

public final class ReflectionAccess {
    private static final ConcurrentHashMap<Class<?>, Method[]> METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Field[]> FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Constructor<?>[]> CONSTRUCTORS = new ConcurrentHashMap<>();

    private ReflectionAccess() {
    }

    public static void prepareMembers(Set<Class<?>> methodOwners, Set<Class<?>> constructorOwners) {
        prepareMembers(methodOwners, 0);
        prepareMembers(constructorOwners, 2);
    }

    private static void prepareMembers(Set<Class<?>> owners, int kind) {
        Map<Class<?>, ?> cache = kind == 0 ? METHODS : CONSTRUCTORS;
        Class<?>[] missing = owners.stream().filter(owner -> !cache.containsKey(owner)).toArray(Class<?>[] ::new);
        for (int offset = 0; offset < missing.length; offset += 1024) {
            prepareMemberGroup(Arrays.copyOfRange(missing, offset, Math.min(missing.length, offset + 1024)), kind);
        }
    }

    private static void prepareMemberGroup(Class<?>[] missing, int kind) {
        Object[][] groups;
        try {
            groups = HookBridge.declaredMembersBatch(missing, kind);
        } catch (UnsatisfiedLinkError hostRuntime) {
            groups = new Object[missing.length][];
            for (int index = 0; index < missing.length; index++)
                groups[index] =
                    kind == 0 ? missing[index].getDeclaredMethods() : missing[index].getDeclaredConstructors();
        }
        if (groups == null || groups.length != missing.length)
            throw new IllegalStateException("Incomplete member batch");
        for (int index = 0; index < missing.length; index++) {
            if (kind == 0)
                METHODS.putIfAbsent(missing[index], (Method[]) groups[index]);
            else
                CONSTRUCTORS.putIfAbsent(missing[index], (Constructor<?>[]) groups[index]);
        }
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
            return CONSTRUCTORS.computeIfAbsent(owner, type -> (Constructor<?>[]) HookBridge.declaredMembers(type, 2));
        } catch (UnsatisfiedLinkError hostRuntime) {
            return owner.getDeclaredConstructors();
        }
    }
}
