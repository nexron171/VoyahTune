package ru.voyahtune.contracts;

import com.google.gson.JsonObject;

public final class VehicleAirModuleTest {
    public static void main(String[] arguments) throws Throwable {
        verify();
        System.out.println("VehicleAirModuleTest: passed");
    }

    static void verify() throws Throwable {
        try (VehicleAirContractDriver.Harness harness = new VehicleAirContractDriver.Harness(new JsonObject())) {
            harness.menu(1, 2);
            require(harness.saved.isEmpty() && harness.bus.sends == 2, "Non-user message was persisted");
            harness.menu(3, 6);
            require(harness.saved.size() == 1 && harness.saved.get(0).getAsString().equals("SNOW") && harness.bus.sends == 4,
                    "User menu was not persisted after IPC acceptance");
            harness.sendMode(3);
            require(harness.saved.size() == 1 && harness.bus.sends == 6, "User scope leaked after the menu callback");
            harness.menu(3, 0);
            harness.menu(3, 7);
            require(harness.saved.size() == 1, "Invalid OEM mode was persisted");
            RuntimeException original = new IllegalStateException("owned-binder-failure");
            harness.bus.failure = original;
            try {
                harness.menu(3, 2);
                throw new AssertionError("Binder exception was lost");
            } catch (RuntimeException failure) {
                require(failure == original, "Binder exception identity changed");
            }
            harness.bus.failure = null;
            harness.sendMode(3);
            require(harness.saved.size() == 1, "Failed OEM callback leaked user scope");
            harness.runtime.close();
            harness.menu(3, 2);
            require(harness.saved.size() == 1 && harness.backend.installed.isEmpty(), "Disabled module still persisted drive changes");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
