package ru.big.town.restoremode;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;

/** Presentation only: never replaces setting listeners or writes preferences. */
final class SettingsDesign {
    private SettingsDesign() { }
    static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
    static int color(View view, int resource) { return view.getContext().getColor(resource); }
    private static GradientDrawable surface(View view, int fill, int border) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(view.getResources().getDimension(R.dimen.settings_control_radius));
        bg.setStroke(dp(view, 1), border);
        return bg;
    }
    private static ColorStateList ink(int normal, int active, int state) {
        return new ColorStateList(new int[][]{{state}, {}}, new int[]{active, normal});
    }
    static void styleTree(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            // Resource typography remains authoritative; legacy dynamic rows use a compact body size.
            if (text instanceof Button || text instanceof EditText || text instanceof CompoundButton) {
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, view.getId() == R.id.rawCanCodes ? 21 : 19);
                text.setIncludeFontPadding(false);
            }
            if (text instanceof Button && !(text instanceof CompoundButton)) {
                Button button = (Button) text;
                String role = String.valueOf(view.getTag());
                boolean primary = view.getId() == R.id.buttonApplyAdvance || role.equals("settings.primary");
                boolean add = role.equals("settings.add"), detail = role.equals("settings.details");
                boolean mini = role.equals("settings.mini"), delete = role.equals("settings.delete");
                boolean field = role.equals("settings.field"), example = role.equals("settings.example");
                int fill = example ? 0xff233247 : primary ? 0xffa5c8ff : add ? 0xff283952 : detail ? 0xff222a37 : field ? 0xff2b3748 : 0xff354d6c;
                int stroke = example ? 0xff435874 : primary ? fill : add ? 0xff6280a6 : detail ? 0xff384457 : field ? 0xff4a5c75 : 0xff617fa7;
                int fg = example ? 0xffbbd1ef : primary ? 0xff102541 : add ? 0xffbad5ff : 0xffd6e7ff;
                GradientDrawable normal = surface(view, fill, stroke);
                normal.setCornerRadius(dp(view, view.getId() == R.id.buttonApplyAdvance || add ? 16 : detail ? 18 : mini || delete ? 8 : 12));
                if (add) normal.setStroke(dp(view, 1), stroke, dp(view, 5), dp(view, 4));
                button.setAllCaps(false); button.setLetterSpacing(0); button.setStateListAnimator(null);
                button.setBackgroundTintList(null);
                if (button instanceof androidx.appcompat.widget.AppCompatButton)
                    ((androidx.appcompat.widget.AppCompatButton) button).setSupportBackgroundTintList(null);
                if (button instanceof MaterialButton) {
                    ((MaterialButton) button).setInsetTop(0); ((MaterialButton) button).setInsetBottom(0);
                }
                button.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x20ffffff), normal, null));
                button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                        new int[]{(fg & 0x00ffffff) | 0x66000000, fg}));
                if (field) {
                    button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    android.graphics.drawable.Drawable arrow = view.getContext().getDrawable(R.drawable.settings_chevron_down);
                    arrow.setBounds(0, 0, dp(view, 14), dp(view, 14));
                    button.setCompoundDrawablesRelative(null, null, arrow, null); button.setCompoundDrawablePadding(dp(view, 10));
                }
                button.setTextSize(view.getId() == R.id.buttonApplyAdvance || detail ? 23 : add ? 20 : delete ? 26 : mini ? 17 : example ? 18 : 19);
                button.setMinHeight(dp(view, mini || delete ? 0 : 48)); button.setMinimumHeight(dp(view, mini || delete ? 0 : 48));
                button.setMinWidth(0); button.setMinimumWidth(0);
                button.setMaxWidth(Integer.MAX_VALUE);
                int horizontal = example ? 17 : detail ? 25 : add ? 20 : field ? 13 : 19;
                int vertical = example ? 14 : detail ? 23 : add ? 20 : field ? 12 : 15;
                button.setPadding(dp(view, mini || delete ? 0 : horizontal), dp(view, mini || delete ? 0 : vertical),
                        dp(view, mini || delete ? 0 : horizontal), dp(view, mini || delete ? 0 : vertical));
            } else if (text instanceof RadioButton) {
                RadioButton radio = (RadioButton) text;
                int accent = choiceColor(view);
                StateListDrawable bg = new StateListDrawable();

                boolean colored = view.getId() == R.id.ECO || view.getId() == R.id.COMFORT || view.getId() == R.id.SPORT
                        || view.getId() == R.id.OUTING || view.getId() == R.id.SNOW || view.getId() == R.id.INDIVIDUAL
                        || view.getId() == R.id.SMART || view.getId() == R.id.EV || view.getId() == R.id.REV || view.getId() == R.id.SREV
                        || view.getId() == R.id.fragranceTaste1 || view.getId() == R.id.fragranceTaste2 || view.getId() == R.id.fragranceTaste3;
                int normalFill = colored ? androidx.core.graphics.ColorUtils.blendARGB(0xff1a2432, accent, .09f) : 0xff1a2432;
                int normalStroke = colored ? androidx.core.graphics.ColorUtils.blendARGB(0xff334155, accent, .35f) : 0xff455771;
                boolean stack = view.getParent() instanceof SettingsChoiceGroup && ((SettingsChoiceGroup) view.getParent()).isStack();
                boolean steeringTab = "settings.steering-tab".equals(view.getTag());
                bg.addState(new int[]{android.R.attr.state_checked}, surface(view, stack && !colored && !steeringTab ? 0xff304965 : accent, stack && !colored && !steeringTab ? 0xffa0c4f5 : accent));
                bg.addState(new int[]{}, surface(view, steeringTab ? 0xff26364b : normalFill, normalStroke));
                radio.setButtonDrawable(null);
                radio.setBackgroundTintList(null);
                radio.setBackground(bg);
                radio.setTextColor(ink(view.getId() == R.id.fragranceTaste2 ? 0xffb7a1dc : colored ? accent : 0xffa6b6cc,
                        stack && !colored && !steeringTab ? 0xffd9e9ff : view.getId() == R.id.fragranceTaste2 ? 0xfff5f0ff : 0xff142339,
                        android.R.attr.state_checked));
                radio.setPadding(dp(view, 19), dp(view, 15), dp(view, 19), dp(view, 15));
                radio.setMinWidth(0); radio.setMinimumWidth(0);
                radio.setGravity(Gravity.CENTER_VERTICAL | (stack ? Gravity.START : Gravity.CENTER_HORIZONTAL));
                radio.setLetterSpacing(0);
                if ("settings.steering-tab".equals(view.getTag())) {
                    radio.setPadding(dp(view, 15), dp(view, 15), dp(view, 15), dp(view, 15));
                    for (android.graphics.drawable.Drawable icon : radio.getCompoundDrawablesRelative()) if (icon != null) {
                        icon.mutate().setTintList(ink(0xffa6bbd8, 0xff1c3454, android.R.attr.state_checked));
                        icon.setBounds(0, 0, dp(view, 28), dp(view, 28));
                    }
                    android.graphics.drawable.Drawable[] icons = radio.getCompoundDrawablesRelative();
                    radio.setCompoundDrawablesRelative(icons[0], icons[1], icons[2], icons[3]);
                }
                radio.setMinHeight(dp(view, 48));
            } else if (text instanceof Switch) {
                Switch toggle = (Switch) text;
                toggle.setTextColor(color(view, R.color.settings_text));
                toggle.setMinHeight(dp(view, 32));
                toggle.setThumbTintList(ink(color(view, R.color.settings_muted), color(view, R.color.settings_accent), android.R.attr.state_checked));
                toggle.setTrackTintList(ink(color(view, R.color.settings_border), 0xff45688e, android.R.attr.state_checked));
            } else if (text instanceof EditText) {
                text.setTextColor(color(view, R.color.settings_text));
                text.setHintTextColor(color(view, R.color.settings_muted));
                text.setMinHeight(dp(view, 48));
            }
        }
        if (view instanceof TextView) applyTypography((TextView) view);
        if (view instanceof Spinner) {
            view.setMinimumHeight(dp(view, 48));
            view.setBackgroundResource(R.drawable.settings_field);
            view.setPadding(dp(view, 2), 0, dp(view, 2), 0);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) styleTree(group.getChildAt(i));
        }
    }
    static void applyTypography(TextView text) {
        if (text.getId() == R.id.rawCanCodes || text.getId() == R.id.textHookStatus) return;
        boolean bold = text.getId() == R.id.sectionTitle || text.getId() == R.id.settingsEyebrow || text.getId() == R.id.buttonApplyAdvance;
        text.setTypeface(text.getResources().getFont(bold ? R.font.settings_arimo_bold : R.font.settings_arimo_regular));
        text.setIncludeFontPadding(false);
        if (!(text instanceof android.widget.EditText)) {
            Float multiplier = (Float) text.getTag(R.id.settings_text_line_multiplier);
            if (multiplier == null) {
                multiplier = text.getLineSpacingMultiplier();
                text.setTag(R.id.settings_text_line_multiplier, multiplier);
            }
            float line = text.getTextSize() * (multiplier > 1.3f ? multiplier : 1.17f);
            if (multiplier <= 1.3f) {
                int size = Math.round(text.getTextSize() / text.getResources().getDisplayMetrics().scaledDensity);
                if (size == 27) line = text.getTextSize() * 31f / 27;
                else if (size == 35) line = text.getTextSize() * 40f / 35;
                else if (size == 36) line = text.getTextSize() * 43f / 36;
                else if (size == 16) line = text.getTextSize() * 18f / 16;
                if (text instanceof RadioButton) line = text.getTextSize() * 1.2f;
            }
            text.setLineSpacing(Math.round(line) - text.getPaint().getFontMetricsInt(null), 1f);
            if (text.getLayoutParams() != null && text.getLayoutParams().height == ViewGroup.LayoutParams.WRAP_CONTENT)
                text.setMinHeight(Math.max(text.getMinHeight(), Math.round(line) + text.getPaddingTop() + text.getPaddingBottom()));
        }
    }
    static int choiceColor(View view) {
        int id = view.getId(), token = R.color.settings_accent;
        if (id == R.id.ECO || id == R.id.EV || id == R.id.fragranceTaste1) token = R.color.settings_green;
        else if (id == R.id.COMFORT || id == R.id.SMART || id == R.id.fragranceTaste3) token = R.color.settings_cyan;
        else if (id == R.id.SPORT) token = R.color.settings_red;
        else if (id == R.id.OUTING) token = R.color.settings_yellow;
        else if (id == R.id.SNOW) token = R.color.settings_snow;
        else if (id == R.id.INDIVIDUAL) token = R.color.settings_purple;
        else if (id == R.id.fragranceTaste2) token = R.color.settings_peony;
        else if (id == R.id.REV) token = R.color.settings_orange;
        else if (id == R.id.SREV) token = R.color.settings_blue;
        return color(view, token);
    }
    static void refreshOverview(Activity activity) {
        TextView count = activity.findViewById(R.id.settingsWidgetCount);
        if (count == null) return;
        int enabled = 0;
        int[] ids = {R.id.switchShowTripTimer, R.id.switchShowPowerHold, R.id.switchShowWashMode, R.id.switchShowAutoLight,
                R.id.switchShowPedestrian, R.id.switchShowBatteryHeat, R.id.switchShowVoiceCommand, R.id.switchShowForcedEv,
                R.id.switchShowSuspensionMaintenance, R.id.switchShowLaunchAppsWidget, R.id.switchShowSuspensionWidget,
                R.id.switchShowCpu, R.id.switchShowRam, R.id.switchShowClearMemory, R.id.switchShowEnergy,
                R.id.switchShowEnergyConsumption, R.id.switchShowEnergyTrip, R.id.switchShowTirePressure, R.id.switchShowOdometer,
                R.id.switchShowScenariosCard, R.id.switchShowTaskManagerTile};
        for (int id : ids) if (((Switch) activity.findViewById(id)).isChecked()) enabled++;
        count.setText(String.valueOf(enabled));
        ((TextView) activity.findViewById(R.id.settingsAppCount)).setText(String.valueOf(
                AppWidgetStore.load(activity.getSharedPreferences("DrivePreferences", 0)).size()));
    }
    static void install(Activity activity) {
        styleTree(activity.findViewById(R.id.main));
        View header = (View) activity.findViewById(R.id.sectionTitle).getParent().getParent();
        header.setBackground(new SettingsHeaderDrawable(header));
        header.setElevation(dp(header, 8));
        header.setOutlineProvider(null);
        int[] navigation = {R.id.navMainScreen, R.id.navDriveModes, R.id.navSplitScreen,
                R.id.navApolloTech, R.id.navCustomCommands, R.id.navSteeringButtons,
                R.id.navVoiceControl, R.id.navScenarios, R.id.navOther};
        for (int id : navigation) {
            TextView item = activity.findViewById(id);
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_selected}, new SettingsNavigationDrawable(item.getResources().getDisplayMetrics().density));
            bg.addState(new int[]{}, surface(item, color(item, R.color.settings_background), color(item, R.color.settings_background)));
            item.setBackground(bg);
            item.setTextColor(ink(0xff8996a9, 0xffd2e5ff, android.R.attr.state_selected));
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23);
            item.setTypeface(item.getResources().getFont(R.font.settings_arimo_regular));
            item.setIncludeFontPadding(false);
            int lines = id == R.id.navDriveModes || id == R.id.navSplitScreen
                    || id == R.id.navVoiceControl || id == R.id.navCustomCommands ? 2 : 1;
            item.setMinHeight(dp(item, 28) + Math.round(item.getTextSize() * 1.17f * lines));
        }
        RadioGroup tabs = activity.findViewById(R.id.settingsSteeringTabs);
        int[] tabIds = {R.id.settingsStarTab, R.id.settingsDvrTab, R.id.settingsVoiceTab, R.id.settingsPhoneTab};
        int[] panels = {R.id.settingsStarPanel, R.id.settingsDvrPanel, R.id.settingsVoicePanel, R.id.settingsPhonePanel};
        android.widget.ImageView wheel = activity.findViewById(R.id.settingsSteeringWheel);
        TextView caption = activity.findViewById(R.id.settingsSteeringCaption);
        tabs.setOnCheckedChangeListener((group, selected) -> {
            for (int i = 0; i < panels.length; i++) activity.findViewById(panels[i]).setVisibility(selected == tabIds[i] ? View.VISIBLE : View.GONE);
            boolean left = selected == R.id.settingsStarTab;
            wheel.setImageResource(left ? R.drawable.settings_wheel_left : R.drawable.settings_wheel_right);
            ((TextView) activity.findViewById(R.id.settingsSteeringSelectedTitle)).setText(((TextView) activity.findViewById(selected)).getText());
            caption.setText(left ? "Левый блок · звёздочка" : "Правый блок · "
                    + (selected == R.id.settingsDvrTab ? "DVR" : selected == R.id.settingsVoiceTab ? "голосовой помощник" : "трубка"));
            wheel.setContentDescription(caption.getText());
        });
        tabs.check(R.id.settingsStarTab);
        RadioGroup keyboard = activity.findViewById(R.id.settingsKeyboardChoices);
        Switch english = activity.findViewById(R.id.switchKeyboardEnglish), russian = activity.findViewById(R.id.switchKeyboardRussian);
        keyboard.check(english.isChecked() ? R.id.settingsKeyboardEnglish : russian.isChecked() ? R.id.settingsKeyboardRussian : R.id.settingsKeyboardOff);
        keyboard.setOnCheckedChangeListener((group, id) -> {
            if (id == R.id.settingsKeyboardEnglish) english.setChecked(true);
            else if (id == R.id.settingsKeyboardRussian) russian.setChecked(true);
            else { english.setChecked(false); russian.setChecked(false); }
        });
        TextView engineering = activity.findViewById(R.id.settingsEngineeringToggle);
        View engineeringBody = activity.findViewById(R.id.settingsEngineeringBody);
        engineering.setOnClickListener(v -> {
            boolean open = engineeringBody.getVisibility() != View.VISIBLE;
            engineeringBody.setVisibility(open ? View.VISIBLE : View.GONE);
            engineering.setText("Инженерное меню    " + (open ? "−" : "＋"));
        });
        refreshOverview(activity);
    }
}
