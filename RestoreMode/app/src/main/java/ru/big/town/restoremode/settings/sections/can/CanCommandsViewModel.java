package ru.big.town.restoremode.settings.sections.can;

import android.content.SharedPreferences;

import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;

import ru.big.town.restoremode.vehicle.steering.SteeringCanCommandPolicy;

import java.util.Arrays;

/** Activity scope lets Apply and Back validate a draft even when the CAN section has no View. */
public final class CanCommandsViewModel extends ViewModel {
    private final SavedStateHandle state;

    public CanCommandsViewModel(SavedStateHandle state) {
        this.state = state;
    }

    public void initialize(String text, int count) {
        if (!state.contains("commands")) {
            setText(text);
            setCount(count);
        }
    }

    public String text() {
        String text = state.get("commands");
        return text == null ? "" : text;
    }

    public int count() {
        Integer count = state.get("count");
        return count == null ? 1 : count;
    }

    void setText(String text) {
        state.set("commands", text);
    }

    void setCount(int count) {
        state.set("count", count);
    }

    boolean isValid() {
        return Arrays.stream(text().split("\n"))
                .allMatch(line -> line.trim().isEmpty() || SteeringCanCommandPolicy.isValid(line));
    }

    public boolean save(SharedPreferences preferences) {
        if (!isValid()) {
            return false;
        }
        preferences
                .edit()
                .putString("customCommand", text())
                .putInt("customCommandCount", count())
                .apply();
        return true;
    }
}
