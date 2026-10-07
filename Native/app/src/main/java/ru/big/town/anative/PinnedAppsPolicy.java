package ru.big.town.anative;

import java.util.LinkedHashSet;

/**
 * Чистая работа со списком зафиксированных приложений: их не закрывает «Закрыть все» ни в диспетчере
 * задач, ни в кнопке «Закрыть приложения». Хранится CSV-строкой в NativePrefs, поэтому переживает
 * перезагрузку головы и одинаково видна обоим путям закрытия.
 */
final class PinnedAppsPolicy {
    private PinnedAppsPolicy() {}

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

    static boolean contains(String csv, String pkg) {
        if (!isValidPackageName(pkg) || csv == null || csv.isEmpty()) return false;
        for (String pinned : csv.split(",")) {
            if (pkg.equals(pinned)) return true;
        }
        return false;
    }

    /** Добавить или убрать пакет; повторное добавление и удаление не меняют список. */
    static String setPinned(String csv, String pkg, boolean pinned) {
        if (!isValidPackageName(pkg)) return normalizeCsv(csv);
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        String normalized = normalizeCsv(csv);
        if (!normalized.isEmpty()) {
            for (String known : normalized.split(",")) packages.add(known);
        }
        if (pinned) packages.add(pkg); else packages.remove(pkg);
        return normalizeCsv(String.join(",", packages));
    }

    private static boolean isValidPackageName(String pkg) {
        return pkg != null && !pkg.isEmpty() && pkg.matches("[A-Za-z0-9_.]+") && pkg.indexOf('.') > 0;
    }
}
