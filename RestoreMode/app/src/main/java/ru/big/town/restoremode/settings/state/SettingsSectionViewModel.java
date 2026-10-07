package ru.big.town.restoremode.settings.state;

import android.os.Bundle;

import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;

/** Each Fragment owns one instance; saved data never contains Views or an Activity. */
public final class SettingsSectionViewModel extends ViewModel {
    private static final String STATE = "sectionState";
    private final SavedStateHandle savedState;

    public SettingsSectionViewModel(SavedStateHandle savedState) {
        this.savedState = savedState;
    }

    public Bundle state() {
        Bundle state = savedState.get(STATE);
        return state == null ? new Bundle() : new Bundle(state);
    }

    public void save(Bundle state) {
        savedState.set(STATE, new Bundle(state));
    }
}
