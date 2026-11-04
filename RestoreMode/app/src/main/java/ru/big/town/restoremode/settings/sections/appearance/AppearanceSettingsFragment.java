package ru.big.town.restoremode.settings.sections.appearance;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.appearance.AppearanceSettings;
import ru.big.town.restoremode.appearance.DashboardAppearance;
import ru.big.town.restoremode.appearance.WallpaperStore;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.core.SettingsSectionFragment;
import ru.big.town.restoremode.settings.core.SettingsSectionLayouts;
import ru.big.town.restoremode.settings.ui.controls.SettingsToggle;
import ru.big.town.restoremode.settings.ui.layout.SettingsComponents;
import ru.big.town.restoremode.settings.ui.layout.SettingsFlow;
import ru.big.town.restoremode.settings.ui.layout.SettingsGrid;
import ru.big.town.restoremode.settings.ui.layout.SettingsPanels;
import ru.big.town.restoremode.settings.ui.list.SettingsList;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

public final class AppearanceSettingsFragment extends SettingsSectionFragment {
    private static final int[] COLORS = {
        AppearanceSettings.DEFAULT_BACKGROUND_COLOR, 0xff000000, 0xff31363d, 0xff17372c,
        0xff3a382d, 0xff15283f, 0xff38283c, 0xff4a2428
    };
    private static final String[] COLOR_NAMES = {
        "Стандартный", "Чёрный", "Графитовый", "Зелёный",
        "Золотисто-серый", "Синий", "Фиолетовый", "Бордовый"
    };
    private SettingsComponents components;
    private FrameLayout preview;
    private LinearLayout previewTiles;
    private DashboardAppearance previewAppearance;
    private SettingsList.Row previewRow;
    private final ActivityResultLauncher<PickVisualMediaRequest> wallpaperPicker = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    importWallpaper(uri);
                }
            });

    @Override
    public SettingsSection section() {
        return SettingsSection.APPEARANCE;
    }

    @Override
    protected void onSectionViewCreated(Bundle state) {
        components = new SettingsComponents(requireContext());
        previewRow = new SettingsList.Row("appearance:preview", parent -> createPreview());
    }

    @Override
    protected void addDynamicRows(List<SettingsList.Row> rows, SettingsSectionLayouts.DynamicContent kind) {
        rows.add(new SettingsList.Row("appearance:intro", parent -> styled(components.intro(
                R.drawable.settings_icon_feather, "Оформление главного экрана",
                "Обои, цвет и прозрачность виджетов. Изменения сохраняются сразу."))));
        rows.add(previewRow);
        rows.add(new SettingsList.Row("appearance:widgets", parent -> {
            SettingsGrid grid = new SettingsGrid(requireContext(), null);
            grid.addView(transparencyCard());
            grid.addView(spacingCard());
            return styled(grid);
        }));
        rows.add(new SettingsList.Row("appearance:tileOutline", parent -> {
            SettingsGrid grid = new SettingsGrid(requireContext(), null);
            grid.addView(tileBorderCard());
            grid.addView(tileCornerRadiusCard());
            return styled(grid);
        }));
        rows.add(new SettingsList.Row("appearance:surfaces", parent -> {
            SettingsGrid grid = new SettingsGrid(requireContext(), null);
            grid.addView(colorCard());
            grid.addView(blurCard());
            return styled(grid);
        }));
        rows.add(new SettingsList.Row("appearance:wallpapers", parent -> wallpaperCard()));
    }

    private View createPreview() {
        closePreview();
        preview = new FrameLayout(requireContext());
        preview.setContentDescription("Предпросмотр оформления виджетов");
        LinearLayout tiles = new LinearLayout(requireContext());
        previewTiles = tiles;
        tiles.setGravity(Gravity.CENTER_VERTICAL);
        int spacing = components.dp(AppearanceSettings.spacingDp(preferences));
        tiles.setPadding(components.dp(20), components.dp(24), components.dp(20), components.dp(24));
        String[] labels = {"12:45", "Энергия\n62%", "Радио\nFM 101.2"};
        for (String label : labels) {
            TextView tile = components.text(label, 24, 0xffeef1f6);
            tile.setGravity(Gravity.CENTER);
            tile.setLineSpacing(components.dp(8), 1f);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
            params.setMargins(spacing, spacing, spacing, spacing);
            tiles.addView(tile, params);
        }
        preview.addView(tiles, new FrameLayout.LayoutParams(-1, -1));
        previewAppearance = new DashboardAppearance(preview, preferences);
        previewAppearance.apply(tiles);
        LinearLayout card = components.card(preview);
        preview.setLayoutParams(new LinearLayout.LayoutParams(-1, components.dp(180)));
        return styled(card);
    }

    private View transparencyCard() {
        AppearanceSettings settings = AppearanceSettings.load(preferences);
        SettingsToggle toggle = toggle("Прозрачность виджетов", settings.transparencyEnabled,
                AppearanceSettings.TRANSPARENCY_ENABLED);
        return components.card(
                components.row("Прозрачность виджетов", "Фон и обводка становятся прозрачнее", toggle),
                slider("Прозрачность", settings.transparencyPercent, 100, "%",
                        settings.transparencyEnabled,
                        value -> saveInt(AppearanceSettings.TRANSPARENCY_PERCENT, value)));
    }

    private View spacingCard() {
        return components.card(
                components.text("Расстояние между виджетами", 22, 0xfff3f5fa),
                detail("Общий отступ для водительского и пассажирского экранов. По умолчанию 4 dp."),
                slider("Отступ", AppearanceSettings.spacingDp(preferences),
                        AppearanceSettings.MAX_SPACING_DP, " dp", true,
                        value -> saveInt(AppearanceSettings.TILE_SPACING, value)));
    }

    private View tileBorderCard() {
        return components.card(
                components.text("Толщина рамок плиток", 22, 0xfff3f5fa),
                detail("Обводка плиток главного экрана. 0 отключает рамку, по умолчанию 1 dp."),
                slider("Толщина", AppearanceSettings.load(preferences).borderDp,
                        AppearanceSettings.MAX_BORDER_DP, " dp", true,
                        value -> saveInt(AppearanceSettings.TILE_BORDER, value)));
    }

    private View tileCornerRadiusCard() {
        return components.card(
                components.text("Скругление углов плиток", 22, 0xfff3f5fa),
                detail("Радиус углов плиток главного экрана. 0 делает углы прямыми,"
                        + " по умолчанию 20 dp."),
                slider("Скругление", AppearanceSettings.load(preferences).cornerRadiusDp,
                        AppearanceSettings.MAX_CORNER_RADIUS_DP, " dp", true,
                        value -> saveInt(AppearanceSettings.TILE_CORNER_RADIUS, value)));
    }

    private View blurCard() {
        AppearanceSettings settings = AppearanceSettings.load(preferences);
        SettingsToggle toggle = toggle("Размытие фона", settings.blurEnabled,
                AppearanceSettings.BLUR_ENABLED);
        toggle.setEnabled(settings.transparencyEnabled);
        toggle.setAlpha(settings.transparencyEnabled ? 1f : .4f);
        return components.card(
                components.row("Размытие фона", "Размывает обои под прозрачными виджетами", toggle),
                slider("Степень размытия", settings.blurAmount, 100, "%",
                        settings.transparencyEnabled && settings.blurEnabled,
                        value -> saveInt(AppearanceSettings.BLUR_AMOUNT, value)));
    }

    private View colorCard() {
        int selected = AppearanceSettings.load(preferences).backgroundColor;
        SettingsFlow palette = new SettingsFlow(requireContext(), null);
        for (int index = 0; index < COLORS.length; index++) {
            final int color = COLORS[index];
            View swatch = new View(requireContext());
            swatch.setContentDescription(COLOR_NAMES[index]);
            swatch.setFocusable(true);
            swatch.setSelected(selected == color);
            GradientDrawable fill = new GradientDrawable();
            fill.setShape(GradientDrawable.OVAL);
            fill.setColor(color);
            fill.setStroke(components.dp(selected == color ? 3 : 1),
                    selected == color ? 0xffb5d2ff : 0xff647085);
            swatch.setBackground(fill);
            swatch.setOnClickListener(view -> saveInt(AppearanceSettings.BACKGROUND_COLOR, color));
            palette.addView(swatch, new LinearLayout.LayoutParams(components.dp(42), components.dp(42)));
        }
        Button custom = new Button(requireContext());
        custom.setText("Свой цвет · " + String.format(Locale.ROOT, "#%06X", selected & 0xffffff));
        custom.setOnClickListener(view -> chooseCustomColor());
        LinearLayout card = components.card(components.text("Цвет фона виджетов", 22, 0xfff3f5fa),
                detail("Цвет сохраняется и при выключенной прозрачности"), palette, custom);
        LinearLayout.LayoutParams customLayout = (LinearLayout.LayoutParams) custom.getLayoutParams();
        customLayout.topMargin = components.dp(16);
        custom.setLayoutParams(customLayout);
        return card;
    }

    private void chooseCustomColor() {
        EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setHint("#RRGGBB");
        input.setText(String.format(Locale.ROOT, "#%06X",
                AppearanceSettings.load(preferences).backgroundColor & 0xffffff));
        input.setSelectAllOnFocus(true);
        LinearLayout content = components.column(detail("Введите цвет в формате #RRGGBB"), input);
        content.setPadding(components.dp(24), 0, components.dp(24), 0);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Цвет фона виджетов")
                .setView(content)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Выбрать", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(view -> {
            String value = input.getText().toString().trim();
            if (!value.matches("#?[0-9a-fA-F]{6}")) {
                input.setError("Укажите шесть символов: 0–9, A–F");
                return;
            }
            saveInt(AppearanceSettings.BACKGROUND_COLOR,
                    Color.parseColor(value.startsWith("#") ? value : "#" + value));
            dialog.dismiss();
        }));
        dialog.show();
    }

    private View wallpaperCard() {
        AppearanceSettings.Wallpaper selected = AppearanceSettings.load(preferences).wallpaper;
        SettingsFlow choices = new SettingsFlow(requireContext(), null);
        for (AppearanceSettings.Wallpaper wallpaper : WallpaperStore.available(preferences)) {
            LinearLayout choice = components.column();
            choice.setContentDescription("Обои: " + wallpaper.title);
            choice.setFocusable(true);
            choice.setSelected(wallpaper.equals(selected));
            GradientDrawable border = new GradientDrawable();
            border.setCornerRadius(components.dp(14));
            border.setColor(0xff000000);
            border.setStroke(components.dp(wallpaper.equals(selected) ? 3 : 1),
                    wallpaper.equals(selected) ? 0xffb5d2ff : 0xff516078);
            choice.setBackground(border);
            choice.setClipToOutline(true);
            choice.setPadding(components.dp(5), components.dp(5), components.dp(5), components.dp(5));
            ImageView image = new ImageView(requireContext());
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (wallpaper != AppearanceSettings.Wallpaper.BLACK) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 4;
                image.setImageBitmap(WallpaperStore.decode(requireContext(), wallpaper, options));
            }
            choice.addView(image, new LinearLayout.LayoutParams(-1, components.dp(72)));
            TextView title = components.text(wallpaper.title, 16, 0xffeef1f6);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, components.dp(12), 0, components.dp(8));
            choice.addView(title, new LinearLayout.LayoutParams(-1, -2));
            choice.setOnClickListener(view -> {
                preferences.edit().putString(AppearanceSettings.WALLPAPER, wallpaper.key).apply();
                appearanceChanged();
            });
            choices.addView(choice, new LinearLayout.LayoutParams(components.dp(202), -2));
        }
        Button add = new Button(requireContext());
        add.setText("Добавить свои обои");
        add.setOnClickListener(view -> {
            try {
                wallpaperPicker.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
            } catch (android.content.ActivityNotFoundException missingPicker) {
                Toast.makeText(requireContext(), "Нет приложения для выбора изображений", Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout card = components.card(components.text("Обои", 24, 0xfff3f5fa),
                detail("1920 × 720 · Свои изображения заполняют экран с сохранением пропорций"), choices, add);
        LinearLayout.LayoutParams addLayout = (LinearLayout.LayoutParams) add.getLayoutParams();
        addLayout.topMargin = components.dp(16);
        add.setLayoutParams(addLayout);
        return styled(card);
    }

    private void importWallpaper(Uri uri) {
        Context application = requireContext().getApplicationContext();
        new Thread(() -> {
            try {
                WallpaperStore.importImage(application, preferences, uri);
                application.getMainExecutor().execute(() -> {
                    if (isAdded()) {
                        appearanceChanged();
                    }
                });
            } catch (java.io.IOException | RuntimeException failure) {
                application.getMainExecutor().execute(() -> Toast.makeText(application,
                        "Не удалось добавить изображение", Toast.LENGTH_LONG).show());
            }
        }, "WallpaperImport").start();
    }

    private SettingsToggle toggle(String title, boolean checked, String key) {
        SettingsToggle toggle = new SettingsToggle(requireContext());
        toggle.setContentDescription(title);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((button, value) -> {
            preferences.edit().putBoolean(key, value).apply();
            appearanceChanged();
        });
        return toggle;
    }

    private View slider(String title, int value, int maximum, String unit, boolean enabled, IntConsumer save) {
        TextView label = components.text(title + " · " + value + unit, 18, 0xffb3c3d8);
        label.setPadding(0, components.dp(16), 0, components.dp(4));
        SeekBar slider = new SeekBar(requireContext());
        slider.setContentDescription(title);
        slider.setMax(maximum);
        slider.setProgress(value);
        slider.setEnabled(enabled);
        slider.setProgressTintList(ColorStateList.valueOf(0xffa5c8ff));
        slider.setThumbTintList(ColorStateList.valueOf(0xffa5c8ff));
        slider.setMinimumHeight(components.dp(48));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                label.setText(title + " · " + progress + unit);
                if (fromUser && !bar.isPressed()) {
                    save.accept(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                save.accept(bar.getProgress());
            }
        });
        LinearLayout control = components.column(label, slider);
        control.setAlpha(enabled ? 1f : .4f);
        return control;
    }

    private TextView detail(String text) {
        TextView detail = components.text(text, 17, 0xff9aaac0);
        detail.setPadding(0, components.dp(10), 0, components.dp(18));
        detail.setLineSpacing(0, 1.3f);
        return detail;
    }

    private View styled(View view) {
        SettingsDesign.bindRow(requireActivity(), view);
        return SettingsPanels.spaced(view);
    }

    private void saveInt(String key, int value) {
        preferences.edit().putInt(key, value).apply();
        appearanceChanged();
    }

    private void appearanceChanged() {
        if (previewAppearance != null) {
            int spacing = components.dp(AppearanceSettings.spacingDp(preferences));
            for (int index = 0; index < previewTiles.getChildCount(); index++) {
                View tile = previewTiles.getChildAt(index);
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) tile.getLayoutParams();
                params.setMargins(spacing, spacing, spacing, spacing);
                tile.setLayoutParams(params);
            }
            previewAppearance.apply(previewTiles);
        }
        refreshSettingsRows();
    }

    private void closePreview() {
        if (previewAppearance != null) {
            previewAppearance.close();
            previewAppearance = null;
        }
        preview = null;
        previewTiles = null;
    }

    @Override
    protected void releaseSettingsRow(View row) {
        if (isDescendant(row, preview)) {
            closePreview();
        }
    }

    @Override
    protected void onSectionViewDestroyed() {
        closePreview();
    }
}
