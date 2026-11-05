package ru.voyahtune.agent.policy;

public final class LauncherCommand {
    public enum Kind {
        DASHBOARD,
        TASK_MANAGER,
        STOCK_HOME,
        PASSENGER_ALL_APPS,
        FREEFORM,
        FULLSCREEN,
        DOCK_SPLIT,
        DOCK_LONG_PRESS,
        APP,
        EMBEDDED
    }

    public final Kind kind;
    public final int display;
    public final String target;
    public final int slot;

    public LauncherCommand(Kind kind, int display, String target, int slot) {
        if (display != 0 && display != 1)
            throw new IllegalArgumentException("Physical launcher display required");
        if ((kind == Kind.DOCK_SPLIT || kind == Kind.DOCK_LONG_PRESS) && slot != 1 && slot != 2)
            throw new IllegalArgumentException("Dock slot required");
        this.kind = java.util.Objects.requireNonNull(kind);
        this.display = display;
        this.target = target == null ? "" : target;
        this.slot = slot;
    }
}
