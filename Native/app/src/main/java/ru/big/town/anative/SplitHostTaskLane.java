package ru.big.town.anative;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

/**
 * One process-wide bounded lane for SplitHost package/task Binder calls.
 *
 * <p>There is at most one running item and one latest pending item for single-host preflight and
 * supervision, plus one latest pending launch per pane. A wedged car/system Binder therefore blocks
 * this background thread, never main, and Activity recreation cannot multiply blocked threads.</p>
 */
final class SplitHostTaskLane {
    private static final String TAG = "$$$ SplitHostTasks $$$";
    private static final int TASK_QUERY_LIMIT = 100;
    private static final long WATCH_SETTING_CACHE_MS = 30_000L;
    private static volatile SplitHostTaskLane instance;

    static SplitHostTaskLane get(Context context) {
        SplitHostTaskLane current = instance;
        if (current != null) return current;
        synchronized (SplitHostTaskLane.class) {
            current = instance;
            if (current == null) {
                Context app = context.getApplicationContext();
                current = new SplitHostTaskLane(app != null ? app : context);
                instance = current;
            }
            return current;
        }
    }

    static final class PaneTicket {
        final long hostGeneration;
        final int paneIndex;
        final long paneGeneration;
        final String packageName;
        final int displayId;

        PaneTicket(long hostGeneration, int paneIndex, long paneGeneration,
                   String packageName, int displayId) {
            this.hostGeneration = hostGeneration;
            this.paneIndex = paneIndex;
            this.paneGeneration = paneGeneration;
            this.packageName = packageName;
            this.displayId = displayId;
        }
    }

    static final class PaneLaunchRequest {
        final long sequence;
        final WeakReference<SplitHostActivity> owner;
        final PaneTicket pane;

        PaneLaunchRequest(long sequence, SplitHostActivity owner, PaneTicket pane) {
            this.sequence = sequence;
            this.owner = new WeakReference<>(owner);
            this.pane = pane;
        }
    }

    static final class SupervisionRequest {
        final long sequence;
        final WeakReference<SplitHostActivity> owner;
        final long hostGeneration;
        final long supervisionGeneration;
        final List<PaneTicket> panes;

        SupervisionRequest(long sequence, SplitHostActivity owner, long hostGeneration,
                           long supervisionGeneration, List<PaneTicket> panes) {
            this.sequence = sequence;
            this.owner = new WeakReference<>(owner);
            this.hostGeneration = hostGeneration;
            this.supervisionGeneration = supervisionGeneration;
            this.panes = Collections.unmodifiableList(new ArrayList<>(panes));
        }
    }

    private static final class SingleHostRequest {
        final long sequence;
        final String packageName;
        final int dpi;
        final int displayId;

        SingleHostRequest(long sequence, String packageName, int dpi, int displayId) {
            this.sequence = sequence;
            this.packageName = packageName;
            this.dpi = dpi;
            this.displayId = displayId;
        }
    }

    private static final class TaskQuery {
        final SplitHostTaskSnapshot snapshot;

        TaskQuery(SplitHostTaskSnapshot snapshot) {
            this.snapshot = snapshot;
        }
    }

    private static final class SingleHostResult {
        final SingleHostRequest request;
        final boolean launchable;

        SingleHostResult(SingleHostRequest request, boolean launchable) {
            this.request = request;
            this.launchable = launchable;
        }
    }

    private static final class PaneLaunchResult {
        final PaneLaunchRequest request;
        final Intent launchIntent;

        PaneLaunchResult(PaneLaunchRequest request, Intent launchIntent) {
            this.request = request;
            this.launchIntent = launchIntent;
        }
    }

    private static final class SupervisionResult {
        final SupervisionRequest request;
        final boolean enabled;
        final SplitHostTaskSnapshot snapshot;

        SupervisionResult(SupervisionRequest request, boolean enabled,
                          SplitHostTaskSnapshot snapshot) {
            this.request = request;
            this.enabled = enabled;
            this.snapshot = snapshot;
        }
    }

    private final Context appContext;
    private final ActivityManager activityManager;
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SplitHostHostLease<SplitHostActivity> hostLease = new SplitHostHostLease<>();
    private final Object lock = new Object();
    private final LatestValueDelivery<SingleHostResult> singleHostResults;
    private final LatestValueDelivery<PaneLaunchResult> leftPaneResults;
    private final LatestValueDelivery<PaneLaunchResult> rightPaneResults;
    private final LatestValueDelivery<SupervisionResult> supervisionResults;

    // Guarded by lock. Each active drain owns at most one queued/running Handler message.
    private long nextSequence;
    private final SplitHostPendingSlots<SingleHostRequest> singleHostWork =
            new SplitHostPendingSlots<>(1);
    private long latestSingleHostSequence;

    private final SplitHostPendingSlots<PaneLaunchRequest> paneLaunchWork =
            new SplitHostPendingSlots<>(2);
    private final long[] latestPaneSequence = new long[2];
    private final long[] latestPaneHost = new long[2];

    private final SplitHostPendingSlots<SupervisionRequest> supervisionWork =
            new SplitHostPendingSlots<>(1);
    private long latestSupervisionHost;

    // Worker-thread confined reflection caches.
    private boolean displayFieldResolved;
    private Field displayField;
    private Object activityTaskManager;
    private Method removeTaskMethod;
    private long watchSettingExpiresAt;
    private boolean cachedWatchEnabled = true;

    private final Runnable singleHostDrain = new Runnable() {
        @Override public void run() {
            SingleHostRequest request;
            synchronized (lock) {
                request = singleHostWork.take(0);
            }
            try {
                if (request != null && isLatestSingleHost(request)) {
                    final boolean launchable = resolveLaunchIntent(request.packageName) != null;
                    final SingleHostRequest delivered = request;
                    if (launchable && isLatestSingleHost(delivered)) {
                        // This Settings write is part of launch preparation too; keep it off main.
                        DockLaunchGuard.arm(appContext, delivered.displayId, "ru.big.town.anative");
                    }
                    singleHostResults.offer(1L, delivered.sequence,
                            new SingleHostResult(delivered, launchable));
                }
            } catch (Throwable t) {
                Log.w(TAG, "single-host preflight failed: " + rootCause(t));
            } finally {
                finishSingleHostDrain();
            }
        }
    };

    private final Runnable paneLaunchDrain = new Runnable() {
        @Override public void run() {
            PaneLaunchRequest[] batch = new PaneLaunchRequest[2];
            synchronized (lock) {
                batch[0] = paneLaunchWork.take(0);
                batch[1] = paneLaunchWork.take(1);
            }
            try {
                processPaneLaunchBatch(batch);
            } catch (Throwable t) {
                Log.w(TAG, "pane launch batch failed: " + rootCause(t));
                for (PaneLaunchRequest request : batch) {
                    if (request != null) postPaneLaunchResult(request, null);
                }
            } finally {
                finishPaneLaunchDrain();
            }
        }
    };

    private final Runnable supervisionDrain = new Runnable() {
        @Override public void run() {
            SupervisionRequest request;
            synchronized (lock) {
                request = supervisionWork.take(0);
            }
            try {
                if (request != null && isCurrentSupervisionHost(request)) {
                    boolean enabled = readWatchEnabled();
                    if (!isCurrentSupervisionHost(request)) return;
                    SplitHostTaskSnapshot snapshot = enabled
                            ? queryTasks().snapshot : SplitHostTaskSnapshot.unknown();
                    postSupervisionResult(request, enabled, snapshot);
                }
            } catch (Throwable t) {
                Log.w(TAG, "supervision query failed: " + rootCause(t));
                if (request != null) {
                    postSupervisionResult(request, true, SplitHostTaskSnapshot.unknown());
                }
            } finally {
                finishSupervisionDrain();
            }
        }
    };

    private SplitHostTaskLane(Context appContext) {
        this.appContext = appContext;
        this.activityManager = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        HandlerThread thread = new HandlerThread("SplitHostTasks", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        worker = new Handler(thread.getLooper());
        java.util.concurrent.Executor mainExecutor = command -> {
            if (!main.post(command)) {
                throw new RejectedExecutionException("main Handler rejected SplitHost result");
            }
        };
        singleHostResults = new LatestValueDelivery<>(mainExecutor, this::deliverSingleHostResult);
        leftPaneResults = new LatestValueDelivery<>(mainExecutor, this::deliverPaneLaunchResult);
        rightPaneResults = new LatestValueDelivery<>(mainExecutor, this::deliverPaneLaunchResult);
        supervisionResults = new LatestValueDelivery<>(mainExecutor, this::deliverSupervisionResult);
    }

    long registerHost(SplitHostActivity owner) {
        SplitHostHostLease.Registration<SplitHostActivity> registration = hostLease.acquire(owner);
        SplitHostActivity previous = registration.previousOwner;
        if (previous != null && previous != owner) {
            Runnable retirePrevious = () -> previous.onSupersededByHost(registration.generation);
            if (Looper.myLooper() == Looper.getMainLooper()) retirePrevious.run();
            else if (!main.post(retirePrevious)) Log.e(TAG, "main rejected old-host retirement");
        }
        return registration.generation;
    }

    void requestSingleHost(String packageName, int dpi, int displayId) {
        synchronized (lock) {
            long sequence = ++nextSequence;
            latestSingleHostSequence = sequence;
            boolean postDrain = singleHostWork.offer(
                    0, new SingleHostRequest(sequence, packageName, dpi, displayId));
            if (postDrain && !worker.post(singleHostDrain)) {
                singleHostWork.rejectDrainPost();
                Log.e(TAG, "worker rejected single-host drain");
            }
        }
    }

    void requestPaneLaunch(SplitHostActivity owner, PaneTicket pane) {
        synchronized (lock) {
            long sequence = ++nextSequence;
            PaneLaunchRequest request = new PaneLaunchRequest(sequence, owner, pane);
            boolean postDrain = paneLaunchWork.offer(pane.paneIndex, request);
            latestPaneSequence[pane.paneIndex] = sequence;
            latestPaneHost[pane.paneIndex] = pane.hostGeneration;
            if (postDrain && !worker.post(paneLaunchDrain)) {
                paneLaunchWork.rejectDrainPost();
                Log.e(TAG, "worker rejected pane-launch drain");
            }
        }
    }

    void requestSupervision(SplitHostActivity owner, long hostGeneration,
                            long supervisionGeneration, List<PaneTicket> panes) {
        if (panes == null || panes.isEmpty()) return;
        synchronized (lock) {
            long sequence = ++nextSequence;
            boolean postDrain = supervisionWork.offer(0, new SupervisionRequest(sequence, owner,
                    hostGeneration, supervisionGeneration, panes));
            latestSupervisionHost = hostGeneration;
            if (postDrain && !worker.post(supervisionDrain)) {
                supervisionWork.rejectDrainPost();
                Log.e(TAG, "worker rejected supervision drain");
            }
        }
    }

    /** Invalidates queued and already-running callbacks for an Activity without blocking main. */
    void cancelHost(long hostGeneration) {
        cancelHostWork(hostGeneration);
        hostLease.release(hostGeneration);
    }

    /** Used by recreate(): fence work immediately but keep ownership until onDestroy/unregister. */
    void cancelHostWork(long hostGeneration) {
        synchronized (lock) {
            for (int pane = 0; pane < 2; pane++) {
                PaneLaunchRequest pending = paneLaunchWork.peek(pane);
                if (pending != null && pending.pane.hostGeneration == hostGeneration) {
                    paneLaunchWork.clear(pane);
                }
                if (latestPaneHost[pane] == hostGeneration) {
                    latestPaneHost[pane] = 0L;
                    latestPaneSequence[pane] = ++nextSequence;
                }
            }
            cancelSupervisionLocked(hostGeneration);
        }
    }

    void cancelSupervision(long hostGeneration) {
        synchronized (lock) {
            cancelSupervisionLocked(hostGeneration);
        }
    }

    private void cancelSupervisionLocked(long hostGeneration) {
        SupervisionRequest pending = supervisionWork.peek(0);
        if (pending != null && pending.hostGeneration == hostGeneration) {
            supervisionWork.clear(0);
        }
        if (latestSupervisionHost == hostGeneration) {
            latestSupervisionHost = 0L;
        }
    }

    private void processPaneLaunchBatch(PaneLaunchRequest[] batch) {
        assertWorkerThread();
        Intent[] launchIntents = new Intent[batch.length];
        for (int pane = 0; pane < batch.length; pane++) {
            PaneLaunchRequest request = batch[pane];
            if (request == null || !isLatestPane(request)) continue;
            launchIntents[pane] = resolveLaunchIntent(request.pane.packageName);
        }

        boolean anyLaunchable = false;
        for (int pane = 0; pane < batch.length; pane++) {
            PaneLaunchRequest request = batch[pane];
            anyLaunchable |= request != null && launchIntents[pane] != null
                    && isLatestPane(request);
        }
        if (anyLaunchable) {
            // Exactly one task snapshot services both pane teardowns in this batch.
            TaskQuery query = queryTasks();
            Set<String> currentPackages = new HashSet<>();
            for (int pane = 0; pane < batch.length; pane++) {
                PaneLaunchRequest request = batch[pane];
                if (request != null && launchIntents[pane] != null && isLatestPane(request)) {
                    currentPackages.add(request.pane.packageName);
                }
            }
            removeMatchingTasks(query.snapshot, currentPackages, batch);
        }

        for (int pane = 0; pane < batch.length; pane++) {
            PaneLaunchRequest request = batch[pane];
            if (request != null) postPaneLaunchResult(request, launchIntents[pane]);
        }
    }

    private Intent resolveLaunchIntent(String packageName) {
        assertWorkerThread();
        try {
            return appContext.getPackageManager().getLaunchIntentForPackage(packageName);
        } catch (Throwable t) {
            Log.w(TAG, "launch intent " + packageName + ": " + rootCause(t));
            return null;
        }
    }

    private boolean readWatchEnabled() {
        assertWorkerThread();
        long now = SystemClock.elapsedRealtime();
        if (now < watchSettingExpiresAt) return cachedWatchEnabled;
        try {
            String value = Settings.Global.getString(
                    appContext.getContentResolver(), "voyahtune_splitwatch");
            cachedWatchEnabled = !"0".equals(value);
        } catch (Throwable t) {
            Log.w(TAG, "splitwatch setting: " + rootCause(t));
            cachedWatchEnabled = true;
        }
        watchSettingExpiresAt = now + WATCH_SETTING_CACHE_MS;
        return cachedWatchEnabled;
    }

    private TaskQuery queryTasks() {
        assertWorkerThread();
        if (activityManager == null) return new TaskQuery(SplitHostTaskSnapshot.unknown());
        try {
            List<ActivityManager.RunningTaskInfo> running =
                    activityManager.getRunningTasks(TASK_QUERY_LIMIT);
            if (running == null) return new TaskQuery(SplitHostTaskSnapshot.unknown());
            List<SplitHostTaskSnapshot.TaskRecord> normalized = new ArrayList<>(running.size());
            for (ActivityManager.RunningTaskInfo task : running) {
                ComponentName component = task.topActivity != null ? task.topActivity : task.baseActivity;
                String packageName = component != null ? component.getPackageName() : null;
                normalized.add(new SplitHostTaskSnapshot.TaskRecord(
                        task.taskId, packageName, readDisplayId(task)));
            }
            return new TaskQuery(SplitHostTaskSnapshot.known(normalized));
        } catch (Throwable t) {
            Log.w(TAG, "getRunningTasks: " + rootCause(t));
            return new TaskQuery(SplitHostTaskSnapshot.unknown());
        }
    }

    private Integer readDisplayId(ActivityManager.RunningTaskInfo task) {
        if (!displayFieldResolved) {
            displayFieldResolved = true;
            try {
                displayField = task.getClass().getField("displayId");
            } catch (Throwable t) {
                Log.w(TAG, "RunningTaskInfo.displayId unavailable: " + rootCause(t));
            }
        }
        if (displayField == null) return null;
        try {
            return displayField.getInt(task);
        } catch (Throwable t) {
            return null;
        }
    }

    private void removeMatchingTasks(SplitHostTaskSnapshot snapshot, Set<String> packages,
                                     PaneLaunchRequest[] batch) {
        assertWorkerThread();
        if (packages.isEmpty() || !ensureRemoveTask()) return;
        int removed = 0;
        boolean invocationFailed = false;
        for (SplitHostTaskSnapshot.TaskRecord task : snapshot.tasks()) {
            // This synchronized latest-check is the destructive operation's linearization point.
            // A successor offered afterwards stays serialized behind this worker; it never races
            // concurrently, although this one already-claimed removeTask may still finish first.
            if (!packages.contains(task.packageName)
                    || !hasCurrentRequestForPackage(batch, task.packageName)) continue;
            try {
                removeTaskMethod.invoke(activityTaskManager, task.taskId);
                removed++;
            } catch (Throwable t) {
                invocationFailed = true;
                Log.w(TAG, "removeTask " + task.taskId + " (" + task.packageName + "): "
                        + rootCause(t));
            }
        }
        if (invocationFailed) {
            // Binder/service replacement is recoverable on a later launch; don't pin a dead proxy.
            activityTaskManager = null;
            removeTaskMethod = null;
        }
        Log.i(TAG, "removeTask packages=" + packages + " removed=" + removed + " (process alive)");
    }

    private boolean hasCurrentRequestForPackage(PaneLaunchRequest[] batch, String packageName) {
        for (PaneLaunchRequest request : batch) {
            if (request != null && packageName.equals(request.pane.packageName)
                    && isLatestPane(request)) return true;
        }
        return false;
    }

    private boolean ensureRemoveTask() {
        if (activityTaskManager != null && removeTaskMethod != null) return true;
        try {
            activityTaskManager = Class.forName("android.app.ActivityTaskManager")
                    .getMethod("getService").invoke(null);
            removeTaskMethod = activityTaskManager.getClass().getMethod("removeTask", int.class);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "IActivityTaskManager.removeTask unavailable: " + rootCause(t));
            activityTaskManager = null;
            removeTaskMethod = null;
            return false;
        }
    }

    private void postPaneLaunchResult(PaneLaunchRequest request, Intent launchIntent) {
        LatestValueDelivery<PaneLaunchResult> delivery =
                request.pane.paneIndex == SplitHostGenerationGate.LEFT
                        ? leftPaneResults : rightPaneResults;
        delivery.offer(request.pane.hostGeneration, request.sequence,
                new PaneLaunchResult(request, launchIntent));
    }

    private void postSupervisionResult(SupervisionRequest request, boolean enabled,
                                       SplitHostTaskSnapshot snapshot) {
        supervisionResults.offer(request.hostGeneration, request.sequence,
                new SupervisionResult(request, enabled, snapshot));
    }

    private void deliverSingleHostResult(SingleHostResult result) {
        SingleHostRequest request = result.request;
        if (!isLatestSingleHost(request)) return;
        if (!result.launchable) {
            Log.w(TAG, "launchSingle: нет launch intent для " + request.packageName);
            return;
        }
        SplitHostActivity.startSingleHost(
                appContext, request.packageName, request.dpi, request.displayId);
    }

    private void deliverPaneLaunchResult(PaneLaunchResult result) {
        PaneLaunchRequest request = result.request;
        if (!isLatestPane(request)) return;
        SplitHostActivity owner = request.owner.get();
        if (owner != null) owner.onPaneLaunchPrepared(request, result.launchIntent);
    }

    private void deliverSupervisionResult(SupervisionResult result) {
        SupervisionRequest request = result.request;
        if (!isCurrentSupervisionHost(request)) return;
        SplitHostActivity owner = request.owner.get();
        if (owner != null) {
            owner.onSupervisionSnapshot(request, result.enabled, result.snapshot);
        }
    }

    private boolean isLatestSingleHost(SingleHostRequest request) {
        synchronized (lock) {
            return request.sequence == latestSingleHostSequence;
        }
    }

    private boolean isLatestPane(PaneLaunchRequest request) {
        int pane = request.pane.paneIndex;
        synchronized (lock) {
            return request.sequence == latestPaneSequence[pane]
                    && request.pane.hostGeneration == latestPaneHost[pane];
        }
    }

    private boolean isCurrentSupervisionHost(SupervisionRequest request) {
        synchronized (lock) {
            // A newer periodic tick from the same host must not starve a slow-but-valid result.
            // Pause/recreate changes the host/lifecycle fence and is still rejected.
            return request.hostGeneration == latestSupervisionHost;
        }
    }

    private void finishSingleHostDrain() {
        synchronized (lock) {
            boolean again = singleHostWork.finishDrain();
            if (again && !worker.post(singleHostDrain)) {
                singleHostWork.rejectDrainPost();
                Log.e(TAG, "worker rejected single-host follow-up");
            }
        }
    }

    private void finishPaneLaunchDrain() {
        synchronized (lock) {
            boolean again = paneLaunchWork.finishDrain();
            if (again && !worker.post(paneLaunchDrain)) {
                paneLaunchWork.rejectDrainPost();
                Log.e(TAG, "worker rejected pane-launch follow-up");
            }
        }
    }

    private void finishSupervisionDrain() {
        synchronized (lock) {
            boolean again = supervisionWork.finishDrain();
            if (again && !worker.post(supervisionDrain)) {
                supervisionWork.rejectDrainPost();
                Log.e(TAG, "worker rejected supervision follow-up");
            }
        }
    }

    private static void assertWorkerThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("SplitHost Binder work reached main thread");
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        if (throwable instanceof InvocationTargetException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }
}
