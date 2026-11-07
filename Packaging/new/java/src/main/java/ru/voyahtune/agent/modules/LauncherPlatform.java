package ru.voyahtune.agent.modules;

import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.policy.LauncherCommand;
import ru.voyahtune.agent.policy.LauncherPolicy;
import ru.voyahtune.agent.policy.LauncherPresentation;
import ru.voyahtune.agent.runtime.ResourceGroup;

public interface LauncherPlatform extends LauncherPresentation.Access {
    @FunctionalInterface interface LongGesture { boolean click(Object view); }
    interface Events {
        void reload();
        void packages(String name);
        void locale();
        void chrome();
    }

    Map<String, String> settings() throws Exception;
    String setting(String key) throws Exception;
    LauncherPolicy.Top top(int display, boolean shared) throws Exception;
    long now();
    int lift();
    long revealRemaining(int display);
    boolean chromeHidden(int display);
    void chromeHidden(int display, boolean hidden) throws Exception;
    boolean syncStatusBar(int display, boolean visible);
    void reportDockHidden(int display, boolean hidden);
    Object model() throws Exception;
    List<?> roots() throws Exception;
    List<String> installed() throws Exception;
    boolean available(String target);
    Object appIcon(String target) throws Exception;
    CharSequence label(String target) throws Exception;
    void invalidatePackage(String name);
    boolean execute(LauncherCommand command) throws Exception;
    Object listener(LongGesture gesture);
    void postMain(Runnable action) throws Exception;
    void drainMain(AutoCloseable cleanup) throws Exception;
    void watch(ResourceGroup resources, Events events) throws Exception;
    default void watchApps(ResourceGroup resources, Events events) throws Exception { watch(resources, events); }
    default boolean image(Object view) { return false; }
    default String resolvePackage(Object intent) throws Exception { return ""; }
    default void passengerDock(Object bar, Object driver, String first, String second) throws Exception {}
    default void passengerSelection(String packageName) {}
    default void clearPassengerDock() throws Exception {}
    void error(Exception failure);
}
