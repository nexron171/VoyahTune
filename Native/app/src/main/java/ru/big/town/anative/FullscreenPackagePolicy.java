package ru.big.town.anative;

import java.util.LinkedHashSet;

/** Чистая нормализация списка полноэкранных пакетов для Native и Frida-конфига. */
final class FullscreenPackagePolicy {
    private FullscreenPackagePolicy() {}

    static String normalizeCsv(String csv) {
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        if (csv != null) {
            for (String raw : csv.split(",")) {
                String pkg = raw.trim();
                if (isValidPackageName(pkg)) packages.add(pkg);
            }
        }
        StringBuilder normalized = new StringBuilder();
        for (String pkg : packages) {
            if (normalized.length() > 0) normalized.append(',');
            normalized.append(pkg);
        }
        return normalized.toString();
    }

    static boolean contains(String normalizedCsv, String pkg) {
        if (!isValidPackageName(pkg) || normalizedCsv == null || normalizedCsv.isEmpty()) return false;
        for (String configured : normalizedCsv.split(",")) {
            if (pkg.equals(configured)) return true;
        }
        return false;
    }

    static boolean requiresAccessibilityService(
            boolean persistentOverlay, boolean steeringBack, String packagesCsv) {
        return persistentOverlay || steeringBack || (packagesCsv != null && !packagesCsv.isEmpty());
    }

    static boolean shouldShowOverlay(boolean persistentOverlay, String packagesCsv, String topPackage) {
        return persistentOverlay || contains(packagesCsv, topPackage);
    }

    private static boolean isValidPackageName(String pkg) {
        return pkg != null && pkg.indexOf('.') > 0 && pkg.matches("[A-Za-z0-9_.]+");
    }
}
