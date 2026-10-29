package ru.voyahtune.contracts;

import java.lang.reflect.Method;
import ru.voyahtune.agent.runtime.ModuleRuntime;

public final class ClientGeometryModuleTest {
    public static void main(String[] arguments) throws Throwable {
        verify();
        System.out.println("ClientGeometryModuleTest: passed");
    }

    static void verify() throws Throwable {
        try (ClientGeometryContractDriver.Harness harness =
                 new ClientGeometryContractDriver.Harness("org.app")) {
            require(harness.runtime.snapshot().phase == ModuleRuntime.Phase.STARTING,
                "Client ready before UI replay");
            harness.fullscreen = true;
            harness.flush();
            require(harness.runtime.snapshot().phase == ModuleRuntime.Phase.READY,
                "Client replay did not complete");
            ClientGeometryContractDriver.View fresh =
                new ClientGeometryContractDriver.View(new ClientGeometryContractDriver.Attributes(640, 1));
            harness.extra.add(fresh);
            ClientGeometryContractDriver.Attributes source = fresh.attributes;
            Method setView = fresh.root.getClass().getDeclaredMethod("setView",
                ClientGeometryContractDriver.View.class, ClientGeometryContractDriver.Attributes.class,
                ClientGeometryContractDriver.View.class, int.class);
            harness.backend.invoke(setView, fresh.root, fresh, source, null, 0);
            require(
                source.width == 640 && fresh.root.attributes != source && fresh.root.attributes.width == -1,
                "Client hook mutated caller params or failed to copy");
            harness.flush();
            require(fresh.attributes.width == -1 && harness.dialog.attributes.width == 400,
                "DecorView replay missing or dialog changed");
            ClientGeometryContractDriver.View virtual = new ClientGeometryContractDriver.View(
                new ClientGeometryContractDriver.Attributes(800, 1));
            virtual.root.display = 7;
            harness.extra.add(virtual);
            harness.backend.invoke(setView, virtual.root, virtual, virtual.attributes, null, 0);
            harness.flush();
            require(virtual.attributes.width == 800 && virtual.root.attributes.width == 800,
                "VirtualDisplay measured as physical window");
            harness.runtime.close();
            require(fresh.attributes.width == 640 && fresh.root.attributes.width == 640
                    && harness.view.attributes.width == 1780 && harness.backend.installed.isEmpty()
                    && harness.closedObservers == 1,
                "Client cleanup left window/hooks/receiver");
            harness.changed.run();
            harness.flush();
            require(fresh.attributes.width == 640 && harness.main.isEmpty(),
                "Closed client reacted to broadcast");
        }
        try (ClientGeometryContractDriver.Harness harness =
                 new ClientGeometryContractDriver.Harness("ru.yandex.yandexmaps")) {
            harness.backend.accept = target -> {
                require(
                    !harness.mainExecuting, "Hook preparation blocked the main thread: " + target.getName());
                return true;
            };
            harness.dpi = 240;
            harness.flush();
            require(harness.map.scale == 1.5f, "Late MapView not discovered");
            Method setter = harness.map.getClass().getDeclaredMethod("setScaleFactor", float.class);
            harness.backend.invoke(setter, harness.map, 3f);
            require(harness.map.scale == 1.5f, "OEM renderer reset bypassed density guard");
            ClientGeometryContractDriver.Binding binding = new ClientGeometryContractDriver.Binding();
            for (Method factory : new Method[] {binding.getClass().getDeclaredMethod("createMapWindow"),
                     binding.getClass().getDeclaredMethod("createMapWindow", int.class)}) {
                Object created = factory.getParameterCount() == 0
                    ? harness.backend.invoke(factory, binding)
                    : harness.backend.invoke(factory, binding, 42);
                require(((ClientGeometryContractDriver.MapWindow) created).scale == 1.5f,
                    "MapKit factory overload not intercepted");
            }
            ClientGeometryContractDriver.MapWindow second = new ClientGeometryContractDriver.MapWindow();
            second.scale = 1.25f;
            harness.extra.add(new ClientGeometryContractDriver.MapView(second));
            harness.changed.run();
            harness.flush();
            require(second.scale == 1.5f, "New MapView not replayed");
            harness.dpi = 0;
            harness.changed.run();
            harness.flush();
            require(harness.map.scale == 1f && second.scale == 1.25f, "Per-window baseline not restored");
            harness.backend.invoke(setter, harness.map, 1.75f);
            harness.dpi = 320;
            harness.changed.run();
            harness.flush();
            require(harness.map.scale == 2f && second.scale == 2f,
                "Changed density not applied to known windows");
            harness.runtime.close();
            require(harness.map.scale == 1.75f && second.scale == 1.25f && harness.map.width == 1920
                    && harness.map.height == 720 && harness.backend.installed.isEmpty(),
                "MapKit cleanup changed Surface or lost latest baseline");
        }
        try (ClientGeometryContractDriver.Harness harness =
                 new ClientGeometryContractDriver.Harness("ru.yandex.yandexnavi")) {
            harness.dpi = 240;
            harness.scheduler.advance(0);
            harness.main.remove(0).run();
            require(harness.runtime.snapshot().phase == ModuleRuntime.Phase.STARTING,
                "Client became ready before renderer preparation");
            harness.runtime.apply(
                "code", 2, java.util.List.of("client.ru.yandex.yandexnavi"), java.util.Map.of());
            harness.flush();
            require(
                harness.runtime.snapshot().phase == ModuleRuntime.Phase.READY && harness.map.scale == 1.5f,
                "Reconfiguration lost pending renderer preparation");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}
