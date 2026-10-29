package ru.voyahtune.agent.runtime;

import java.util.ArrayList;
import java.util.List;

public final class SystemServerClasses implements MethodAccess.ClassResolver {
    @FunctionalInterface public interface ServiceLoaders { List<ClassLoader> find() throws Exception; }
    private final ClassLoader primary;
    private final ServiceLoaders services;
    private volatile ClassLoader resolved;

    public SystemServerClasses(ClassLoader primary, ServiceLoaders services) {
        this.primary = java.util.Objects.requireNonNull(primary);
        this.services = java.util.Objects.requireNonNull(services);
    }

    public static SystemServerClasses android(ClassLoader primary) {
        return new SystemServerClasses(primary, () -> {
            Class<?> registry = Class.forName("android.os.ServiceManager", false, primary);
            List<ClassLoader> result = new ArrayList<>();
            for (String name : new String[] {"activity", "window"}) {
                Object service = OemAccess.call(registry, "getService", name);
                // Remote BinderProxy objects cannot identify the server's defining loader.
                if (service == null || !service.getClass().getName().startsWith("com.android.server.")) continue;
                ClassLoader loader = service.getClass().getClassLoader();
                if (loader != null && !result.contains(loader)) result.add(loader);
            }
            return result;
        });
    }

    @Override public Class<?> find(String name) throws ClassNotFoundException {
        try { return load(primary, name); }
        catch (ClassNotFoundException absent) {
            if (!name.contains("com.android.server.")) throw absent;
            ClassLoader cached = resolved;
            if (cached != null) {
                try { return load(cached, name); }
                catch (ClassNotFoundException missing) { absent.addSuppressed(missing); }
            }
            try {
                for (ClassLoader loader : services.find()) {
                    try {
                        Class<?> type = load(loader, name);
                        resolved = loader;
                        return type;
                    } catch (ClassNotFoundException missing) { absent.addSuppressed(missing); }
                }
            } catch (Exception unavailable) { absent.addSuppressed(unavailable); }
            throw absent;
        }
    }

    private static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return name.startsWith("[") ? Class.forName(name, false, loader) : loader.loadClass(name);
    }
}
