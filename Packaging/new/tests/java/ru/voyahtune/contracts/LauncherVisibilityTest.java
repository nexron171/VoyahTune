package ru.voyahtune.contracts;

import java.util.ArrayList;
import java.util.List;
import ru.voyahtune.agent.modules.LauncherWindowVisibility;

public final class LauncherVisibilityTest {
    public static void main(String[] arguments) throws Exception { verify(); System.out.println("LauncherVisibilityTest: passed"); }
    static void verify() throws Exception {
        Bar bar = new Bar();
        LauncherWindowVisibility visibility = new LauncherWindowVisibility();
        require(visibility.hide(bar), "Compatible controller not hidden");
        require(bar.events.equals(List.of("listeners", "updates", "cancel", "layout", "remove")) && bar.mLp.x == -145
                && bar.mRootView.parent == null, "Dismiss animation was not silenced before detach");
        visibility.hide(bar);
        require(bar.mWindowManager.removes == 1, "Repeated hide removed root twice");
        visibility.close();
        require(bar.mRootView.parent != null && bar.mLp.x == 0 && bar.shows == 1, "Owned detach did not restore stock window");
        Bar failed = new Bar();
        failed.mWindowManager.failAfterRemove = true;
        visibility = new LauncherWindowVisibility();
        Throwable failure = null;
        try { visibility.hide(failed); }
        catch (Exception caught) { failure = caught; }
        require(failure != null && failed.mWindowManager.removes == 1, "Uncertain removal did not surface");
        failure = null;
        try { visibility.close(); }
        catch (Exception caught) { failure = caught; }
        require(failure != null, "Cleanup falsely confirmed a failed vendor detach");
        require(!new LauncherWindowVisibility().hide(new Object()), "Unknown ABI mutated controller");
    }
    static final class Bar {
        final List<String> events = new ArrayList<>();
        final Animator mMoveWindowAnimator = new Animator(events);
        final Root mRootView = new Root();
        final Parameters mLp = new Parameters();
        final Manager mWindowManager = new Manager(events);
        int shows;
        void show() { shows++; mRootView.parent = new Object(); mLp.x = 0; }
    }
    static final class Animator {
        final List<String> events;
        boolean started = true;
        Animator(List<String> events) { this.events = events; }
        boolean isStarted() { return started; }
        void removeAllListeners() { events.add("listeners"); }
        void removeAllUpdateListeners() { events.add("updates"); }
        void cancel() { events.add("cancel"); started = false; }
    }
    static final class Parameters { int width = 145; int x; }
    static final class Root { Object parent = new Object(); Object getParent() { return parent; } }
    static final class Manager {
        final List<String> events;
        int removes;
        boolean failAfterRemove;
        Manager(List<String> events) { this.events = events; }
        void updateViewLayout(Root root, Parameters parameters) { events.add("layout"); }
        void removeView(Root root) {
            events.add("remove");
            removes++;
            root.parent = null;
            if (failAfterRemove) throw new IllegalStateException("Vendor failed after detach");
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
