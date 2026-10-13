package ru.big.town.restoremode.carplay;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.shilapi.xcertplay.CarPlayHomeUi;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.ui.layout.SettingsComponents;

/** VoyahTune home for both the CarPlay activity and its embedded display. */
public final class CarPlayHomeScreen implements CarPlayHomeUi.Screen {
    private final Context context;
    private final SettingsComponents components;
    private final FrameLayout root;
    private final TextView status;
    private final TextView problem;
    private final ActionCard wireless;
    private final ActionCard usb;
    private final TextView disconnect;
    private boolean stopping;

    public CarPlayHomeScreen(Context context, CarPlayHomeUi.Actions actions) {
        this.context = context;
        components = new SettingsComponents(context);
        boolean compact =
                context.getResources().getConfiguration().screenHeightDp < 450
                        || context.getResources().getConfiguration().screenWidthDp < 700;
        int gap = compact ? 8 : 12;
        int padding = compact ? 12 : 20;
        root = new FrameLayout(context);
        root.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));

        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(context);
        back.setImageResource(R.drawable.settings_icon_back);
        back.setBackgroundResource(R.drawable.settings_back);
        back.setScaleType(ImageView.ScaleType.FIT_CENTER);
        back.setContentDescription(context.getString(R.string.carplay_home_back));
        int backPadding = dp(compact ? 16 : 20);
        back.setPadding(backPadding, backPadding, backPadding, backPadding);
        back.setOnClickListener(view -> actions.back());
        int backSize = dp(compact ? 56 : 72);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(backSize, backSize);
        backParams.setMarginEnd(dp(12));
        heading.addView(back, backParams);
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
        LinearLayout header = components.column(heading);
        root.addView(header, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        status = components.text("", compact ? 16 : 18, color(R.color.settings_muted));
        addBelow(header, status, gap);

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
            if (index > 0) {
                params.setMarginStart(dp(gap));
            }
            cards.addView(entries[index], params);
        }
        FrameLayout.LayoutParams cardsParams =
                new FrameLayout.LayoutParams(-1, dp(compact ? 156 : 184), Gravity.CENTER);
        root.addView(cards, cardsParams);

        LinearLayout bottom = components.column();
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        problem = components.text("", compact ? 14 : 16, color(R.color.settings_yellow));
        addBelow(bottom, problem, 0);
        problem.setVisibility(View.GONE);
        LinearLayout footer = new LinearLayout(context);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        disconnect = secondary(R.string.carplay_home_disconnect, () -> stopSession(actions));
        disconnect.setVisibility(View.GONE);
        footer.addView(disconnect);
        TextView exit = secondary(R.string.carplay_home_exit, () -> ((Activity) context).finish());
        LinearLayout.LayoutParams exitParams = new LinearLayout.LayoutParams(-2, -2);
        exitParams.setMarginStart(dp(gap));
        footer.addView(exit, exitParams);
        addBelow(bottom, footer, 8);
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
        // Auto-connect is managed by VoyahTune settings.
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
        status.setVisibility(TextUtils.isEmpty(statusText) ? View.GONE : View.VISIBLE);
        wireless.setTitle(running ? R.string.carplay_home_open : R.string.carplay_home_wireless);
        wireless.setEnabled(enabled);
        wireless.setAlpha(enabled ? 1f : .45f);
        usb.setEnabled(enabled);
        usb.setAlpha(enabled ? 1f : .45f);
        problem.setText(problemText);
        problem.setVisibility(problemText == null ? View.GONE : View.VISIBLE);
        if (!running) {
            stopping = false;
        }
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
            int padding = dp(compact ? 10 : 12);
            setPadding(padding, padding, padding, padding);
            setBackground(interactiveSurface());
            setFocusable(true);
            setOnClickListener(view -> action.run());
            int iconSize = compact ? 40 : 48;
            addView(
                    components.icon(icon, iconSize, compact ? 24 : 28),
                    new LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)));
            title = text(titleResource, compact ? 18 : 20, R.color.settings_text);
            title.setTypeface(context.getResources().getFont(R.font.settings_arimo_bold));
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(2);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
            titleParams.topMargin = dp(compact ? 8 : 10);
            addView(title, titleParams);
            detail = context.getString(detailResource);
            TextView description = text(detailResource, 14, R.color.settings_muted);
            description.setGravity(Gravity.CENTER);
            description.setMaxLines(2);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
            detailParams.topMargin = dp(6);
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

    private void addBelow(LinearLayout parent, View view, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(gap);
        parent.addView(view, params);
    }

    private int color(int resource) {
        return context.getColor(resource);
    }

    private int dp(int value) {
        return components.dp(value);
    }
}
