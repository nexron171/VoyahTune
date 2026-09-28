package ru.big.town.anative;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.function.BooleanSupplier;

final class SaveChargeController {
    private static final OemVehicleStateTransport.StateKey MODE = new OemVehicleStateTransport.StateKey(
            VehicleRestorePolicy.SOC_MODE, VehicleRestorePolicy.SOC_MODE_ID);
    private static final OemVehicleStateTransport.StateKey LEVEL = new OemVehicleStateTransport.StateKey(
            VehicleRestorePolicy.SAVE_CHARGE_LEVEL, VehicleRestorePolicy.SAVE_CHARGE_LEVEL_ID);

    /** Called on CanCommands; never holds the OEM transaction lock while waiting for feedback. */
    static SaveChargeSequence.Result apply(Context context, int percent, BooleanSupplier active) {
        SaveChargeSequence.Result result = SaveChargeSequence.run(percent, new SaveChargeSequence.Vehicle() {
            @Override public SaveChargeSequence.State read() {
                // Debug transport returns invented zeroes: they must not confirm a real target.
                if (CanSender.isDebugMode()) return null;
                Map<OemVehicleStateTransport.StateKey, Integer> state =
                        OemVehicleStateTransport.readVehicleStates(context, Arrays.asList(MODE, LEVEL));
                return state == null ? null : new SaveChargeSequence.State(state.get(MODE), state.get(LEVEL));
            }
            @Override public boolean selectSrev() {
                return active.getAsBoolean() && OemVehicleStateTransport.sendBundle(context,
                        Collections.singletonMap(MODE, VehicleRestorePolicy.SOC_SREV), "voice select SREV before target").accepted();
            }
            @Override public boolean setLevel(int level) {
                return active.getAsBoolean() && OemVehicleStateTransport.sendVehicleState(context,
                        LEVEL, level, "voice SREV target: " + percent + "%").accepted();
            }
            @Override public void modeConfirmed() {
                MainActivity.persistSavedToggle(context, "forcedEv", false);
                ApplyEngine.noteVehicleMode("energy", "SREV");
                MainActivity.persistSavedMode(context, "energy", "SREV");
            }
        }, new SaveChargeSequence.Clock() {
            @Override public long now() { return SystemClock.elapsedRealtime(); }
            @Override public void sleep(long milliseconds) throws InterruptedException { Thread.sleep(milliseconds); }
        }, active);
        Log.i("VoyahVoice", "SREV target " + percent + "%: " + result.outcome
                + ", modeConfirmed=" + result.modeConfirmed + ", feedback="
                + (result.observed == null ? "unavailable" : result.observed.mode + "/" + result.observed.level));
        return result;
    }

    static String error(SaveChargeSequence.Result result, int percent) {
        if (result.outcome == SaveChargeSequence.Outcome.CANCELLED) return "Команда отменена или истекло время ожидания";
        if (!result.modeConfirmed) return "Не удалось подтвердить режим «Топливо». Уровень заряда не изменён";
        if (result.outcome == SaveChargeSequence.Outcome.MODE_UNCONFIRMED) return "Режим «Топливо» изменился во время установки заряда";
        String current = result.observed != null && result.observed.level >= 0 && result.observed.level <= 11
                ? " Сейчас: " + (25 + result.observed.level * 5) + "%." : " Обратное значение недоступно.";
        return "Не подтверждён уровень поддержания заряда " + percent + "%." + current;
    }
}
