package ru.big.town.anative;

import android.content.Context;
import android.os.SystemClock;

import ru.big.town.common.CarPlayClimateProtocol;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class VehicleClimateActionController {
    private VehicleClimateActionController() {}

    static void execute(
            Context context, String action, BooleanSupplier active, Consumer<String> completion) {
        VehicleClimateAction command = VehicleClimateAction.parse(action);
        if (command == null) {
            completion.accept("Некорректная команда климата или ароматизатора");
            return;
        }
        long deadline = SystemClock.elapsedRealtime() + 7000;
        String[] error = {"Не удалось отправить команду автомобилю"};
        ApplyEngine.postIndependentUserCommand(
                "vehicle " + action,
                () -> {
                    if (!active.getAsBoolean() || SystemClock.elapsedRealtime() >= deadline) {
                        return;
                    }
                    if (command.fragrance) {
                        OemVehicleStateTransport.StateValue timer = command.fragranceTimer();
                        OemVehicleStateTransport.Result result =
                                timer == null
                                        ? OemVehicleStateTransport.sendBundle(
                                                context,
                                                command.fragranceValues(),
                                                "select fragrance")
                                        : OemVehicleStateTransport.sendVehicleStateThenBundle(
                                                context,
                                                timer,
                                                command.fragranceValues(),
                                                "select fragrance with duration");
                        boolean accepted = result.accepted();
                        error[0] = accepted ? null : "Автомобиль не принял выбор ароматизатора";
                        return;
                    }
                    int result =
                            command.applyClimate(
                                    field ->
                                            ClimateControlOperation.apply(
                                                    context,
                                                    field,
                                                    command.value,
                                                    deadline,
                                                    active,
                                                    new OemClimateState[1],
                                                    new long[1]));
                    error[0] =
                            result == CarPlayClimateProtocol.SUCCESS
                                    ? null
                                    : result == CarPlayClimateProtocol.TIMEOUT
                                            ? "Изменение климата не подтверждено"
                                            : "Состояние или управление климатом недоступно";
                },
                () -> completion.accept(error[0]));
    }
}
