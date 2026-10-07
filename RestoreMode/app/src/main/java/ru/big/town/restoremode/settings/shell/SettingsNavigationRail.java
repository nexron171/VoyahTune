package ru.big.town.restoremode.settings.shell;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

import java.util.function.Consumer;

public final class SettingsNavigationRail extends FrameLayout {
    public SettingsNavigationRail(Context context, AttributeSet attributes) {
        super(context, attributes);
        LayoutInflater.from(context).inflate(R.layout.view_settings_navigation_rail, this, true);
        SettingsDesign.styleTree(this);
        for (SettingsSection section : SettingsSection.values()) {
            TextView item = findViewById(section.navigationId);
            SettingsDesign.styleNavigationItem(item);
        }
    }

    public void onSectionSelected(Consumer<SettingsSection> listener) {
        for (SettingsSection section : SettingsSection.values()) {
            findViewById(section.navigationId).setOnClickListener(view -> listener.accept(section));
        }
    }

    public void onBack(Runnable listener) {
        findViewById(R.id.buttonBack).setOnClickListener(view -> listener.run());
    }

    public void select(SettingsSection section) {
        for (SettingsSection candidate : SettingsSection.values()) {
            findViewById(candidate.navigationId).setSelected(candidate == section);
        }
    }

    public void showCustomCommands(boolean visible) {
        findViewById(R.id.navCustomCommands).setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
