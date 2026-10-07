package ru.big.town.restoremode;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;

/** Card state follows its existing native switch without replacing its listener. */
public final class SettingsWidgetCard extends LinearLayout implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final int toggleId;
    private final SharedPreferences prefs;
    private final Runnable refresh = this::refresh;
    public SettingsWidgetCard(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SettingsWidgetCard);
        toggleId = a.getResourceId(R.styleable.SettingsWidgetCard_settingsToggleRef, 0); a.recycle();
        prefs = context.getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
        setClipToOutline(true);
    }
    private void refresh() {
        Switch toggle = findViewById(toggleId);
        if (toggle == null) return;
        boolean on = toggle.isChecked();
        if (toggleId == R.id.switchShowTripTimer && getContext() instanceof android.app.Activity) SettingsDesign.refreshOverview((android.app.Activity) getContext());
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(SettingsDesign.dp(this, 22));
        bg.setColor(on ? 0xff222a37 : 0xff1c2430); bg.setStroke(SettingsDesign.dp(this, 1), on ? 0xff384457 : 0xff2d3746); setBackground(bg);
        View preview = findViewWithTag("settings.widget-preview");
        if (preview != null) preview.setAlpha(on ? 1f : .45f);
        View note = findViewWithTag("settings.off-note");
        if (note != null) note.setVisibility(on ? GONE : VISIBLE);
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); prefs.registerOnSharedPreferenceChangeListener(this); post(refresh); }
    @Override protected void onDetachedFromWindow() { removeCallbacks(refresh); prefs.unregisterOnSharedPreferenceChangeListener(this); super.onDetachedFromWindow(); }
    @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) { removeCallbacks(refresh); post(refresh); }
}
