package ru.big.town.restoremode.carplay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.shilapi.xcertplay.CarPlayHomeUi;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.ui.layout.SettingsComponents;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

/** VoyahTune home for both the CarPlay activity and its embedded display. */
public final class CarPlayHomeScreen implements CarPlayHomeUi.Screen {
    private final Context context;
    private final SettingsComponents components;
    private final LinearLayout root;
    private final TextView status;
    private final TextView hint;
    private final TextView problem;
    private final ActionCard wireless;
    private final ActionCard usb;
    private final TextView disconnect;
    private final TextView hotspotSettings;
    private final Switch autoConnect;
    private final TextView autoConnectDescription;
    private boolean updatingAutoConnect;
    private boolean stopping;

    public CarPlayHomeScreen(Context context, CarPlayHomeUi.Actions actions) {
        this.context = context;
        components = new SettingsComponents(context);
        boolean compact =
                context.getResources().getConfiguration().screenHeightDp < 450
                        || context.getResources().getConfiguration().screenWidthDp < 700;
        int gap = compact ? 12 : 16;
        int padding = compact ? 16 : 28;
        root = components.column();
        root.setBackgroundColor(color(R.color.settings_background));
        root.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));

        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(context);
        logo.setImageResource(R.drawable.carplay_home_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(logo, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView title =
                text(R.string.carplay_home_title, compact ? 26 : 32, R.color.settings_text);
        title.setTypeface(context.getResources().getFont(R.font.settings_arimo_bold));
        title.setPadding(dp(14), 0, 0, 0);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(text(R.string.carplay_home_brand, 16, R.color.settings_muted));
        root.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        status = components.text("", compact ? 16 : 18, color(R.color.settings_muted));
        addBelow(status, gap);

        LinearLayout cards = new LinearLayout(context);
        cards.setBaselineAligned(false);
        wireless =
                new ActionCard(
                        R.drawable.carplay_home_wireless,
                        R.string.carplay_home_wireless,
                        R.string.carplay_home_wireless_detail,
                        compact,
                        actions::wireless);
        usb =
                new ActionCard(
                        R.drawable.carplay_home_usb,
                        R.string.carplay_home_usb,
                        R.string.carplay_home_usb_detail,
                        compact,
                        actions::usb);
        ActionCard settings =
                new ActionCard(
                        R.drawable.settings_icon_tool,
                        R.string.carplay_home_settings,
                        R.string.carplay_home_settings_detail,
                        compact,
                        actions::settings);
        ActionCard[] entries = {wireless, usb, settings};
        for (int index = 0; index < entries.length; index++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
            if (index > 0) params.leftMargin = dp(gap);
            cards.addView(entries[index], params);
        }
        LinearLayout.LayoutParams cardsParams = new LinearLayout.LayoutParams(-1, -2, 1);
        cardsParams.topMargin = dp(gap);
        root.addView(cards, cardsParams);

        autoConnect = new Switch(context);
        autoConnect.setContentDescription(context.getString(R.string.carplay_home_auto_connect));
        SettingsDesign.styleTree(autoConnect);
        autoConnect.setMinHeight(dp(48));
        autoConnect.setMinimumHeight(dp(48));
        autoConnect.setOnCheckedChangeListener(
                (button, enabled) -> {
                    if (!updatingAutoConnect) actions.setAutoConnect(enabled);
                });
        autoConnectDescription =
                text(
                        R.string.carplay_home_auto_connect_detail,
                        compact ? 14 : 16,
                        R.color.settings_muted);
        autoConnectDescription.setPadding(0, dp(4), dp(16), 0);
        LinearLayout autoConnectCopy =
                components.column(
                        text(
                                R.string.carplay_home_auto_connect,
                                compact ? 18 : 20,
                                R.color.settings_text),
                        autoConnectDescription);
        LinearLayout autoConnectRow = new LinearLayout(context);
        autoConnectRow.setGravity(Gravity.CENTER_VERTICAL);
        autoConnectRow.addView(autoConnectCopy, new LinearLayout.LayoutParams(0, -2, 1));
        autoConnectRow.addView(autoConnect, new LinearLayout.LayoutParams(-2, -2));
        autoConnectCopy.setOnClickListener(
                view -> {
                    if (autoConnect.isEnabled()) autoConnect.toggle();
                });
        addBelow(autoConnectRow, gap);

        hint = components.text("", compact ? 14 : 16, color(R.color.settings_muted));
        addBelow(hint, gap);
        problem = components.text("", compact ? 14 : 16, color(R.color.settings_yellow));
        addBelow(problem, 8);
        problem.setVisibility(View.GONE);
        hotspotSettings = secondary(R.string.carplay_home_hotspot, actions::hotspotSettings);
        addBelow(hotspotSettings, 8);
        hotspotSettings.setVisibility(View.GONE);

        LinearLayout footer = new LinearLayout(context);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(secondary(R.string.carplay_home_choose_phone, actions::choosePhone));
        footer.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1));
        disconnect = secondary(R.string.carplay_home_disconnect, () -> stopSession(actions));
        disconnect.setVisibility(View.GONE);
        footer.addView(disconnect);
        LinearLayout.LayoutParams exitParams = new LinearLayout.LayoutParams(-2, -2);
        exitParams.leftMargin = dp(8);
        footer.addView(secondary(R.string.carplay_home_exit, actions::back), exitParams);
        addBelow(footer, 8);
    }

    private void stopSession(CarPlayHomeUi.Actions actions) {
        stopping = true;
        disconnect.setEnabled(false);
        actions.disconnect();
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void updateAutoConnect(boolean enabled, boolean phoneSelected) {
        updatingAutoConnect = true;
        autoConnect.setChecked(enabled);
        autoConnect.setEnabled(phoneSelected || enabled);
        autoConnectDescription.setText(
                phoneSelected
                        ? R.string.carplay_home_auto_connect_detail
                        : R.string.carplay_home_auto_connect_choose_phone);
        updatingAutoConnect = false;
    }

    @Override
    public void update(
            String statusText,
            boolean running,
            boolean enabled,
            String connectionHint,
            String problemText,
            boolean hotspotProblem) {
        status.setText(statusText);
        wireless.setTitle(running ? R.string.carplay_home_open : R.string.carplay_home_wireless);
        wireless.setEnabled(enabled);
        wireless.setAlpha(enabled ? 1f : .45f);
        usb.setEnabled(enabled);
        usb.setAlpha(enabled ? 1f : .45f);
        hint.setText(connectionHint);
        problem.setText(problemText);
        problem.setVisibility(problemText == null ? View.GONE : View.VISIBLE);
        hotspotSettings.setVisibility(hotspotProblem ? View.VISIBLE : View.GONE);
        if (!running) stopping = false;
        disconnect.setVisibility(running ? View.VISIBLE : View.GONE);
        disconnect.setEnabled(!stopping);
    }

    private final class ActionCard extends LinearLayout {
        private final TextView title;
        private final String detail;

        ActionCard(
                int icon, int titleResource, int detailResource, boolean compact, Runnable action) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setMinimumHeight(dp(compact ? 190 : 260));
            int padding = dp(compact ? 12 : 24);
            setPadding(padding, padding, padding, padding);
            setBackground(interactiveSurface());
            setFocusable(true);
            setOnClickListener(view -> action.run());
            int iconSize = compact ? 56 : 72;
            addView(
                    components.icon(icon, iconSize, compact ? 30 : 38),
                    new LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)));
            title = text(titleResource, compact ? 18 : 24, R.color.settings_text);
            title.setTypeface(context.getResources().getFont(R.font.settings_arimo_bold));
            title.setGravity(Gravity.CENTER);
            title.setMinLines(2);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
            titleParams.topMargin = dp(compact ? 12 : 20);
            addView(title, titleParams);
            detail = context.getString(detailResource);
            TextView description = text(detailResource, compact ? 14 : 16, R.color.settings_muted);
            description.setGravity(Gravity.CENTER);
            description.setMinLines(2);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
            detailParams.topMargin = dp(8);
            addView(description, detailParams);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            for (int index = 0; index < getChildCount(); index++) {
                getChildAt(index).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
            setTitle(titleResource);
        }

        void setTitle(int resource) {
            title.setText(resource);
            setContentDescription(context.getString(resource) + ". " + detail);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(Button.class.getName());
        }
    }

    private TextView secondary(int label, Runnable action) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(16);
        button.setTypeface(context.getResources().getFont(R.font.settings_arimo_regular));
        button.setTextColor(color(R.color.settings_accent));
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setBackgroundTintList(null);
        button.setBackground(interactiveSurface());
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private RippleDrawable interactiveSurface() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, surface(true));
        states.addState(new int[0], surface(false));
        int ripple = (color(R.color.settings_accent) & 0x00ffffff) | 0x28000000;
        return new RippleDrawable(ColorStateList.valueOf(ripple), states, surface(false));
    }

    private GradientDrawable surface(boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color(R.color.settings_surface));
        drawable.setCornerRadius(context.getResources().getDimension(R.dimen.settings_card_radius));
        drawable.setStroke(
                dp(focused ? 2 : 1),
                color(focused ? R.color.settings_accent : R.color.settings_border));
        return drawable;
    }

    private TextView text(int resource, int size, int colorResource) {
        return components.text(context.getString(resource), size, color(colorResource));
    }

    private void addBelow(View view, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(gap);
        root.addView(view, params);
    }

    private int color(int resource) {
        return context.getColor(resource);
    }

    private int dp(int value) {
        return components.dp(value);
    }
}
