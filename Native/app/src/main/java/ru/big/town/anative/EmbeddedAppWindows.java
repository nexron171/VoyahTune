package ru.big.town.anative;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Shares live Native ownership with the OEM launcher bridge. All calls run on main. */
final class EmbeddedAppWindows {
    private static final EmbeddedAppLaunchCoordinator COORDINATOR =
            new EmbeddedAppLaunchCoordinator();
    private static Context application;

    private EmbeddedAppWindows() {}

    static void initialize(Context context) {
        application = context.getApplicationContext();
        publish();
    }

    static void register(
            Context context,
            Object owner,
            String packageName,
            int displayId,
            Consumer<Runnable> close) {
        application = context.getApplicationContext();
        COORDINATOR.register(owner, packageName, displayId, close);
        publish();
    }

    static void remove(Object owner) {
        COORDINATOR.remove(owner);
        publish();
    }

    static void prepare(String packageName, int displayId, BooleanSupplier valid, Runnable launch) {
        COORDINATOR.prepare(packageName, displayId, valid, launch);
    }

    private static void publish() {
        if (application == null) return;
        try {
            Settings.Global.putString(
                    application.getContentResolver(),
                    "voyahtune_embedded_apps",
                    String.join(",", COORDINATOR.packages()));
        } catch (RuntimeException error) {
            Log.w("EmbeddedAppWindows", "Cannot publish embedded application ownership", error);
        }
    }
}
