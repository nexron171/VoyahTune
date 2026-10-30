package ru.voyahtune.contracts;

import ru.voyahtune.agent.modules.AndroidClientInputMethodPlatform;
import ru.voyahtune.agent.runtime.MethodAccess;

public final class ClientInputMethodPlatformTest {
    public static void main(String[] arguments) throws Exception {
        AndroidClientInputMethodPlatform platform = new AndroidClientInputMethodPlatform(
                new MethodAccess(Class::forName));
        View view = new View();
        require(platform.managerForView(view) == view.context.manager,
                "Attached Native display did not resolve its own manager");
        require(view.context.selected == view.display, "Stale activity context was reused");
        view.root.id = 8;
        require(platform.managerForView(view) == null, "Mismatched root/display was accepted");
        view.display.id = 8;
        require(platform.managerForView(view) == null, "Wrong manager display was accepted");
        view.context.manager.id = 8;
        require(platform.managerForView(view) == view.context.manager, "Reparented window was not resolved");
        view.display.owner = "foreign.app";
        require(platform.managerForView(view) == null, "Foreign display routing changed");
        view.display.owner = "ru.big.town.anative";
        view.display.type = 1;
        require(platform.managerForView(view) == null, "Physical display routing changed");
        view.display.type = 5;
        view.display.valid = false;
        require(platform.managerForView(view) == null, "Released display was accepted");
        view.display.valid = true;
        view.root = null;
        require(platform.managerForView(view) == null, "Detached view was accepted");
        view.display = null;
        require(platform.managerForView(view) == null, "Missing display was accepted");
        System.out.println("ClientInputMethodPlatformTest: passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static final class View {
        Root root = new Root();
        Display display = new Display();
        final Context context = new Context();

        public Root getViewRootImpl() {
            return root;
        }

        public Display getDisplay() {
            return display;
        }

        public Context getContext() {
            return context;
        }
    }

    public static final class Root {
        int id = 7;

        public int getDisplayId() {
            return id;
        }
    }

    public static final class Display {
        int id = 7;
        int type = 5;
        String owner = "ru.big.town.anative";
        boolean valid = true;

        public int getDisplayId() {
            return id;
        }

        public int getType() {
            return type;
        }

        public String getOwnerPackageName() {
            return owner;
        }

        public boolean isValid() {
            return valid;
        }
    }

    public static final class Context {
        final Manager manager = new Manager();
        Display selected;

        public Context createDisplayContext(Display display) {
            selected = display;
            return this;
        }

        public Manager getSystemService(String name) {
            require("input_method".equals(name), "Wrong context service requested");
            require(selected != null, "Manager requested without a display context");
            return manager;
        }
    }

    public static final class Manager {
        int id = 7;

        public int getDisplayId() {
            return id;
        }
    }
}
