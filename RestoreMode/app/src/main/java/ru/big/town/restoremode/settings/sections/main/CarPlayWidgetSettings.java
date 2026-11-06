package ru.big.town.restoremode.settings.sections.main;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import ru.big.town.common.CarPlayTarget;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.dashboard.DashboardProfile;
import ru.big.town.restoremode.dashboard.tiles.TileOrderStore;
import ru.big.town.restoremode.dashboard.tiles.TileSizeStore;
import ru.big.town.restoremode.settings.ui.preview.WidgetSizeGrid;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;
import ru.big.town.restoremode.widgets.apps.AppWidgetStore;

import java.util.function.BooleanSupplier;

final class CarPlayWidgetSettings {
    private final Context context;
    private final SharedPreferences preferences;
    private final DashboardProfile profile;
    private final AppWidgetStore.Entry entry;
    private final View root;

    private CarPlayWidgetSettings(
            Context context,
            ViewGroup parent,
            SharedPreferences preferences,
            DashboardProfile profile,
            AppWidgetStore.Entry entry) {
        this.context = context;
        this.preferences = preferences;
        this.profile = profile;
        this.entry = entry;
        root =
                LayoutInflater.from(context)
                        .inflate(R.layout.settings_main_carplay_widget, parent, false);
    }

    static boolean isDedicated(AppWidgetStore.Entry entry) {
        entry.ensureProfiles();
        return entry.profiles.size() == 1 && CarPlayTarget.isCarPlay(entry.packageName);
    }

    static View create(
            Context context,
            ViewGroup parent,
            SharedPreferences preferences,
            DashboardProfile profile,
            AppWidgetStore.Entry entry,
            BooleanSupplier canAdd,
            Runnable refreshRows) {
        CarPlayWidgetSettings settings =
                new CarPlayWidgetSettings(context, parent, preferences, profile, entry);
        settings.bind(canAdd, refreshRows);
        SettingsDesign.styleTree(settings.root);
        return settings.root;
    }

    private void bind(BooleanSupplier canAdd, Runnable refreshRows) {
        Switch enabled = root.findViewById(R.id.switchShowCarPlayWidget);
        enabled.setChecked(entry != null);
        root.findViewById(R.id.carPlayWidgetOptions)
                .setVisibility(entry == null ? View.GONE : View.VISIBLE);
        enabled.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (checked && entry == null) {
                        if (!ru.big.town.restoremode.carplay.CarPlayFeature.isEnabled(context)
                                || !canAdd.getAsBoolean()) {
                            enabled.setChecked(false);
                            return;
                        }
                        AppWidgetStore.Entry created =
                                AppWidgetStore.add(
                                        preferences, CarPlayTarget.ID, profile.widgetIdPrefix());
                        if (created == null) {
                            enabled.setChecked(false);
                            return;
                        }
                        created.width = 1;
                        created.height = 1;
                        AppWidgetStore.update(preferences, created);
                    } else if (!checked && entry != null) {
                        AppWidgetStore.remove(preferences, entry.id);
                    } else {
                        return;
                    }
                    syncTiles();
                    refreshRows.run();
                });

        RadioGroup mode = root.findViewById(R.id.carPlayWidgetMode);
        mode.check(isShortcut() ? R.id.carPlayWidgetShortcutMode : R.id.carPlayWidgetWindowMode);
        updateWindowControls();
        mode.setOnCheckedChangeListener(
                (group, checkedId) -> {
                    if (entry == null) {
                        return;
                    }
                    boolean shortcut = checkedId == R.id.carPlayWidgetShortcutMode;
                    if (shortcut == isShortcut()) {
                        return;
                    }
                    if (shortcut) {
                        rememberWindowSize();
                        entry.width = 1;
                        entry.height = 1;
                    } else {
                        entry.width = TileSizeStore.width(preferences, windowSizeKey(), 8);
                        entry.height =
                                Math.max(
                                        minimumWindowHeight(),
                                        TileSizeStore.height(preferences, windowSizeKey(), 5));
                    }
                    saveEntry();
                    updateWindowControls();
                });
    }

    private boolean isShortcut() {
        return entry == null
                || CarPlayTarget.opensActivity(entry.packageName, entry.width, entry.height);
    }

    private String windowSizeKey() {
        return "carPlayWindow:" + entry.id;
    }

    private int minimumWindowHeight() {
        // 1x1 is reserved for the fullscreen shortcut by the shared launch contract.
        return entry.width == 1 ? 2 : 1;
    }

    private void rememberWindowSize() {
        TileSizeStore.setSize(preferences, windowSizeKey(), entry.width, entry.height);
    }

    private void saveEntry() {
        AppWidgetStore.update(preferences, entry);
        syncTiles();
    }

    private void syncTiles() {
        TileOrderStore.sync(
                preferences,
                profile.settingsPreferences(context),
                context.getPackageManager(),
                profile.isPassenger());
    }

    private void updateWindowControls() {
        boolean shortcut = isShortcut();
        root.findViewById(R.id.carPlayWidgetShortcutHint)
                .setVisibility(shortcut ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.carPlayWidgetWindowOptions)
                .setVisibility(shortcut ? View.GONE : View.VISIBLE);
        if (shortcut) {
            return;
        }
        Switch autoStart = root.findViewById(R.id.carPlayWidgetAutoStart);
        autoStart.setOnCheckedChangeListener(null);
        autoStart.setChecked(entry.autoStart);
        autoStart.setOnCheckedChangeListener(
                (button, checked) -> {
                    entry.autoStart = checked;
                    saveEntry();
                });
        WidgetSizeGrid preview = root.findViewById(R.id.carPlayWidgetGrid);
        preview.bind(
                entry.width,
                entry.height,
                (columns, rows) -> !CarPlayTarget.opensActivity(entry.packageName, columns, rows),
                this::resize);
        TextView summary = root.findViewById(R.id.carPlayWidgetSizeSummary);
        summary.setText(
                context.getString(
                        R.string.carplay_widget_size_summary,
                        entry.width,
                        entry.height,
                        entry.width * entry.height));
    }

    private void resize(int width, int height) {
        int rows = Math.max(width == 1 ? 2 : 1, height);
        if (isShortcut() || (entry.width == width && entry.height == rows)) {
            return;
        }
        entry.width = width;
        entry.height = rows;
        rememberWindowSize();
        saveEntry();
        updateWindowControls();
    }
}
