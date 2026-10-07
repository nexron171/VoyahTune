package ru.big.town.restoremode.settings.ui.controls;

import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;

public final class SettingsControls {
    private SettingsControls() {}

    public static void checkRadioByTag(RadioGroup group, String value) {
        if (group == null || value == null) {
            return;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof RadioButton && value.equals(child.getTag())) {
                ((RadioButton) child).setChecked(true);
                return;
            }
        }
    }
}
