package ru.voyahtune.agent.modules;

import java.lang.reflect.Method;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.AirQualityPublisher;
import ru.voyahtune.agent.policy.StatusBarController;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;

public final class StatusBarModule implements ModuleRuntime.Module {
    public interface Platform {
        String top(int display) throws Exception;
        boolean hideSystemBars();
        long revealRemaining(int display);
        void reportHidden(int display, boolean hidden);
        Object statusBar() throws Exception;
        void postMain(Runnable action) throws Exception;
        void drainMain(NavigationSourceModule.MainAction action) throws Exception;
        void watch(ModuleScope scope, Runnable changed) throws Exception;
        AirQualityPublisher.Storage airQuality();
        void seedAirQuality() throws Exception;
        void error(Exception failure);
    }

    public static final String BAR = "com.qinggan.systemui.statusbar.phone.PhoneStatusBar";
    public static final String INVISIBLE = "com.qinggan.systemui.statusbar.utils.StatusBarInvisibleItem";
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final Platform platform;
    private final ThreadLocal<Boolean> applying = ThreadLocal.withInitial(() -> false);
    private Method hit;
    private Method[] windows;
    private Method air;
    private StatusBarController controller;

    public StatusBarModule(MethodAccess methods, HookRegistry hooks, Platform platform) {
        this.methods = methods;
        this.hooks = hooks;
        this.platform = platform;
    }

    @Override public void prepare(Map<String, String> configuration) throws Exception {
        hit = methods.require(INVISIBLE, "isHit", "java.lang.String", "int");
        windows = new Method[] {methods.require(BAR, "addStatusBarWindow", "boolean"), methods.require(BAR, "addStatusBarWindowSecond", "boolean")};
        for (Method method : windows) if (method.getReturnType() != void.class) throw new IllegalArgumentException("Status-bar window setter must return void");
        if (hit.getReturnType() != boolean.class) throw new IllegalArgumentException("Status-bar predicate must return boolean");
        OemAccess.field(methods.type(BAR), "pictureFullScreen");
        try { air = methods.optional("com.qinggan.systemui.statusbar.views.StatusBarView", "setPm25Incar", "int"); }
        catch (ClassNotFoundException absent) { air = null; }
    }

    @Override public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        controller = new StatusBarController(new StatusBarController.IO() {
            @Override public String top(int display) throws Exception { return platform.top(display); }
            @Override public boolean hideSystemBars() { return platform.hideSystemBars(); }
            @Override public long revealRemaining(int display) { return platform.revealRemaining(display); }
            @Override public boolean stockHidden(String activity, int display) throws Exception {
                boolean hidden = (Boolean) OemAccess.call(methods.type(INVISIBLE), "isHit", activity, display);
                return hidden && (!activity.endsWith("PictureMainActivity") || Boolean.TRUE.equals(OemAccess.read(methods.type(BAR), "pictureFullScreen")));
            }
            @Override public void apply(int display, boolean visible, Object receiver) throws Exception {
                if (receiver instanceof HookRegistry.Invocation) {
                    HookRegistry.Invocation invocation = (HookRegistry.Invocation) receiver;
                    invocation.arguments()[0] = visible;
                    try { invocation.proceed(); }
                    catch (Error failure) { throw failure; }
                    catch (Exception failure) { throw failure; }
                    catch (Throwable failure) { throw new IllegalStateException(failure); }
                    return;
                }
                boolean previous = applying.get();
                applying.set(true);
                try { OemAccess.call(receiver == null ? platform.statusBar() : receiver, windows[display].getName(), visible); }
                finally { if (previous) applying.set(true); else applying.remove(); }
            }
            @Override public void reportHidden(int display, boolean hidden) { platform.reportHidden(display, hidden); }
            @Override public void schedule(long delay, Runnable action) throws Exception { scope.schedule(delay, () -> post(scope, action)); }
            @Override public void error(Exception failure) { StatusBarModule.this.error(failure); }
        });
        scope.own(hooks.install("statusbar", hit, scope.callback(invocation -> {
            String activity = (String) invocation.arguments()[0];
            int display = (Integer) invocation.arguments()[1];
            return StatusBarController.isHost(activity, display) ? platform.hideSystemBars() && platform.revealRemaining(display) == 0 : invocation.proceed();
        })));
        for (int index = 0; index < 2; index++) {
            int display = index;
            scope.own(hooks.install("statusbar", windows[index], scope.callback(invocation -> {
                if (applying.get()) return invocation.proceed();
                controller.request(display, (Boolean) invocation.arguments()[0], invocation);
                return null;
            })));
        }
        if (air != null) {
            try {
                scope.own(hooks.install("statusbar-air-quality", air, scope.callback(invocation -> {
                    Object result = invocation.proceed();
                    try { AirQualityPublisher.publish(platform.airQuality(), (Integer) invocation.arguments()[0]); }
                    catch (Exception unavailable) { error(unavailable); }
                    return result;
                })));
            } catch (HookRegistry.RejectedException unsupported) { error(unsupported); }
        }
        platform.watch(scope, () -> post(scope, controller::reconcile));
        scope.own(() -> platform.drainMain(controller::release));
    }

    @Override public void configure(ModuleScope scope, Map<String, String> configuration) throws Exception {
        controller.resetTimer();
        scope.schedule(0, () -> post(scope, () -> {
            controller.reconcile();
            try { platform.seedAirQuality(); }
            catch (Exception unavailable) { error(unavailable); }
        }));
    }

    private void post(ModuleScope scope, Runnable action) {
        try { platform.postMain(scope.listener(action)); }
        catch (Exception unavailable) { error(unavailable); }
    }

    private void error(Exception failure) {
        try { platform.error(failure); }
        catch (RuntimeException ignored) { /* Diagnostics must preserve OEM visibility behavior. */ }
    }
}
