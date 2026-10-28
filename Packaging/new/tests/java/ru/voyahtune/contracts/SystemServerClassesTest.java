package ru.voyahtune.contracts;

import java.util.List;
import ru.voyahtune.agent.runtime.SystemServerClasses;

public final class SystemServerClassesTest {
    public static void main(String[] arguments) throws Exception {
        verify();
        System.out.println("SystemServerClassesTest: passed");
    }

    static void verify() throws Exception {
        int[] lookups = {0};
        ClassLoader brokenMain = new ClassLoader(SystemServerClassesTest.class.getClassLoader()) {
            @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.startsWith("com.android.server.")) throw new ClassNotFoundException("Empty OEM main path");
                return super.loadClass(name);
            }
        };
        ClassLoader actualService = new ClassLoader(brokenMain) {
            @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.equals("com.android.server.TestService")) return Fixture.class;
                return super.loadClass(name);
            }
        };
        SystemServerClasses resolver = new SystemServerClasses(brokenMain, () -> { lookups[0]++; return List.of(actualService); });
        require(resolver.find("java.lang.String") == String.class && lookups[0] == 0, "Ordinary class lookup consulted service registry");
        require(resolver.find("[I") == int[].class && lookups[0] == 0, "Array parameter type lookup changed");
        require(resolver.find("com.android.server.TestService") == Fixture.class, "Incorrect main loader prevented real service loader lookup");
        require(resolver.find("com.android.server.TestService") == Fixture.class && lookups[0] == 1, "Validated service loader was not retained");
        Throwable failure = null;
        try { resolver.find("com.android.server.MissingService"); }
        catch (ClassNotFoundException caught) { failure = caught; }
        require(failure != null, "Missing service class was fabricated");
    }

    static final class Fixture {}
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
