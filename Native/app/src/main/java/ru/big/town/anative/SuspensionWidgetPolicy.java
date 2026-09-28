package ru.big.town.anative;

/** OEM Launcher semantics: these are drive profiles, not arbitrary height commands. */
final class SuspensionWidgetPolicy {
    static boolean validHeight(int height) { return height >= 1 && height <= 9; }
    static boolean mediumMode(int mode) { return mode == 1 || mode == 2 || mode == 5 || mode == 6; }
    static String driveMode(int selection, int previousMedium) {
        if (selection == 1) return "SPORT";
        if (selection == 3) return "OUTING";
        if (selection != 2) return null;
        switch (previousMedium) {
            case 2: return "COMFORT";
            case 5: return "INDIVIDUAL";
            case 6: return "SNOW";
            default: return "ECO";
        }
    }
    static String lowestBlockedReason(int drive, int inhibit) {
        if (drive == 4) return "Удобная посадка недоступна в Outing";
        if (drive < 1 || drive > 6) return "Нет данных режима движения";
        if (inhibit != 0) return "Удобная посадка сейчас недоступна";
        return null;
    }
    static String blocked(int selection, int height, int maintenance, int inhibit, Integer speed, int drive) {
        if (selection < 0 || selection > 3) return "Неизвестный уровень";
        if (!validHeight(height) || maintenance < 1 || maintenance > 2) return "Нет данных подвески";
        if (maintenance == 2) return "Включён сервисный режим подвески";
        if (selection == 0) {
            String reason = lowestBlockedReason(drive, inhibit);
            if (reason != null) return reason;
        }
        if (selection == 3 && (speed == null || speed < 0)) return "Нет данных скорости";
        if (selection == 3 && speed >= 40) return "Outing доступен при скорости ниже 40 км/ч";
        return null;
    }
    static boolean reached(int selection, int height) {
        return height == new int[]{9, 7, 5, 1}[selection];
    }
}
