package ru.voyahtune.agent.modules;

import android.util.Log;
import java.util.List;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.OemAccess;

public final class AndroidClientInputMethodPlatform implements ClientInputMethodModule.Platform {
    private final MethodAccess methods;

    public AndroidClientInputMethodPlatform(MethodAccess methods) {
        this.methods = methods;
    }

    @Override
    public List<?> roots() throws Exception {
        Object manager = OemAccess.call(methods.type("android.view.WindowManagerGlobal"), "getInstance");
        return (List<?>) OemAccess.call(manager, "getWindowViews");
    }

    @Override
    public Object managerForView(Object view) throws Exception {
        Object root = OemAccess.call(view, "getViewRootImpl");
        Object display = OemAccess.call(view, "getDisplay");
        if (root == null || display == null || !Boolean.TRUE.equals(OemAccess.call(display, "isValid"))
                || !Integer.valueOf(5).equals(OemAccess.call(display, "getType"))
                || !"ru.big.town.anative".equals(OemAccess.call(display, "getOwnerPackageName"))) {
            return null;
        }
        Object displayId = OemAccess.call(root, "getDisplayId");
        if (!displayId.equals(OemAccess.call(display, "getDisplayId"))) {
            return null;
        }
        // Application/activity contexts may still reference the display used before reparenting.
        Object context = OemAccess.call(view, "getContext");
        Object displayContext = OemAccess.call(context, "createDisplayContext", display);
        Object manager = OemAccess.call(displayContext, "getSystemService", "input_method");
        return manager != null && displayId.equals(OemAccess.call(manager, "getDisplayId")) ? manager : null;
    }

    @Override
    public void error(Exception failure) {
        Log.w("vt_app_client", "Virtual display input routing unavailable", failure);
    }
}
