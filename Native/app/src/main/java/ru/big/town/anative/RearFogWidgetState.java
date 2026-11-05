package ru.big.town.anative;

/** Only vehicle feedback confirms a rear-fog toggle. */
final class RearFogWidgetState {
    int value = -1;
    int pending = -1;
    String message = "";

    boolean available() {
        return value >= 0 && pending < 0;
    }

    boolean toggle() {
        if (!available()) return false;
        pending = 1 - value;
        message = "Противотуманка: отправка…";
        return true;
    }

    void observed(Integer observed) {
        value = observed != null && (observed == 0 || observed == 1) ? observed : -1;
        if (value < 0) {
            pending = -1;
            message = "Нет данных противотуманки";
        } else if (value == pending || pending < 0) {
            pending = -1;
            message = "";
        }
    }

    void sent(String error) {
        if (!error.isEmpty()) {
            pending = -1;
            message = error;
        } else if (pending >= 0) {
            message = "Противотуманка: ожидание…";
        }
    }

    void timeout() {
        if (pending >= 0) {
            pending = -1;
            message = "Противотуманка не подтверждена";
        }
    }
}
