package ru.big.town.restoremode;

/** Diagnostic names established for BCM_RSM_lightSWReason, not measured light levels. */
final class AutoLightReasonLabel {
    private AutoLightReasonLabel() {}

    static String format(int reason) {
        if (reason < 0) return "SWReason: —";
        String description;
        switch (reason) {
            case 0: description = "День"; break;
            case 1: description = "Прочее"; break;
            case 2: description = "Темно"; break;
            case 3: description = "Тоннель"; break;
            case 4: description = "Запуск в темноте"; break;
            default: description = "Неизвестно"; break;
        }
        return "SWReason: " + reason + " — " + description;
    }
}
