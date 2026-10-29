package ru.voyahtune.contracts;

import ru.voyahtune.agent.runtime.OemAccess;

public final class AndroidOemAccessTest {
    public static void main(String[] arguments) {
        try {
            verify(arguments);
        } catch (Throwable failure) {
            failure.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void verify(String[] arguments) throws Exception {
        NativeTestBootstrap.load(arguments[0]);
        try {
            Class.forName(AndroidOemAccessTest.class.getName() + "$MissingDependency");
            throw new AssertionError("Exclude MissingDependency from the test DEX");
        } catch (ClassNotFoundException expected) {
        }
        Service service = new Service();
        require("station".equals(OemAccess.call(service, "play", "station")), "Available OEM call failed");
        require(service.calls == 1, "OEM side effect was skipped or repeated");
        require("next".equals(OemAccess.call(service, "play", "next")), "Repeated OEM call failed");
        require(service.calls == 2, "Repeated OEM side effect was skipped or repeated");
        try {
            OemAccess.call(service, "absent");
            throw new AssertionError("Missing OEM method was accepted");
        } catch (NoSuchMethodException expected) {
        }
        System.out.println("AndroidOemAccessTest: unrelated missing signature does not block available OEM calls");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static final class MissingDependency {}

    public static final class Service {
        int calls;

        public String play(String station) {
            calls++;
            return station;
        }

        public void unrelated(MissingDependency listener) {}
    }
}
