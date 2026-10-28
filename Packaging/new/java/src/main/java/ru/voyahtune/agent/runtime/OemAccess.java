package ru.voyahtune.agent.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class OemAccess {
    private static final Map<Class<?>, Class<?>> BOXED = Map.of(
            boolean.class, Boolean.class, byte.class, Byte.class, short.class, Short.class,
            char.class, Character.class, int.class, Integer.class, long.class, Long.class,
            float.class, Float.class, double.class, Double.class);

    private OemAccess() {}

    public static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException absent) {
                for (Field field : ReflectionAccess.allFields(current)) {
                    if (field.getName().equals(name)) { field.setAccessible(true); return field; }
                }
            }
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }

    public static Object read(Object owner, String name) throws ReflectiveOperationException {
        return field(type(owner), name).get(owner instanceof Class<?> ? null : owner);
    }

    public static void write(Object owner, String name, Object value) throws ReflectiveOperationException {
        field(type(owner), name).set(owner instanceof Class<?> ? null : owner, value);
    }

    public static Object call(Object receiver, String name, Object... arguments) throws Exception {
        Method selected;
        try { selected = select(methods(receiver, name, false), arguments); }
        catch (NoSuchMethodException absent) { selected = select(methods(receiver, name, true), arguments); }
        selected.setAccessible(true);
        try {
            return selected.invoke(receiver instanceof Class<?> ? null : receiver, arguments);
        } catch (InvocationTargetException failure) {
            throw original(failure);
        }
    }

    private static List<Method> methods(Object receiver, String name, boolean includeHidden) {
        List<Method> methods = new ArrayList<>();
        for (Class<?> current = type(receiver); current != null; current = current.getSuperclass()) {
            for (Method method : includeHidden ? ReflectionAccess.allMethods(current) : ReflectionAccess.methods(current)) {
                if (!method.getName().equals(name) || method.isBridge()
                        || receiver instanceof Class<?> && !Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                boolean inherited = methods.stream().anyMatch(candidate ->
                        Arrays.equals(candidate.getParameterTypes(), method.getParameterTypes()));
                if (!inherited) {
                    methods.add(method);
                }
            }
        }
        return methods;
    }

    public static Object construct(Class<?> owner, Object... arguments) throws Exception {
        Constructor<?> selected;
        try { selected = select(Arrays.asList(owner.getDeclaredConstructors()), arguments); }
        catch (NoSuchMethodException absent) { selected = select(Arrays.asList(ReflectionAccess.allConstructors(owner)), arguments); }
        selected.setAccessible(true);
        try {
            return selected.newInstance(arguments);
        } catch (InvocationTargetException failure) {
            throw original(failure);
        }
    }

    private static Class<?> type(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("OEM receiver is null");
        }
        return value instanceof Class<?> ? (Class<?>) value : value.getClass();
    }

    private static <T extends Executable> T select(List<T> candidates, Object[] arguments) throws NoSuchMethodException {
        List<T> compatible = new ArrayList<>();
        for (T candidate : candidates) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length != arguments.length) {
                continue;
            }
            boolean accepts = true;
            for (int index = 0; index < parameters.length; index++) {
                Object value = arguments[index];
                accepts &= value == null ? !parameters[index].isPrimitive() : boxed(parameters[index]).isInstance(value);
            }
            if (accepts) {
                compatible.add(candidate);
            }
        }
        for (T candidate : compatible) {
            boolean mostSpecific = true;
            for (T other : compatible) {
                if (candidate == other) {
                    continue;
                }
                Class<?>[] parameters = candidate.getParameterTypes();
                Class<?>[] otherParameters = other.getParameterTypes();
                boolean stricter = false;
                for (int index = 0; index < parameters.length; index++) {
                    Class<?> ownType = boxed(parameters[index]);
                    Class<?> otherType = boxed(otherParameters[index]);
                    mostSpecific &= otherType.isAssignableFrom(ownType);
                    stricter |= !ownType.equals(otherType);
                }
                mostSpecific &= stricter;
            }
            if (mostSpecific) {
                return candidate;
            }
        }
        throw new NoSuchMethodException("OEM call has no unambiguous compatible signature");
    }

    private static Class<?> boxed(Class<?> type) {
        return BOXED.getOrDefault(type, type);
    }

    private static Exception original(InvocationTargetException failure) throws Error {
        Throwable cause = failure.getCause();
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return cause instanceof Exception ? (Exception) cause : new ReflectiveOperationException(cause);
    }
}
