package ru.big.town.restoremode.settings.sections.main;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import ru.big.town.common.CarPlayTarget;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.dashboard.tiles.TileOrderStore;
import ru.big.town.restoremode.dashboard.tiles.TileSizeStore;
import ru.big.town.restoremode.settings.ui.preview.SettingsPreviewView;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;
import ru.big.town.restoremode.widgets.apps.AppWidgetStore;

final class CarPlayWidgetSettings {
    private final Context context;
    private final SharedPreferences preferences;
    private final AppWidgetStore.Entry entry;
    private final View root;

    private CarPlayWidgetSettings(
            Context context, ViewGroup parent, SharedPreferences preferences,
            AppWidgetStore.Entry entry) {
        this.context = context;
        this.preferences = preferences;
        this.entry = entry;
        root = LayoutInflater.from(context)
                .inflate(R.layout.settings_main_carplay_widget, parent, false);
    }

    static boolean isDedicated(AppWidgetStore.Entry entry) {
        entry.ensureProfiles();
        return entry.profiles.size() == 1 && CarPlayTarget.isCarPlay(entry.packageName);
    }

    static View create(
            Context context, ViewGroup parent, SharedPreferences preferences,
            AppWidgetStore.Entry entry, BooleanSupplier canAdd, Runnable refreshRows) {
        CarPlayWidgetSettings settings =
                new CarPlayWidgetSettings(context, parent, preferences, entry);
        settings.bind(canAdd, refreshRows);
        SettingsDesign.styleTree(settings.root);
        return settings.root;
    }

    private void bind(BooleanSupplier canAdd, Runnable refreshRows) {
        Switch enabled = root.findViewById(R.id.switchShowCarPlayWidget);
        enabled.setChecked(entry != null);
        root.findViewById(R.id.carPlayWidgetOptions)
                .setVisibility(entry == null ? View.GONE : View.VISIBLE);
        enabled.setOnCheckedChangeListener((button, checked) -> {
            if (checked && entry == null) {
                if (!canAdd.getAsBoolean()) {
                    enabled.setChecked(false);
                    return;
                }
                AppWidgetStore.Entry created = AppWidgetStore.add(preferences, CarPlayTarget.ID);
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
            TileOrderStore.sync(preferences, context.getPackageManager());
            refreshRows.run();
        });

        RadioGroup mode = root.findViewById(R.id.carPlayWidgetMode);
        mode.check(isShortcut() ? R.id.carPlayWidgetShortcutMode : R.id.carPlayWidgetWindowMode);
        updateWindowControls();
        mode.setOnCheckedChangeListener((group, checkedId) -> {
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
                entry.height = Math.max(minimumWindowHeight(),
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
        TileSizeStore.setWidth(preferences, windowSizeKey(), entry.width);
        TileSizeStore.setHeight(preferences, windowSizeKey(), entry.height);
    }

    private void saveEntry() {
        AppWidgetStore.update(preferences, entry);
        TileOrderStore.sync(preferences, context.getPackageManager());
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
        autoStart.setOnCheckedChangeListener((button, checked) -> {
            entry.autoStart = checked;
            saveEntry();
        });
        bindDimension(R.id.carPlayWidgetWidth, 1, 12, entry.width,
                width -> resize(width, entry.height));
        bindDimension(R.id.carPlayWidgetHeight, minimumWindowHeight(), 5, entry.height,
                height -> resize(entry.width, height));
        SettingsPreviewView preview = root.findViewById(R.id.carPlayWidgetGrid);
        preview.setCells(entry.width, entry.height);
        TextView summary = root.findViewById(R.id.carPlayWidgetSizeSummary);
        summary.setText(context.getString(R.string.carplay_widget_size_summary,
                entry.width, entry.height, entry.width * entry.height));
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

    private void bindDimension(int viewId, int minimum, int maximum, int selected,
            IntConsumer onChanged) {
        Spinner spinner = root.findViewById(viewId);
        spinner.setOnItemSelectedListener(null);
        String[] values = new String[maximum - minimum + 1];
        for (int index = 0; index < values.length; index++) {
            int cells = minimum + index;
            values[index] = cells + (cells == 1 ? " ячейка" : cells < 5 ? " ячейки" : " ячеек");
        }
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(context, R.layout.settings_spinner_item, values);
        adapter.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected - minimum);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == parent.getSelectedItemPosition()) {
                    onChanged.accept(position + minimum);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
}
