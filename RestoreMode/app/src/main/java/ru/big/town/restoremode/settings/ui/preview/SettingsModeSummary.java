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

import androidx.core.graphics.ColorUtils;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

/** Read-only summary of the actual selector, including selections received from the vehicle. */
public final class SettingsModeSummary extends LinearLayout
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final int modeGroupId;
    private final int rememberSwitchId;
    private final int defaultIconResource;
    private final SharedPreferences preferences;
    private final TextView titleView;
    private final TextView detailView;
    private final ImageView iconView;
    private final Runnable refreshTask = this::refreshFromSelection;

    public SettingsModeSummary(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray attributes =
                context.obtainStyledAttributes(attrs, R.styleable.SettingsModeSummary);
        modeGroupId =
                attributes.getResourceId(R.styleable.SettingsModeSummary_settingsModeGroup, 0);
        rememberSwitchId =
                attributes.getResourceId(R.styleable.SettingsModeSummary_settingsRememberSwitch, 0);
        defaultIconResource =
                attributes.getResourceId(R.styleable.SettingsModeSummary_settingsModeIcon, 0);
        attributes.recycle();

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(25), dp(21), dp(25), dp(21));

        iconView = new ImageView(context);
        iconView.setImageResource(defaultIconResource);
        LayoutParams iconLayoutParams = new LayoutParams(dp(54), dp(54));
        iconLayoutParams.rightMargin = dp(20);
        addView(iconView, iconLayoutParams);

        LinearLayout textContainer = new LinearLayout(context);
        textContainer.setOrientation(VERTICAL);

        titleView = new TextView(context);
        titleView.setTextSize(34);
        titleView.setIncludeFontPadding(false);
        titleView.setTypeface(getResources().getFont(R.font.settings_arimo_regular));
        textContainer.addView(titleView);

        detailView = new TextView(context);
        detailView.setTextSize(15);
        detailView.setTextColor(0xff92a6c0);
        detailView.setIncludeFontPadding(false);
        LayoutParams detailLayoutParams =
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        detailLayoutParams.topMargin = dp(6);
        textContainer.addView(detailView, detailLayoutParams);
        addView(textContainer, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));

        preferences = context.getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
    }

    private int dp(int sizeDp) {
        return SettingsDesign.dp(this, sizeDp);
    }

    private void refreshFromSelection() {
        RadioGroup modeGroup = getRootView().findViewById(modeGroupId);
        if (modeGroup == null) {
            return;
        }

        TextView selectedMode = modeGroup.findViewById(modeGroup.getCheckedRadioButtonId());
        if (selectedMode == null) {
            return;
        }

        int accentColor = SettingsDesign.choiceColor(selectedMode);
        int iconResource = iconForSelectedMode(selectedMode.getId());
        titleView.setText(selectedMode.getText());
        titleView.setTextColor(accentColor);
        iconView.setImageResource(iconResource);
        iconView.setColorFilter(accentColor);
        updateHeaderIcon(iconResource, accentColor);

        Switch rememberSwitch = getRootView().findViewById(rememberSwitchId);
        detailView.setText(
                rememberSwitch != null && rememberSwitch.isChecked()
                        ? "Последнее выбранное значение"
                        : "Выбранное значение при восстановлении");

        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(16));
        background.setColor(ColorUtils.blendARGB(0xff172434, accentColor, 0.12f));
        background.setStroke(dp(1), ColorUtils.blendARGB(0xff344258, accentColor, 0.48f));
        setBackground(background);
    }

    private int iconForSelectedMode(int selectedModeId) {
        if (modeGroupId != R.id.drive_modes_group) {
            return defaultIconResource;
        }
        if (selectedModeId == R.id.ECO) {
            return R.drawable.settings_icon_leaf;
        }
        if (selectedModeId == R.id.COMFORT) {
            return R.drawable.settings_icon_feather;
        }
        if (selectedModeId == R.id.SPORT) {
            return R.drawable.settings_icon_racing_helmet;
        }
        if (selectedModeId == R.id.SNOW) {
            return R.drawable.settings_icon_snowflake;
        }
        if (selectedModeId == R.id.OUTING) {
            return R.drawable.settings_icon_mountains;
        }
        if (selectedModeId == R.id.INDIVIDUAL) {
            return R.drawable.settings_icon_star;
        }
        return defaultIconResource;
    }

    private void updateHeaderIcon(int iconResource, int accentColor) {
        if (!(getParent() instanceof LinearLayout)) {
            return;
        }
        LinearLayout card = (LinearLayout) getParent();
        if (!(card.getChildAt(0) instanceof LinearLayout)) {
            return;
        }
        LinearLayout header = (LinearLayout) card.getChildAt(0);
        if (!(header.getChildAt(0) instanceof ImageView)) {
            return;
        }

        ImageView headerIcon = (ImageView) header.getChildAt(0);
        headerIcon.setImageResource(iconResource);
        headerIcon.setColorFilter(accentColor);

        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(14));
        background.setColor(ColorUtils.blendARGB(0xff243448, accentColor, 0.18f));
        headerIcon.setBackground(background);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        preferences.registerOnSharedPreferenceChangeListener(this);
        post(refreshTask);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(refreshTask);
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        removeCallbacks(refreshTask);
        post(refreshTask);
    }
}
