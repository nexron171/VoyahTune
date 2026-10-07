package ru.big.town.restoremode.settings.ui.preview;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

/** Read-only summary of the actual selector, including selections received from the vehicle. */
public final class SettingsModeSummary extends LinearLayout
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final int groupId, rememberId;
    private final SharedPreferences prefs;
    private final TextView title, detail;
    private final ImageView icon;
    private final Runnable refresh = this::refresh;

    public SettingsModeSummary(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SettingsModeSummary);
        groupId = a.getResourceId(R.styleable.SettingsModeSummary_settingsModeGroup, 0);
        rememberId = a.getResourceId(R.styleable.SettingsModeSummary_settingsRememberSwitch, 0);
        int art = a.getResourceId(R.styleable.SettingsModeSummary_settingsModeIcon, 0);
        a.recycle();
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(25), dp(21), dp(25), dp(21));
        icon = new ImageView(context);
        icon.setImageResource(art);
        LayoutParams ip = new LayoutParams(dp(54), dp(54));
        ip.rightMargin = dp(20);
        addView(icon, ip);
        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(VERTICAL);
        title = new TextView(context);
        title.setTextSize(34);
        title.setIncludeFontPadding(false);
        title.setTypeface(getResources().getFont(R.font.settings_arimo_regular));
        detail = new TextView(context);
        detail.setTextSize(15);
        detail.setTextColor(0xff92a6c0);
        detail.setIncludeFontPadding(false);
        copy.addView(title);
        LayoutParams dp = new LayoutParams(-1, -2);
        dp.topMargin = dp(6);
        copy.addView(detail, dp);
        addView(copy, new LayoutParams(0, -2, 1));
        prefs = context.getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
    }

    private int dp(int n) {
        return SettingsDesign.dp(this, n);
    }

    private void refresh() {
        RadioGroup group = getRootView().findViewById(groupId);
        if (group == null) {
            return;
        }
        TextView selected = getRootView().findViewById(group.getCheckedRadioButtonId());
        if (selected == null) {
            return;
        }
        int color = SettingsDesign.choiceColor(selected);
        title.setText(selected.getText());
        title.setTextColor(color);
        icon.setColorFilter(color);
        // The head icon belongs to the same selected-mode palette.
        if (getParent() instanceof LinearLayout) {
            LinearLayout card = (LinearLayout) getParent();
            if (card.getChildAt(0) instanceof LinearLayout) {
                LinearLayout head = (LinearLayout) card.getChildAt(0);
                if (head.getChildAt(0) instanceof ImageView) {
                    ImageView headIcon = (ImageView) head.getChildAt(0);
                    headIcon.setColorFilter(color);
                    GradientDrawable iconBg = new GradientDrawable();
                    iconBg.setCornerRadius(dp(14));
                    iconBg.setColor(
                            androidx.core.graphics.ColorUtils.blendARGB(0xff243448, color, .18f));
                    headIcon.setBackground(iconBg);
                }
            }
        }
        Switch remember = getRootView().findViewById(rememberId);
        detail.setText(
                remember != null && remember.isChecked()
                        ? "Последнее выбранное значение"
                        : "Выбранное значение при восстановлении");
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(androidx.core.graphics.ColorUtils.blendARGB(0xff172434, color, .12f));
        bg.setStroke(dp(1), androidx.core.graphics.ColorUtils.blendARGB(0xff344258, color, .48f));
        setBackground(bg);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        prefs.registerOnSharedPreferenceChangeListener(this);
        post(refresh);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(refresh);
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        removeCallbacks(refresh);
        post(refresh);
    }
}
