package ru.voyahtune.agent.runtime;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class AndroidMainThread {
    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }

    private AndroidMainThread() {}

    public static void run(Action action) throws Exception {
        Looper main = Looper.getMainLooper();
        if (main == null) throw new IllegalStateException("Android main looper is unavailable");
        run(new Handler(main), action);
    }

    public static void run(Handler handler, Action action) throws Exception {
        if (Looper.myLooper() == handler.getLooper()) {
            action.run();
            return;
        }
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        boolean posted = handler.post(() -> {
            try {
                action.run();
            } catch (Throwable caught) {
                failure.set(caught);
            } finally {
                completed.countDown();
            }
        });
        if (!posted) throw new IllegalStateException("Android looper rejected work");
        boolean interrupted = false;
        for (;;) {
            try {
                if (completed.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)) break;
                if (!handler.getLooper().getThread().isAlive()) throw new IllegalStateException("Android looper stopped before cleanup completed");
            } catch (InterruptedException cancelled) {
                // Cleanup cannot acknowledge completion while accepted UI work is still running.
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure.get() instanceof Error) throw (Error) failure.get();
        if (failure.get() instanceof Exception) throw (Exception) failure.get();
        if (failure.get() != null) throw new IllegalStateException("Android main thread action failed", failure.get());
    }
}
