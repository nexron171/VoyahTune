package ru.voyahtune.agent.modules;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import ru.voyahtune.agent.policy.WindowPolicy;
import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.runtime.WeakIdentityMap;

public final class WindowLayout {
    public interface Access {
        Object read(Object owner, String field) throws Exception;
        void write(Object owner, String field, Object value) throws Exception;
        Object call(Object owner, String method, Object... arguments) throws Exception;
    }

    public static class Reflection implements Access {
        @Override public Object read(Object owner, String field) throws Exception { return OemAccess.read(owner, field); }
        @Override public void write(Object owner, String field, Object value) throws Exception { OemAccess.write(owner, field, value); }
        @Override public Object call(Object owner, String method, Object... arguments) throws Exception { return OemAccess.call(owner, method, arguments); }
    }

    private static final String[] FRAMES = {"mStableFrame", "mParentFrame", "mDisplayFrame", "mContentFrame", "mVisibleFrame", "mDecorFrame"};
    private final Access access;
    private final Consumer<Exception> uncertain;
    private final WeakIdentityMap<Object, Size> originals = new WeakIdentityMap<>();
    private boolean rollbackIncomplete;

    public WindowLayout(Access access, Consumer<Exception> uncertain) {
        this.access = access;
        this.uncertain = uncertain;
    }

    public synchronized void apply(Object window, Object displayFrames, WindowPolicy policy) {
        if (!policy.enabled || rollbackIncomplete) return;
        try {
            String name = (String) access.call(window, "getOwningPackage");
            if (WindowPolicy.blacklisted(name)) return;
            Object display = access.call(window, "getDisplayContent");
            if (display == null) return;
            int displayId = (Integer) access.call(display, "getDisplayId");
            if (displayId != 0 && displayId != 1) return;
            Object attributes = access.call(window, "getAttrs");
            int type = integer(attributes, "type");
            if (type == 2011 || type == 2012 || type == 2038 || type == 2032 || Integer.valueOf(5).equals(access.call(window, "getWindowingMode"))) return;
            Object frames = access.call(window, "getDisplayFrames", displayFrames);
            Object windowFrames = access.call(window, "getWindowFrames");
            if (frames == null || windowFrames == null) return;
            compute(window, attributes, frames, windowFrames, policy, policy.fullscreen(name) , type);
        } catch (Exception unsupportedWindow) { /* An incompatible window keeps the stock layout. */ }
    }

    private void compute(Object window, Object attributes, Object frames, Object windowFrames, WindowPolicy policy, boolean fullscreen, int type) throws Exception {
        RectState stable = new RectState(access.read(frames, "mStable"));
        List<RectState> saved = new ArrayList<>();
        for (String field : FRAMES) saved.add(new RectState(access.read(windowFrames, field)));
        int width = integer(window, "mRequestedWidth");
        int height = integer(window, "mRequestedHeight");
        int attributeWidth = integer(attributes, "width");
        int attributeHeight = integer(attributes, "height");
        int left = fullscreen ? 0 : policy.left;
        int bottom = policy.effectiveBottom();
        Size size = originals.get(window);
        if (fullscreen && type == 1 && size == null) {
            if (originals.size() >= 128) return;
            size = new Size(width, height);
            originals.put(window, size);
        }
        boolean complete = false;
        try {
            if (fullscreen && type == 1) {
                if (width != size.appliedWidth) size.width = width;
                if (height != size.appliedHeight) size.height = height;
                size.appliedWidth = policy.right - left;
                size.appliedHeight = bottom - policy.top;
                access.write(window, "mRequestedWidth", size.appliedWidth);
                access.write(window, "mRequestedHeight", size.appliedHeight);
            } else if (size != null) restore(window);
            if (fullscreen) {
                access.write(attributes, "width", -1);
                access.write(attributes, "height", -1);
            }
            stable.set(left, policy.top, policy.right, bottom);
            for (RectState frame : saved) frame.set(left, policy.top, policy.right, bottom);
            access.call(window, "computeFrame", frames);
            complete = true;
        } finally {
            List<Exception> failures = new ArrayList<>();
            recover(failures, () -> access.write(attributes, "width", attributeWidth));
            recover(failures, () -> access.write(attributes, "height", attributeHeight));
            recover(failures, stable::restore);
            if (!complete) {
                for (RectState frame : saved) recover(failures, frame::restore);
                recover(failures, () -> restore(window));
            }
            if (!failures.isEmpty()) {
                rollbackIncomplete = true;
                uncertain.accept(combined("Window shared fields restoration incomplete", failures));
            }
        }
    }

    public synchronized void restore(Object window) throws Exception {
        Size size = originals.get(window);
        if (size == null) return;
        List<Exception> failures = new ArrayList<>();
        recover(failures, () -> {
            if (integer(window, "mRequestedWidth") == size.appliedWidth) access.write(window, "mRequestedWidth", size.width);
        });
        recover(failures, () -> {
            if (integer(window, "mRequestedHeight") == size.appliedHeight) access.write(window, "mRequestedHeight", size.height);
        });
        if (failures.isEmpty()) originals.remove(window);
        else throw combined("Window requested size restoration incomplete", failures);
    }

    public synchronized void restoreAll(WindowPolicy policy, boolean all) throws Exception {
        List<Exception> failures = new ArrayList<>();
        for (Object window : originals.keys()) recover(failures, () -> {
            if (all || !policy.enabled || !policy.fullscreen((String) access.call(window, "getOwningPackage"))) restore(window);
        });
        if (rollbackIncomplete) failures.add(new IllegalStateException("Earlier shared window rollback remains unconfirmed"));
        if (!failures.isEmpty()) throw combined("Window cleanup incomplete", failures);
    }

    public synchronized int size() { return originals.size(); }

    private int integer(Object owner, String field) throws Exception { return (Integer) access.read(owner, field); }
    private static void recover(List<Exception> failures, AutoCloseable action) {
        try { action.close(); }
        catch (Exception failure) { failures.add(failure); }
    }
    private static Exception combined(String message, List<Exception> failures) {
        Exception result = new IllegalStateException(message, failures.get(0));
        for (int index = 1; index < failures.size(); index++) result.addSuppressed(failures.get(index));
        return result;
    }

    private final class RectState {
        final Object rectangle;
        final int left;
        final int top;
        final int right;
        final int bottom;
        RectState(Object rectangle) throws Exception {
            this.rectangle = rectangle;
            left = integer(rectangle, "left");
            top = integer(rectangle, "top");
            right = integer(rectangle, "right");
            bottom = integer(rectangle, "bottom");
        }
        void set(int left, int top, int right, int bottom) throws Exception { access.call(rectangle, "set", left, top, right, bottom); }
        void restore() throws Exception { set(left, top, right, bottom); }
    }

    private static final class Size {
        int width;
        int height;
        int appliedWidth;
        int appliedHeight;
        Size(int width, int height) { this.width = width; this.height = height; appliedWidth = width; appliedHeight = height; }
    }
}
