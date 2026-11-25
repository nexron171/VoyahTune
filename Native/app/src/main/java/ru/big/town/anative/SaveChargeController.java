package ru.big.town.anative;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.function.BooleanSupplier;

final class SaveChargeController {
    private static final OemVehicleStateTransport.StateKey MODE =
            new OemVehicleStateTransport.StateKey(
                    VehicleRestorePolicy.SOC_MODE, VehicleRestorePolicy.SOC_MODE_ID);
    private static final OemVehicleStateTransport.StateKey LEVEL =
            new OemVehicleStateTransport.StateKey(
                    VehicleRestorePolicy.SAVE_CHARGE_LEVEL,
                    VehicleRestorePolicy.SAVE_CHARGE_LEVEL_ID);

    /** Called on CanCommands; never holds the OEM transaction lock while waiting for feedback. */
    static SaveChargeSequence.Result apply(Context context, int percent, BooleanSupplier active) {
        SaveChargeSequence.Result result =
                apply(
                        context,
                        percent,
                        active,
                        () ->
                                OemVehicleStateTransport.sendBundle(
                                                context,
                                                Collections.singletonMap(
                                                        MODE, VehicleRestorePolicy.SOC_SREV),
                                                "voice select SREV before target")
                                        .accepted(),
                        () -> {
                            MainActivity.persistSavedToggle(context, "forcedEv", false);
                            ApplyEngine.noteVehicleMode("energy", "SREV");
                            MainActivity.persistSavedMode(context, "energy", "SREV");
                        });
        Log.i(
                "VoyahVoice",
                "SREV target "
                        + percent
                        + "%: "
                        + result.outcome
                        + ", modeConfirmed="
                        + result.modeConfirmed
                        + ", feedback="
                        + (result.observed == null
                                ? "unavailable"
                                : result.observed.mode + "/" + result.observed.level));
        return result;
    }

    static boolean applyTemporary(
            Context context, int percent, long revision, long cycle, BooleanSupplier active) {
        SaveChargeSequence.Result result =
                apply(
                        context,
                        percent,
                        active,
                        () ->
                                OemVehicleStateTransport.sendTemporaryEnergyMode(
                                                context,
                                                VehicleRestorePolicy.SOC_SREV,
                                                revision,
                                                cycle)
                                        .accepted(),
                        () -> {});
        Log.i("HybridEngine", "Temporary fuel target " + percent + "%: " + result.outcome);
        return result.outcome == SaveChargeSequence.Outcome.CONFIRMED;
    }

    private static SaveChargeSequence.Result apply(
            Context context,
            int percent,
            BooleanSupplier active,
            BooleanSupplier selectFuel,
            Runnable onModeConfirmed) {
        return SaveChargeSequence.run(
                percent,
                new SaveChargeSequence.Vehicle() {
                    @Override
                    public SaveChargeSequence.State read() {
                        Map<OemVehicleStateTransport.StateKey, Integer> state =
                                OemVehicleStateTransport.readVehicleStates(
                                        context, Arrays.asList(MODE, LEVEL));
                        return state == null
                                ? null
                                : new SaveChargeSequence.State(state.get(MODE), state.get(LEVEL));
                    }

                    @Override
                    public boolean selectSrev() {
                        return active.getAsBoolean() && selectFuel.getAsBoolean();
                    }

                    @Override
                    public boolean setLevel(int level) {
                        return active.getAsBoolean()
                                && OemVehicleStateTransport.sendVehicleState(
                                                context,
                                                LEVEL,
                                                level,
                                                "SREV target: " + percent + "%")
                                        .accepted();
                    }

                    @Override
                    public void modeConfirmed() {
                        onModeConfirmed.run();
                    }
                },
                new SaveChargeSequence.Clock() {
                    @Override
                    public long now() {
                        return SystemClock.elapsedRealtime();
                    }

                    @Override
                    public void sleep(long milliseconds) throws InterruptedException {
                        Thread.sleep(milliseconds);
                    }
                },
                active);
    }

    static String error(SaveChargeSequence.Result result, int percent) {
        if (result.outcome == SaveChargeSequence.Outcome.CANCELLED)
            return "Команда отменена или истекло время ожидания";
        if (!result.modeConfirmed)
            return "Не удалось подтвердить режим «Топливо». Уровень заряда не изменён";
        if (result.outcome == SaveChargeSequence.Outcome.MODE_UNCONFIRMED)
            return "Режим «Топливо» изменился во время установки заряда";
        int observedPercent =
                result.observed == null
                        ? -1
                        : VehicleRestorePolicy.saveTargetPercent(result.observed.level);
        String current =
                observedPercent >= 0
                        ? " Сейчас: " + observedPercent + "%."
                        : " Обратное значение недоступно.";
        return "Не подтверждён уровень поддержания заряда " + percent + "%." + current;
    }
}
