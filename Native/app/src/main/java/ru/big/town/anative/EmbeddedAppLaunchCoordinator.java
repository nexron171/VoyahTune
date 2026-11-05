package ru.big.town.anative;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Main-thread ownership of embedded windows, including asynchronous split teardown. */
final class EmbeddedAppLaunchCoordinator {
    private static final class Window {
        final Object owner;
        final String packageName;
        final int displayId;
        final Consumer<Runnable> close;

        Window(Object owner, String packageName, int displayId, Consumer<Runnable> close) {
            this.owner = owner;
            this.packageName = packageName;
            this.displayId = displayId;
            this.close = close;
        }
    }

    private final Map<Object, Window> windows = new LinkedHashMap<>();

    void register(Object owner, String packageName, int displayId, Consumer<Runnable> close) {
        windows.put(owner, new Window(owner, packageName, displayId, close));
    }

    void remove(Object owner) {
        windows.remove(owner);
    }

    Set<String> packages() {
        Set<String> packages = new LinkedHashSet<>();
        for (Window window : windows.values()) packages.add(window.packageName);
        return packages;
    }

    void prepare(String packageName, int displayId, BooleanSupplier valid, Runnable launch) {
        List<Window> conflicts = new ArrayList<>();
        for (Window window : windows.values()) {
            if (window.packageName.equals(packageName) && window.displayId != displayId) {
                conflicts.add(window);
            }
        }
        closeNext(conflicts, 0, valid, launch);
    }

    private void closeNext(
            List<Window> conflicts, int index, BooleanSupplier valid, Runnable launch) {
        if (!valid.getAsBoolean()) return;
        if (index == conflicts.size()) {
            launch.run();
            return;
        }
        Window window = conflicts.get(index);
        Runnable next = () -> closeNext(conflicts, index + 1, valid, launch);
        // Closing a split can remove both panes; a newer window must not inherit an old close.
        if (windows.get(window.owner) != window) next.run();
        else window.close.accept(next);
    }
}
