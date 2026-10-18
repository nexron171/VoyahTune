package ru.big.town.anative;

import ru.big.town.common.HeadlightWidgetProtocol;

/** Keeps the observed switch position separate from an unconfirmed command. */
final class HeadlightWidgetState {
    int mode = HeadlightWidgetProtocol.UNKNOWN;
    int pending = HeadlightWidgetProtocol.UNKNOWN;
    String message = "Нет данных фар";
    private boolean sending;

    static int modeFromFeedback(int value) {
        // OEM LAMP_SWITCH_POSITION_FB: Auto=0, low beam=1, parking=2, Off=3.
        return value >= 0 && value <= 3 ? 3 - value : HeadlightWidgetProtocol.UNKNOWN;
    }

    boolean available() {
        return HeadlightWidgetProtocol.validMode(mode) && pending < 0;
    }

    boolean select(int requested) {
        if (!available() || !HeadlightWidgetProtocol.validMode(requested)) {
            return false;
        }
        pending = requested;
        sending = true;
        message = "Отправка…";
        return true;
    }

    void observed(int value) {
        mode = modeFromFeedback(value);
        if (mode < 0) {
            reset("Нет данных фар");
        } else if (!sending && pending == mode) {
            pending = HeadlightWidgetProtocol.UNKNOWN;
            message = "";
        } else if (pending < 0) {
            message = "";
        }
    }

    void sent(boolean accepted) {
        sending = false;
        if (pending < 0) {
            return;
        }
        if (!accepted) {
            pending = HeadlightWidgetProtocol.UNKNOWN;
            message = "Не удалось отправить";
        } else if (mode == pending) {
            pending = HeadlightWidgetProtocol.UNKNOWN;
            message = "";
        } else {
            message = "Ожидание режима…";
        }
    }

    void timeout() {
        if (pending >= 0) {
            pending = HeadlightWidgetProtocol.UNKNOWN;
            sending = false;
            message = "Режим не подтверждён";
        }
    }

    void reset(String reason) {
        mode = HeadlightWidgetProtocol.UNKNOWN;
        pending = HeadlightWidgetProtocol.UNKNOWN;
        sending = false;
        message = reason;
    }
}
