package ru.big.town.anative;

import android.content.Context;

import ru.big.town.common.QuickControlProtocol;

import java.util.function.Consumer;

/** Serializes one-shot controls from widgets, voice, scenarios and steering buttons. */
final class QuickControlController {
    static void execute(Context context, String action, Consumer<String> completion) {
        String[] result = {"Не удалось выполнить команду"};
        ApplyEngine.postIndependentUserCommand(
                "quick control " + action,
                () -> {
                    if (!QuickControlProtocol.handles(action)) {
                        result[0] = "Неизвестное действие";
                    } else if (action.startsWith("rear_fog:")) {
                        result[0] = RearFogController.execute(context, action);
                    } else {
                        result[0] = HotspotController.execute(context, action);
                    }
                },
                () -> completion.accept(result[0]));
    }

    private QuickControlController() {}
}
