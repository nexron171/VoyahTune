package ru.voyahtune.agent.modules;

import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.runtime.WeakIdentityMap;

public final class LauncherWindowVisibility implements AutoCloseable {
    private final WeakIdentityMap<Object, Position> windows = new WeakIdentityMap<>();
    private boolean closed;
    private boolean uncertain;

    public synchronized boolean hide(Object controller) throws Exception {
        if (closed) throw new IllegalStateException("Dock window ownership is closed");
        Object root;
        Object parameters;
        Object manager;
        int x;
        int width;
        boolean attached;
        try {
            root = OemAccess.read(controller, "mRootView");
            parameters = OemAccess.read(controller, "mLp");
            manager = OemAccess.read(controller, "mWindowManager");
            if (root == null || parameters == null || manager == null) return false;
            x = (Integer) OemAccess.read(parameters, "x");
            width = (Integer) OemAccess.read(parameters, "width");
            if (width == 0 || width == Integer.MIN_VALUE) return false;
            attached = OemAccess.call(root, "getParent") != null;
        } catch (ReflectiveOperationException unsupported) { return false; }
        Position position = windows.get(controller);
        if (position == null) {
            position = new Position(x, attached);
            windows.put(controller, position);
        }
        position.applied = -Math.abs(width);
        try {
            Object animator;
            try { animator = OemAccess.read(controller, "mMoveWindowAnimator"); }
            catch (NoSuchFieldException absent) { animator = null; }
            if (animator != null && Boolean.TRUE.equals(OemAccess.call(animator, "isStarted"))) {
                OemAccess.call(animator, "removeAllListeners");
                OemAccess.call(animator, "removeAllUpdateListeners");
                OemAccess.call(animator, "cancel");
            }
            OemAccess.write(parameters, "x", position.applied);
            if (attached) {
                OemAccess.call(manager, "updateViewLayout", root, parameters);
                OemAccess.call(manager, "removeView", root);
            }
            return true;
        } catch (Exception failure) {
            uncertain = true;
            throw failure;
        }
    }

    public synchronized void forget(Object controller) { if (controller != null) windows.remove(controller); }

    @Override public synchronized void close() throws Exception {
        closed = true;
        Exception failure = null;
        for (Object controller : windows.keys()) {
            try { restore(controller, windows.get(controller)); windows.remove(controller); }
            catch (Exception caught) { if (failure == null) failure = caught; else failure.addSuppressed(caught); }
        }
        if (uncertain) {
            Exception unknown = new IllegalStateException("Vendor dock detach remains unconfirmed");
            if (failure == null) failure = unknown;
            else failure.addSuppressed(unknown);
        }
        if (failure != null) throw failure;
    }

    private void restore(Object controller, Position position) throws Exception {
        Object root = OemAccess.read(controller, "mRootView");
        Object parameters = OemAccess.read(controller, "mLp");
        if (!Integer.valueOf(position.applied).equals(OemAccess.read(parameters, "x"))) return;
        if (position.attached && OemAccess.call(root, "getParent") == null) {
            OemAccess.call(controller, "show");
            return;
        }
        OemAccess.write(parameters, "x", position.original);
        if (OemAccess.call(root, "getParent") != null) {
            OemAccess.call(OemAccess.read(controller, "mWindowManager"), "updateViewLayout", root, parameters);
        }
    }

    private static final class Position {
        final int original;
        final boolean attached;
        int applied;
        Position(int original, boolean attached) { this.original = original; this.attached = attached; }
    }
}
