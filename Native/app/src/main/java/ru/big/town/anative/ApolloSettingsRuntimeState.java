package ru.big.town.anative;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Current-boot transport state used by load.bin for the persisted VehicleSettings UI target. */
final class ApolloSettingsRuntimeState {
    private static final String TAG = "ApolloSettingsFlag";
    private static final File BOOT_ID_FILE = new File("/proc/sys/kernel/random/boot_id");

    private ApolloSettingsRuntimeState() {}

    enum TargetApplyResult {
        CONFIRMED,
        ACCEPTED_UNCONFIRMED,
        TRANSIENT_FAILURE
    }

    /**
     * Applies the persisted target for this boot. The app-private file remains boot-bound so a
     * stale value can never activate the loader before the 10-second restore chain republishes it.
     */
    static TargetApplyResult applyTarget(Context context, boolean enabled) {
        if (context == null) return TargetApplyResult.TRANSIENT_FAILURE;
        boolean previous = isEnabled(context);
        if (previous == enabled) return TargetApplyResult.CONFIRMED;
        if (!setEnabled(context, enabled)) return TargetApplyResult.TRANSIENT_FAILURE;
        if (forceStopVehicleSettings(context)) return TargetApplyResult.CONFIRMED;
        // The desired transport state is already durable for this boot. A later OEM process
        // recreation (or the next restored boot) will still converge even if force-stop failed.
        Log.w(TAG, "VehicleSettings restart failed; target will apply on next process start");
        return TargetApplyResult.ACCEPTED_UNCONFIRMED;
    }

    static boolean isEnabled(Context context) {
        if (context == null) return false;
        File flag = flagFile(context);
        try {
            if (!flag.isFile() || flag.length() > ApolloSettingsRuntimeFlag.MAX_PAYLOAD_CHARS) {
                return false;
            }
            String payload = new String(Files.readAllBytes(flag.toPath()), StandardCharsets.US_ASCII);
            String bootId = readBootId();
            boolean enabled = ApolloSettingsRuntimeFlag.isEnabledForBoot(payload, bootId);
            if (!enabled) deleteQuietly(flag);
            return enabled;
        } catch (Exception e) {
            Log.w(TAG, "Unable to read runtime flag", e);
            deleteQuietly(flag);
            return false;
        }
    }

    static boolean setEnabled(Context context, boolean enabled) {
        if (context == null) return false;
        File flag = flagFile(context);
        if (!enabled) return !flag.exists() || flag.delete();

        File parent = flag.getParentFile();
        File staged = new File(parent, ApolloSettingsRuntimeFlag.FILE_NAME + ".new");
        try {
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return false;
            String payload = ApolloSettingsRuntimeFlag.encodeEnabled(readBootId());
            Files.write(staged.toPath(), payload.getBytes(StandardCharsets.US_ASCII));
            try (java.io.FileOutputStream sync = new java.io.FileOutputStream(staged, true)) {
                sync.getFD().sync();
            }
            Files.move(staged.toPath(), flag.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return isEnabled(context);
        } catch (Exception e) {
            Log.e(TAG, "Unable to publish runtime flag", e);
            deleteQuietly(staged);
            return false;
        }
    }

    static File flagFile(Context context) {
        Context deviceContext = context.createDeviceProtectedStorageContext();
        return new File(deviceContext.getFilesDir(), ApolloSettingsRuntimeFlag.FILE_NAME);
    }

    private static boolean forceStopVehicleSettings(Context context) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            java.lang.reflect.Method forceStop = android.app.ActivityManager.class
                    .getMethod("forceStopPackage", String.class);
            forceStop.invoke(am, "com.qinggan.app.vehiclesetting");
            return true;
        } catch (Exception e) {
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException
                    && e.getCause() != null) ? e.getCause() : e;
            Log.e(TAG, "force-stop VehicleSettings failed", cause);
            return false;
        }
    }

    private static String readBootId() throws java.io.IOException {
        byte[] bytes = Files.readAllBytes(BOOT_ID_FILE.toPath());
        return new String(bytes, StandardCharsets.US_ASCII).trim();
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Unable to remove stale runtime flag");
        }
    }
}
