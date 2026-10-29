package ru.voyahtune.agent.policy;

import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.runtime.ViewOwnership;

public final class LauncherPresentation implements AutoCloseable {
    public interface Access extends ViewOwnership.Access {
        Object icon(String target, int slot, String style) throws Exception;
        void invalidate(Object view) throws Exception;
        boolean layout(Object view) throws Exception;
    }

    private final Access access;
    private final ViewOwnership ownership;

    public LauncherPresentation(Access access) {
        this.access = access;
        ownership = new ViewOwnership(access);
    }

    public void icons(int display, List<Object> slots, List<String> targets, List<String> styles) throws Exception {
        if (display != 0) return;
        for (int index = 0; index < Math.min(2, slots.size()); index++) {
            Object view = slots.get(index);
            if (view == null) continue;
            String target = targets.get(index);
            if (target == null || target.isEmpty() || target.equals("none")) ownership.restore(view, "background");
            else {
                Object icon = access.icon(target, index + 1, styles.get(index));
                if (icon != null) ownership.write(view, "background", icon);
            }
            access.invalidate(view);
        }
    }

    public void listener(int display, Object view, Object listener) throws Exception {
        if (display != 0 || view == null || listener == null) return;
        ownership.write(view, "longClickable", true);
        ownership.write(view, "longClick", listener);
    }

    public void lift(int display, Map<String, Object> views, int lift, boolean hide, boolean firstAssigned, boolean secondAssigned) throws Exception {
        if (display != 0) return;
        if (hide) {
            visibility(views.get("up"), false);
            visibility(views.get("down"), false);
            return;
        }
        boolean compact = lift == 1;
        visibility(views.get("up"), true);
        visibility(views.get("down"), false);
        height(views.get("up"), compact ? 560 : 720);
        height(views.get("group"), compact ? -2 : -1);
        visibility(views.get("home"), true);
        visibility(views.get("slot1"), !compact || firstAssigned);
        visibility(views.get("slot2"), !compact || secondAssigned);
        for (String name : new String[] {"allApps", "extra1", "extra2", "temperature"}) visibility(views.get(name), !compact);
    }

    public void checked(Object view, boolean checked) throws Exception {
        if (view != null) ownership.write(view, "checked", checked);
    }

    private void visibility(Object view, boolean visible) throws Exception {
        if (view != null) ownership.write(view, "visibility", visible ? 0 : 8);
    }
    private void height(Object view, int height) throws Exception {
        if (view != null && access.layout(view)) ownership.write(view, "height", height);
    }

    @Override public void close() throws Exception { ownership.close(); }
}
