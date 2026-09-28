package ru.big.town.restoremode;

/** The fuel-cap result shares the normal success screen, but remains visible longer. */
final class VoiceResultPresentation {
    static String successText(String action, String title, int refillLiters) {
        if (!"port_cap:fuel".equals(action)) return title;
        return refillLiters >= 0 && refillLiters <= 200
                ? "Можно заправить ~" + refillLiters + "л бензина"
                : "Не удалось получить данные о топливе";
    }

    static int successDurationMs(String action) {
        return "port_cap:fuel".equals(action) ? 10000 : 3000;
    }
}
