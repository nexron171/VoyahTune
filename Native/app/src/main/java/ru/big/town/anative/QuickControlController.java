package ru.big.town.anative;

import android.content.Context;

import ru.big.town.common.QuickControlProtocol;

import java.util.function.Consumer;

/** Serializes one-shot controls from widgets, voice, scenarios and steering buttons. */
final class QuickControlController {
    static void execute(Context context, String action, Consumer<String> completion) {
        executeWithResult(context, action, result -> completion.accept(result.error));
    }

    static void executeWithResult(
            Context context, String action, Consumer<ActionResult> completion) {
        String[] resolved = {action};
        String[] result = {"Не удалось выполнить команду"};
        ApplyEngine.postIndependentUserCommand(
                "quick control " + action,
                () -> {
                    if (!QuickControlProtocol.handles(action)) {
                        result[0] = "Неизвестное действие";
                    } else if (action.startsWith("rear_fog:")) {
                        result[0] =
                                RearFogController.execute(
                                        context, action, target -> resolved[0] = target);
                    } else {
                        result[0] =
                                HotspotController.execute(
                                        context, action, target -> resolved[0] = target);
                    }
                },
                () -> completion.accept(ActionResult.from(resolved[0], result[0])));
    }

    private QuickControlController() {}
}
