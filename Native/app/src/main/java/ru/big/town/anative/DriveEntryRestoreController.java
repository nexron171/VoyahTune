package ru.big.town.anative;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.core.content.ContextCompat;

import ru.big.town.common.SmartSaveSettings;

/** OD opt-in restoration; snapshots never create a door opening or Drive entry. */
final class DriveEntryRestoreController implements AutoCloseable {
    private final Context context;
    private final HandlerThread thread = new HandlerThread("DriveEntryRestore");
    private final Handler handler;
    private final CanBusEventHub.Subscription subscription;
    private ModeRestoreTriggers triggers = new ModeRestoreTriggers();
    private boolean enabled;
    private volatile boolean closed;
    private volatile long generation;
    private final BroadcastReceiver receiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    refreshEnabled();
                }
            };

    DriveEntryRestoreController(Context context) {
        this.context = context.getApplicationContext();
        thread.start();
        handler = new Handler(thread.getLooper());
        ContextCompat.registerReceiver(
                context,
                receiver,
                new IntentFilter(SmartSaveSettings.CHANGED),
                "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE",
                handler,
                ContextCompat.RECEIVER_EXPORTED);
        handler.post(this::refreshEnabled);
        subscription =
                CanBusEventHub.get(context)
                        .subscribe(
                                CanBusEventRouter.INTEREST_CONNECTION
                                        | CanBusEventRouter.INTEREST_DOOR
                                        | CanBusEventRouter.INTEREST_GEAR,
                                new int[0],
                                handler,
                                this::onEvent);
    }

    static boolean enabledNow(Context context) {
        try {
            Bundle state =
                    context.getContentResolver()
                            .call(
                                    Uri.parse(
                                            "content://ru.big.town.restoremode.restoremodecontentprovider/"),
                                    "driveHookV2",
                                    "snapshot",
                                    null);
            return state != null
                    && state.getInt("acc", -1) == 2
                    && state.getBoolean("applySettingsOnDrive", false);
        } catch (RuntimeException error) {
            Log.w("DriveEntryRestore", "Settings unavailable", error);
            return false;
        }
    }

    private void refreshEnabled() {
        boolean next = !closed && enabledNow(context);
        if (enabled != next) {
            generation++;
            triggers = new ModeRestoreTriggers();
            enabled = next;
        }
    }

    private void onEvent(CanBusEvent event) {
        if (closed) return;
        if (event.kind == CanBusEvent.Kind.CONNECTION
                || event.kind == CanBusEvent.Kind.CONNECTION_LOST) {
            generation++;
            triggers = new ModeRestoreTriggers();
            refreshEnabled();
            return;
        }
        if (event.origin != CanBusEvent.Origin.LIVE) return;
        if (event.kind == CanBusEvent.Kind.DOOR) {
            refreshEnabled();
            if (enabled) triggers.onDoor(event.first);
        } else if (event.kind == CanBusEvent.Kind.GEAR) {
            refreshEnabled();
            if (enabled && triggers.onGear(event.first)) {
                long requestGeneration = generation;
                ApplyEngine.scheduleDriveEntryApply(
                        () -> !closed && generation == requestGeneration && enabledNow(context));
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        generation++;
        context.unregisterReceiver(receiver);
        subscription.close();
        thread.quitSafely();
    }
}
