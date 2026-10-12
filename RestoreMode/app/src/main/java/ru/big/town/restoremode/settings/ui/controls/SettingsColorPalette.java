package ru.big.town.restoremode.settings.ui.controls;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import ru.big.town.restoremode.settings.ui.layout.SettingsComponents;
import ru.big.town.restoremode.settings.ui.layout.SettingsFlow;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;
import ru.big.town.restoremode.widgets.energy.CarAppearance;

/** Restores the saved car color whenever a settings row is attached. */
public final class SettingsColorPalette extends LinearLayout {
    private static final int[] COLORS = {
        0xff161a20, 0xffe5e5df, 0xff555d66, 0xff28483c, 0xff67394c, 0xff8c7459, 0xff88928c
    };
    private final SharedPreferences preferences;
    private final TextView label;
    private final View[] swatches = new View[COLORS.length];

    public SettingsColorPalette(Context context, AttributeSet attrs) {
        super(context, attrs);
        preferences = context.getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
        setOrientation(VERTICAL);
        SettingsFlow flow = new SettingsFlow(context, null);
        addView(flow, new LayoutParams(-1, -2));
        for (int i = 0; i < COLORS.length; i++) {
            final int index = i;
            View swatch = new View(context);
            swatches[i] = swatch;
            swatch.setContentDescription(CarAppearance.COLOR_NAMES[i]);
            swatch.setFocusable(true);
            swatch.setOnClickListener(
                    v -> {
                        preferences
                                .edit()
                                .putString(
                                        CarAppearance.COLOR_PREFERENCE, CarAppearance.COLORS[index])
                                .apply();
                        refresh(index);
                    });
            flow.addView(
                    swatch,
                    new LayoutParams(SettingsDesign.dp(this, 46), SettingsDesign.dp(this, 46)));
        }
        label = new SettingsComponents(context).text("", 16, 0xffb3c3d8);
        LayoutParams labelLayoutParams = new LayoutParams(-1, -2);
        labelLayoutParams.topMargin = SettingsDesign.dp(this, 12);
        addView(label, labelLayoutParams);
    }

    private void refresh(int selected) {
        for (int i = 0; i < swatches.length; i++) {
            GradientDrawable outer = new GradientDrawable();
            outer.setShape(GradientDrawable.OVAL);
            outer.setColor(0xff222a37);
            outer.setStroke(
                    SettingsDesign.dp(this, i == selected ? 3 : 1),
                    i == selected ? 0xffb5d2ff : 0xff516078);
            GradientDrawable fill = new GradientDrawable();
            fill.setShape(GradientDrawable.OVAL);
            fill.setColor(COLORS[i]);
            android.graphics.drawable.LayerDrawable layers =
                    new android.graphics.drawable.LayerDrawable(
                            new android.graphics.drawable.Drawable[] {outer, fill});
            int inset = SettingsDesign.dp(this, 5);
            layers.setLayerInset(1, inset, inset, inset, inset);
            swatches[i].setBackground(layers);
            swatches[i].setSelected(i == selected);
        }
        label.setText(CarAppearance.COLOR_NAMES[selected]);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        String color = CarAppearance.savedColor(preferences);
        refresh(java.util.Arrays.asList(CarAppearance.COLORS).indexOf(color));
    }
}
