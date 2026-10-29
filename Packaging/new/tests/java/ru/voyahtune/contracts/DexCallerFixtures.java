package ru.voyahtune.contracts;

public final class DexCallerFixtures {
    public static String leaf(String value) {
        return value + "!";
    }
    public static String middle(String value) {
        return leaf(value);
    }
    public static String outer(String value) {
        return middle(value);
    }
    public static String unrelated(String value) {
        return value.toUpperCase(java.util.Locale.ROOT);
    }
    public static String guarded(String value) {
        try {
            return leaf(value);
        } catch (RuntimeException failure) {
            return "failed";
        }
    }
    public static String outsideGuard(String value) {
        return guarded(value);
    }
    public static final class Task implements Runnable {
        @Override
        public void run() {
            outer("task");
        }
    }
    public static final class Constructed {
        public Constructed() {
            leaf("constructor");
        }
    }
    public static final class Unrelated {}
    public static Object factory() {
        return new Constructed();
    }
    public static Object unrelatedFactory() {
        return new Unrelated();
    }
    public static class Base {
        public String value() {
            return "base";
        }
        public static String shared() {
            return "shared";
        }
    }
    public static final class Child extends Base {}
    public static String inherited(Child child) {
        return child.value();
    }
    public static String inheritedStatic() {
        return Child.shared();
    }
    public static void dispatch(Runnable task) {
        task.run();
    }
}
