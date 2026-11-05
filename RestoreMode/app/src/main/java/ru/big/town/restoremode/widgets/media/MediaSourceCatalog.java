package ru.big.town.restoremode.widgets.media;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Enumerates launchable media applications and renders their icons for the card picker. */
final class MediaSourceCatalog {
    private static final String MEDIA_BROWSER_SERVICE = "android.media.browse.MediaBrowserService";
    private static final String LEGACY_MUSIC_PLAYER = "android.intent.action.MUSIC_PLAYER";
    private static final int ICON_SIZE_PX = 128;

    private static final List<String> INFRASTRUCTURE_PACKAGES = List.of(
            "ru.big.town.anative",
            "ru.big.town.updater",
            "com.android.systemui");

    /** Applications that advertise a media surface but are not players. */
    private static final List<String> EXCLUDED_PACKAGES = List.of("com.huawei.hidisk.fa");
    private static final List<String> SYSTEM_PREFIXES = List.of(
            "com.qinggan",
            "com.android",
            "com.google",
            "com.tencent",
            "com.huawei",
            "com.baidu",
            "com.pateo");

    private MediaSourceCatalog() {}

    /** Blocking enumeration; call from a worker thread. */
    static List<MediaSource> load(Context context, Map<String, Bitmap> iconCache) {
        PackageManager packageManager = context.getPackageManager();
        Map<String, String> labels = candidates(context, packageManager);
        List<MediaSource> sources = new ArrayList<>();
        for (String packageName : order(new ArrayList<>(labels.keySet()), labels)) {
            sources.add(
                    new MediaSource(
                            packageName,
                            labels.get(packageName),
                            icon(packageManager, packageName, iconCache)));
        }
        return sources;
    }

    /** Cached application icon, null when the package is gone. */
    static Bitmap icon(
            PackageManager packageManager, String packageName, Map<String, Bitmap> iconCache) {
        Bitmap cached = iconCache.get(packageName);
        if (cached != null) {
            return cached;
        }
        Bitmap rendered = null;
        try {
            rendered = render(packageManager.getApplicationIcon(packageName));
        } catch (Exception ignored) {
        }
        if (rendered != null) {
            iconCache.put(packageName, rendered);
        }
        return rendered;
    }

    /** System players go last, alphabetical order inside both groups. */
    static List<String> order(List<String> packageNames, Map<String, String> labels) {
        List<String> ordered = new ArrayList<>(packageNames);
        ordered.sort(
                (first, second) -> {
                    boolean firstSystem = isSystemPackage(first);
                    boolean secondSystem = isSystemPackage(second);
                    if (firstSystem != secondSystem) {
                        return firstSystem ? 1 : -1;
                    }
                    return labelOf(first, labels).compareToIgnoreCase(labelOf(second, labels));
                });
        return ordered;
    }

    static boolean isSystemPackage(String packageName) {
        for (String prefix : SYSTEM_PREFIXES) {
            if (packageName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static boolean isHidden(String packageName, String ownPackage) {
        return packageName.equals(ownPackage)
                || INFRASTRUCTURE_PACKAGES.contains(packageName)
                || EXCLUDED_PACKAGES.contains(packageName);
    }

    private static String labelOf(String packageName, Map<String, String> labels) {
        String label = labels.get(packageName);
        return label == null || label.isEmpty() ? packageName : label;
    }

    private static Bitmap render(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, ICON_SIZE_PX, ICON_SIZE_PX);
        drawable.draw(canvas);
        return bitmap;
    }

    private static Map<String, String> candidates(
            Context context, PackageManager packageManager) {
        Map<String, String> labels = new LinkedHashMap<>();
        collectActivities(
                packageManager,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC),
                labels);
        collectActivities(packageManager, new Intent(LEGACY_MUSIC_PLAYER), labels);
        collectServices(packageManager, new Intent(MEDIA_BROWSER_SERVICE), labels);
        if (labels.isEmpty()) {
            // Some players do not advertise a media category. Keep the picker usable anyway.
            collectActivities(
                    packageManager,
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    labels);
        }

        String ownPackage = context.getPackageName();
        labels.keySet()
                .removeIf(
                        packageName ->
                                isHidden(packageName, ownPackage)
                                        || packageManager.getLaunchIntentForPackage(packageName)
                                                == null);
        return labels;
    }

    private static void collectActivities(
            PackageManager packageManager, Intent intent, Map<String, String> labels) {
        List<ResolveInfo> resolved = packageManager.queryIntentActivities(intent, 0);
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) {
                continue;
            }
            labels.putIfAbsent(
                    info.activityInfo.packageName,
                    info.loadLabel(packageManager).toString());
        }
    }

    private static void collectServices(
            PackageManager packageManager, Intent intent, Map<String, String> labels) {
        List<ResolveInfo> resolved = packageManager.queryIntentServices(intent, 0);
        for (ResolveInfo info : resolved) {
            if (info.serviceInfo == null) {
                continue;
            }
            labels.putIfAbsent(
                    info.serviceInfo.packageName,
                    info.loadLabel(packageManager).toString());
        }
    }
}
