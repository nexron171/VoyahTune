package ru.voyahtune.agent.runtime;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import ru.voyahtune.agent.HookBridge;

public final class AndroidCallers {
    private final ClassLoader applicationLoader;
    private DexCallGraph graph = new DexCallGraph();
    private final Set<ClassLoader> loaders = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> files = new LinkedHashSet<>();
    private final Set<Executable> deoptimized = new LinkedHashSet<>();
    private final Set<Class<?>> deoptimizedInitializers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Executable, PreparedCallers> prepared = new HashMap<>();

    private static final class PreparedCallers {
        final List<Executable> methods = new ArrayList<>();
        final Set<Class<?>> initializers = Collections.newSetFromMap(new IdentityHashMap<>());
    }
    private static final class CallerClass {
        final DexCallGraph.Reference method;
        final Class<?> owner;
        CallerClass(DexCallGraph.Reference method, Class<?> owner) {
            this.method = method;
            this.owner = owner;
        }
    }

    private final Map<String, Set<Class<?>>> resolvedClasses = new HashMap<>();
    private boolean bootRead;

    public AndroidCallers(ClassLoader applicationLoader) {
        this.applicationLoader = java.util.Objects.requireNonNull(applicationLoader);
    }

    public synchronized void prepare(Executable target) throws Exception {
        ClassLoader definingLoader = target.getDeclaringClass().getClassLoader();
        // Application JNI methods have no inlineable DEX body and cannot be boot intrinsics.
        if (Modifier.isNative(target.getModifiers()) && definingLoader != null
            && definingLoader != String.class.getClassLoader()) {
            prepared.put(target, new PreparedCallers());
            return;
        }
        Set<ClassLoader> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        inventory(applicationLoader, visited);
        inventory(target.getDeclaringClass().getClassLoader(), visited);
        ClassLoader ownerLoader = target.getDeclaringClass().getClassLoader();
        if (!bootRead && (ownerLoader == null || ownerLoader == String.class.getClassLoader())) {
            bootRead = true;
            String paths = System.getProperty("java.boot.class.path", "");
            if (paths.isEmpty())
                paths = System.getenv("BOOTCLASSPATH");
            if (paths != null)
                for (String path : paths.split(":"))
                    source(path);
        }
        PreparedCallers result = new PreparedCallers();
        List<CallerClass> resolved = new ArrayList<>();
        Set<Class<?>> methodOwners = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Class<?>> constructorOwners = Collections.newSetFromMap(new IdentityHashMap<>());
        for (DexCallGraph.Reference caller : graph.callers(reference(target))) {
            for (Class<?> type : resolve(caller.owner)) {
                if (caller.name.equals("<clinit>")) {
                    if (!deoptimizedInitializers.contains(type))
                        result.initializers.add(type);
                    continue;
                }
                resolved.add(new CallerClass(caller, type));
                if (caller.name.equals("<init>"))
                    constructorOwners.add(type);
                else
                    methodOwners.add(type);
            }
        }
        ReflectionAccess.prepareMembers(methodOwners, constructorOwners);
        for (CallerClass caller : resolved) {
            for (Executable candidate : members(caller.owner, caller.method.name)) {
                if ((candidate instanceof Constructor<?> || candidate.getName().equals(caller.method.name))
                    && matches(candidate, caller.method) && !Modifier.isAbstract(candidate.getModifiers())
                    && !Modifier.isNative(candidate.getModifiers()) && !deoptimized.contains(candidate))
                    result.methods.add(candidate);
            }
        }
        prepared.put(target, result);
    }

    public synchronized void installed(Executable target) {
        PreparedCallers callers = prepared.remove(target);
        if (callers == null)
            throw new IllegalStateException("Caller coverage was not prepared");
        for (Executable caller : callers.methods) {
            if (!HookBridge.deoptimize(caller))
                throw new IllegalStateException("Compiled caller could not be invalidated: " + caller);
            deoptimized.add(caller);
        }
        for (Class<?> owner : callers.initializers) {
            if (!HookBridge.deoptimizeClassInitializer(owner))
                throw new IllegalStateException("Static initializer could not be invalidated: " + owner.getName());
            deoptimizedInitializers.add(owner);
        }
    }

    public synchronized void releaseIndex() {
        // Pending coverage owns its resolved members and survives index eviction.
        graph = new DexCallGraph();
        files.clear();
        loaders.clear();
        resolvedClasses.clear();
        bootRead = false;
    }

    private boolean matches(Executable method, DexCallGraph.Reference caller) {
        try {
            return graph.matchesDefinition(caller, HookBridge.dexMethodIndex(method));
        } catch (UnsatisfiedLinkError hostRuntime) {
            return reference(method).key().equals(caller.key());
        }
    }

    private static Executable[] members(Class<?> type, String name) {
        if (name.equals("<init>"))
            return ReflectionAccess.allConstructors(type);
        return ReflectionAccess.allMethods(type);
    }

    private Set<Class<?>> resolve(String descriptor) {
        Set<Class<?>> cached = resolvedClasses.get(descriptor);
        if (cached != null)
            return cached;
        String name = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        Set<Class<?>> result = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ClassLoader loader : loaders) {
            try {
                result.add(Class.forName(name, false, loader));
            } catch (ClassNotFoundException | LinkageError absent) {
                // A caller with unresolved dependencies cannot contain compiled code in this loader.
            }
        }
        resolvedClasses.put(descriptor, result);
        return result;
    }

    private void inventory(ClassLoader loader, Set<ClassLoader> visited) throws Exception {
        if (loader == null || !visited.add(loader))
            return;
        if (loaders.add(loader))
            resolvedClasses.clear();
        if (loaders.size() > 32)
            throw new IllegalStateException("Caller classloader limit exceeded");
        inventory(loader.getParent(), visited);
        Object shared = optional(loader, "sharedLibraryLoaders");
        if (shared instanceof ClassLoader[])
            for (ClassLoader dependency : (ClassLoader[]) shared)
                inventory(dependency, visited);
        Object path = optional(loader, "pathList");
        Object elements = path == null ? null : optional(path, "dexElements");
        if (!(elements instanceof Object[]))
            return;
        for (Object element : (Object[]) elements) {
            Object dex = optional(element, "dexFile");
            if (dex != null) {
                Object name = OemAccess.call(dex, "getName");
                if (name instanceof String)
                    source((String) name);
            }
        }
    }

    private void source(String name) throws Exception {
        File file = new File(name);
        if (!file.isFile())
            return;
        String path = file.getCanonicalPath();
        if (!files.add(path))
            return;
        resolvedClasses.clear();
        if (files.size() > 128)
            throw new IllegalStateException("Caller DEX source limit exceeded");
        if (name.endsWith(".dex")) {
            if (file.length() > 64 * 1024 * 1024)
                throw new IllegalStateException("Caller DEX exceeds size limit");
            graph.add(Files.readAllBytes(file.toPath()));
            return;
        }
        if (!name.endsWith(".apk") && !name.endsWith(".jar") && !name.endsWith(".zip"))
            return;
        try (ZipFile archive = new ZipFile(file)) {
            var entries = archive.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes[0-9]*\\.dex"))
                    continue;
                if (++count > 64)
                    throw new IllegalStateException("Caller multidex limit exceeded");
                try (InputStream input = archive.getInputStream(entry);
                     ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if ((long) bytes.size() + read > 64 * 1024 * 1024)
                            throw new IllegalStateException("Caller DEX exceeds size limit");
                        bytes.write(buffer, 0, read);
                    }
                    graph.add(bytes.toByteArray());
                }
            }
        }
    }

    private static Object optional(Object owner, String name) throws Exception {
        try {
            return OemAccess.read(owner, name);
        } catch (NoSuchFieldException absent) {
            return null;
        }
    }

    private static DexCallGraph.Reference reference(Executable method) {
        StringBuilder prototype = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes())
            prototype.append(descriptor(parameter));
        prototype.append(')').append(method instanceof Method ? descriptor(((Method) method).getReturnType()) : "V");
        return new DexCallGraph.Reference(descriptor(method.getDeclaringClass()),
            method instanceof Constructor<?> ? "<init>" : method.getName(), prototype.toString());
    }

    private static String descriptor(Class<?> type) {
        if (type.isArray())
            return type.getName().replace('.', '/');
        if (!type.isPrimitive())
            return "L" + type.getName().replace('.', '/') + ";";
        switch (type.getName()) {
            case "void":
                return "V";
            case "boolean":
                return "Z";
            case "byte":
                return "B";
            case "char":
                return "C";
            case "short":
                return "S";
            case "int":
                return "I";
            case "long":
                return "J";
            case "float":
                return "F";
            case "double":
                return "D";
            default:
                throw new IllegalArgumentException("Unknown primitive type");
        }
    }
}
