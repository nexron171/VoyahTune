package ru.voyahtune.agent.policy;

public final class LauncherNavigation {
    public enum Button { HOME, ALL_APPS }
    public enum Action { STOCK, DASHBOARD, TASK_MANAGER, OEM_HOME }
    @FunctionalInterface public interface Click { void run() throws Throwable; }

    private final boolean shared;
    private final ThreadLocal<Boolean> stock = ThreadLocal.withInitial(() -> false);

    public LauncherNavigation(boolean shared) { this.shared = shared; }

    public Action action(int display, Button button, boolean dashboardShort, boolean longPress) {
        if (display != 0 || stock.get()) return Action.STOCK;
        if (shared) {
            if (button == Button.HOME) return longPress ? Action.OEM_HOME : Action.DASHBOARD;
            return longPress ? Action.TASK_MANAGER : Action.STOCK;
        }
        return dashboardShort != longPress ? Action.DASHBOARD : Action.STOCK;
    }

    public boolean bypassing() { return stock.get(); }

    public void stock(Click click) throws Throwable {
        boolean previous = stock.get();
        stock.set(true);
        try { click.run(); }
        finally { if (previous) stock.set(true); else stock.remove(); }
    }
}
