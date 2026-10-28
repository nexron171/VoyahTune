package ru.voyahtune.agent;

import ru.voyahtune.agent.runtime.OemAccess;

public final class OemAccessTest {
    public static void main(String[] arguments) throws Exception {
        Fixture fixture = new Fixture();
        require(OemAccess.call(fixture, "number", 4).equals("int:4"), "Exact primitive overload");
        require(OemAccess.call(fixture, "number", 4L).equals("long:4"), "Long overload");
        require(OemAccess.call(fixture, "inherit", "x").equals("x"), "Inherited private method");
        require(OemAccess.read(fixture, "value").equals(3), "Inherited private field");
        OemAccess.write(fixture, "value", 8);
        require(OemAccess.read(fixture, "value").equals(8), "Field write");
        require(OemAccess.call(Fixture.class, "staticValue").equals("static"), "Static method");
        try {
            OemAccess.call(fixture, "fail");
            throw new AssertionError("Original exception lost");
        } catch (IllegalStateException caught) {
            require(caught == fixture.failure, "Original exception identity");
        }
        try {
            OemAccess.call(fixture, "number", (Object) null);
            throw new AssertionError("Null was accepted for primitive");
        } catch (NoSuchMethodException expected) {
        }
        require(OemAccess.call(fixture, "text", "exact").equals("string"), "Most specific overload");
        System.out.println("OemAccessTest: passed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static class Parent {
        private int value = 3;
        private String inherit(String text) { return text; }
    }

    private static final class Fixture extends Parent {
        final IllegalStateException failure = new IllegalStateException("OEM failed");
        String number(int value) { return "int:" + value; }
        String number(long value) { return "long:" + value; }
        String text(Object value) { return "object"; }
        String text(String value) { return "string"; }
        static String staticValue() { return "static"; }
        void fail() { throw failure; }
    }
}
