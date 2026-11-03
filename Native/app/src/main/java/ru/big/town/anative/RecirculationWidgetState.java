package ru.big.town.anative;

import ru.big.town.common.CarPlayClimateProtocol;
import ru.big.town.common.RecirculationWidgetProtocol;

final class RecirculationWidgetState {
    int mode = RecirculationWidgetProtocol.UNKNOWN;
    boolean pending;
    String message = "Нет данных рециркуляции";

    boolean available() {
        return RecirculationWidgetProtocol.validMode(mode) && !pending;
    }

    boolean begin() {
        if (!available()) return false;
        pending = true;
        message = "Переключение…";
        return true;
    }

    void observe(int observed) {
        int next =
                RecirculationWidgetProtocol.validMode(observed)
                        ? observed
                        : RecirculationWidgetProtocol.UNKNOWN;
        if (!pending && next != mode) message = "";
        mode = next;
        if (mode == RecirculationWidgetProtocol.UNKNOWN) message = "Нет данных рециркуляции";
    }

    void complete(int result) {
        pending = false;
        message =
                result == CarPlayClimateProtocol.SUCCESS
                        ? ""
                        : result == CarPlayClimateProtocol.TIMEOUT
                                ? "Режим не подтверждён автомобилем"
                                : "Не удалось переключить режим";
    }

    void reset(String reason) {
        mode = RecirculationWidgetProtocol.UNKNOWN;
        pending = false;
        message = reason;
    }
}
