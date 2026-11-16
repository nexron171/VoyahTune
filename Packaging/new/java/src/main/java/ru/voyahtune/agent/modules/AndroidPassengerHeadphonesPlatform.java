package ru.voyahtune.agent.modules;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;

import ru.voyahtune.agent.runtime.AndroidMainThread;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class AndroidPassengerHeadphonesPlatform implements PassengerHeadphonesModule.Platform {
    private final Context context;

    public AndroidPassengerHeadphonesPlatform(Context context) {
        this.context = context;
    }

    @Override
    public int read(String packageName) throws Exception {
        return context.getPackageManager().getApplicationInfo(packageName, 0).flags;
    }

    @Override
    public Object engine() throws Exception {
        AtomicReference<Object> engine = new AtomicReference<>();
        AndroidMainThread.run(() -> engine.set(OemAccess.read(context.getApplicationContext(), "a")));
        return engine.get();
    }

    @Override
    public Map<String, Integer> displays() throws Exception {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) throw new IllegalStateException("Activity manager unavailable");
        List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(100);
        Map<String, Integer> displays = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (ActivityManager.RunningTaskInfo task : tasks) {
            int display = ((Number) OemAccess.read(task, "displayId")).intValue();
            if (display != 0 && display != 1) continue;
            addTask(displays, ambiguous, task.topActivity, display);
            addTask(displays, ambiguous, task.baseActivity, display);
        }
        ambiguous.forEach(displays::remove);
        return displays;
    }

    private static void addTask(Map<String, Integer> displays, Set<String> ambiguous,
            ComponentName activity, int display) {
        if (activity == null) return;
        String packageName = activity.getPackageName();
        Integer previous = displays.putIfAbsent(packageName, display);
        if (previous != null && previous != display) ambiguous.add(packageName);
    }

    @Override
    public void onCollector(Object collector, ModuleScope.ConfigurationAction action) throws Exception {
        AndroidMainThread.run((Handler) OemAccess.read(collector, "b"), action::apply);
    }
}
