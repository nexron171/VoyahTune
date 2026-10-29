package ru.voyahtune.agent.runtime;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MethodAccess {
    @FunctionalInterface
    public interface ClassResolver {
        Class<?> find(String name) throws ClassNotFoundException;
    }

    private static final Map<String, Class<?>> PRIMITIVES =
        Map.of("boolean", boolean.class, "byte", byte.class, "short", short.class, "char", char.class, "int", int.class,
            "long", long.class, "float", float.class, "double", double.class, "void", void.class);
    private final ClassResolver resolver;

    public MethodAccess(ClassResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver);
    }

    public Class<?> type(String name) throws ClassNotFoundException {
        Class<?> primitive = PRIMITIVES.get(name);
        return primitive == null ? resolver.find(name) : primitive;
    }

    public Method require(String owner, String name, String... parameterNames) throws ReflectiveOperationException {
        Method method = optional(owner, name, parameterNames);
        if (method == null) {
            throw new NoSuchMethodException(owner + "." + name);
        }
        return method;
    }

    public List<Method> overloads(String owner, String name) throws ClassNotFoundException {
        Map<List<Class<?>>, Method> found = new LinkedHashMap<>();
        for (Class<?> current = type(owner); current != null; current = current.getSuperclass()) {
            Method[] declared = Arrays.stream(ReflectionAccess.allMethods(current))
                                    .filter(method -> method.getName().equals(name) && !method.isBridge())
                                    .toArray(Method[] ::new);
            Arrays.sort(declared, Comparator.comparing(Method::toGenericString));
            for (Method method : declared) {
                found.putIfAbsent(Arrays.asList(method.getParameterTypes()), method);
            }
        }
        return List.copyOf(found.values());
    }

    public Method optional(String owner, String name, String... parameterNames) throws ClassNotFoundException {
        Class<?>[] parameters = new Class<?>[ parameterNames.length ];
        for (int index = 0; index < parameters.length; index++) {
            parameters[index] = type(parameterNames[index]);
        }
        for (Class<?> current = type(owner); current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name, parameters);
            } catch (NoSuchMethodException absent) {
                for (Method candidate : ReflectionAccess.allMethods(current)) {
                    if (candidate.getName().equals(name) && Arrays.equals(candidate.getParameterTypes(), parameters))
                        return candidate;
                }
            }
        }
        return null;
    }
}
