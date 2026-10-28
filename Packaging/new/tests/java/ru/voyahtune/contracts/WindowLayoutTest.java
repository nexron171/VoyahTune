package ru.voyahtune.contracts;

import java.util.Map;
import ru.voyahtune.agent.modules.WindowLayout;
import ru.voyahtune.agent.policy.WindowPolicy;

public final class WindowLayoutTest {
    public static void main(String[] arguments) throws Exception {
        verify();
        System.out.println("WindowLayoutTest: passed");
    }

    static void verify() throws Exception {
        WindowPolicy policy = WindowPolicy.read(Map.of("voyahtune_fullscreen_apps", "org.app")::get, 2);
        int[] uncertain = {0};
        WindowLayout layout = new WindowLayout(new WindowLayout.Reflection(), failure -> uncertain[0]++);
        Window window = new Window(1780, 675);
        layout.apply(window, window.displayFrames, policy);
        require(window.mRequestedWidth == 1920 && window.mRequestedHeight == 675, "Fullscreen requested size not applied");
        require(window.attrs.width == 1780 && window.attrs.height == 675 && window.displayFrames.mStable.top == 0, "Shared fields leaked");
        require(window.frames.mContentFrame.left == 0 && window.frames.mContentFrame.top == 45 && window.computes == 1, "Constrained frame missing");
        window.mRequestedWidth = 1200;
        layout.apply(window, window.displayFrames, policy);
        layout.restore(window);
        require(window.mRequestedWidth == 1200 && layout.size() == 0, "Relayout request not retained");
        Window second = new Window(800, 675);
        layout.apply(window, window.displayFrames, policy);
        layout.apply(second, second.displayFrames, policy);
        layout.restoreAll(policy, true);
        require(window.mRequestedWidth == 1200 && second.mRequestedWidth == 800, "Equal window hashes merged ownership");
        window.failCompute = true;
        int stockLeft = window.frames.mContentFrame.left;
        layout.apply(window, window.displayFrames, policy);
        require(window.mRequestedWidth == 1200 && window.attrs.width == 1780 && window.displayFrames.mStable.top == 0
                && window.frames.mContentFrame.left == stockLeft && uncertain[0] == 0, "Compute failure did not roll back fields");
        for (int display : new int[] {2, 7}) {
            Window excluded = new Window(900, 500);
            excluded.display = display;
            layout.apply(excluded, excluded.displayFrames, policy);
            require(excluded.computes == 0 && excluded.mRequestedWidth == 900, "Virtual display mutated");
        }
        for (int type : new int[] {2011, 2012, 2038, 2032}) {
            Window excluded = new Window(900, 500);
            excluded.attrs.type = type;
            layout.apply(excluded, excluded.displayFrames, policy);
            require(excluded.computes == 0, "System overlay mutated");
        }
        Window excluded = new Window(900, 500);
        excluded.mode = 5;
        layout.apply(excluded, excluded.displayFrames, policy);
        require(excluded.computes == 0, "True freeform task mutated under WM lock");
        Window ordinary = new Window(900, 500);
        WindowPolicy dock = WindowPolicy.read(Map.<String, String>of()::get, 1);
        layout.apply(ordinary, ordinary.displayFrames, dock);
        require(ordinary.frames.mContentFrame.left == 145 && ordinary.frames.mContentFrame.bottom == 560 && ordinary.mRequestedWidth == 900, "Compact dock viewport changed requested size");
        for (String value : new String[] {"240", "2.4e2", "0xf0"}) require(WindowPolicy.integer(Map.of("dpi", value)::get, "dpi", 0) == 240, "Numeric setting compatibility lost");
        require(WindowPolicy.blacklisted("com.qinggan.launcher") && !WindowPolicy.blacklisted("com.android.settings"), "OEM exclusions changed");
    }

    static final class Window {
        int mRequestedWidth;
        int mRequestedHeight;
        final Attributes attrs;
        final DisplayFrames displayFrames = new DisplayFrames();
        final Frames frames = new Frames();
        String name = "org.app";
        int display;
        int mode = 1;
        int computes;
        boolean failCompute;
        void removeImmediately() {}
        Window(int width, int height) { mRequestedWidth = width; mRequestedHeight = height; attrs = new Attributes(width, height); }
        String getOwningPackage() { return name; }
        Window getDisplayContent() { return this; }
        int getDisplayId() { return display; }
        int getWindowingMode() { return mode; }
        Attributes getAttrs() { return attrs; }
        DisplayFrames getDisplayFrames(DisplayFrames ignored) { return displayFrames; }
        Frames getWindowFrames() { return frames; }
        void computeFrame(DisplayFrames ignored) {
            computes++;
            if (failCompute) throw new IllegalStateException("OEM compute failed");
        }
        @Override public int hashCode() { return 123; }
        @Override public boolean equals(Object other) { return other instanceof Window; }
    }

    static final class Attributes {
        int width;
        int height;
        int type = 1;
        Attributes(int width, int height) { this.width = width; this.height = height; }
        String getTitle() { return ""; }
    }
    static final class DisplayFrames { Rect mStable = new Rect(); }
    static final class Frames {
        Rect mStableFrame = new Rect();
        Rect mParentFrame = new Rect();
        Rect mDisplayFrame = new Rect();
        Rect mContentFrame = new Rect();
        Rect mVisibleFrame = new Rect();
        Rect mDecorFrame = new Rect();
    }
    static final class Rect {
        int left;
        int top;
        int right = 1920;
        int bottom = 720;
        void set(int left, int top, int right, int bottom) { this.left = left; this.top = top; this.right = right; this.bottom = bottom; }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
