package ru.big.town.restoremode.settings.ui.layout;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ru.big.town.restoremode.R;

/** Programmatic counterparts of the settings XML components. Dimensions mirror the HTML spec. */
public final class SettingsComponents {
    private final Context context;

    public SettingsComponents(Context context) {
        this.context = context;
    }

    public int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public LinearLayout column(View... children) {
        LinearLayout out = new LinearLayout(context);
        out.setOrientation(LinearLayout.VERTICAL);
        for (View child : children) {
            detach(child);
            out.addView(child, new LinearLayout.LayoutParams(-1, -2));
        }
        return out;
    }

    static void detach(View child) {
        if (child.getParent() instanceof ViewGroup) {
            ((ViewGroup) child.getParent()).removeView(child);
        }
    }

    public TextView text(String value, int size, int color) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(context.getResources().getFont(R.font.settings_arimo_regular));
        text.setIncludeFontPadding(false);
        return text;
    }

    public LinearLayout card(View... children) {
        LinearLayout card = column(children);
        card.setBackgroundResource(R.drawable.settings_card);
        card.setPadding(dp(25), dp(25), dp(25), dp(25));
        return card;
    }

    public ImageView icon(int source, int size, int inner) {
        ImageView icon = new ImageView(context);
        icon.setImageResource(source);
        icon.setBackgroundResource(
                size == 72 ? R.drawable.settings_intro_icon : R.drawable.settings_icon);
        int inset = dp((size - inner) / 2);
        icon.setPadding(inset, inset, inset, inset);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    public LinearLayout head(int source, String title, View control) {
        LinearLayout head = new LinearLayout(context);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(46), dp(46));
        ip.rightMargin = dp(16);
        head.addView(icon(source, 46, 28), ip);
        head.addView(text(title, 24, 0xfff3f5fa), new LinearLayout.LayoutParams(0, -2, 1));
        if (control != null) {
            detach(control);
            head.addView(control, new LinearLayout.LayoutParams(-2, -2));
        }
        head.setPadding(0, 0, 0, dp(20));
        return head;
    }

    public LinearLayout row(String title, String description, View control) {
        LinearLayout row = new SettingsRow(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView detail = text(description, 17, 0xff9aaac0);
        detail.setPadding(0, dp(7), 0, 0);
        detail.setLineSpacing(0, 1.45f);
        LinearLayout copy = column(text(title, 22, 0xfff3f5fa), detail);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        detach(control);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.leftMargin = dp(20);
        row.addView(control, cp);
        row.setPadding(0, dp(18), 0, dp(18));
        return row;
    }

    public TextView heading(String title) {
        TextView heading = text(title, 27, 0xfff3f5fa);
        heading.setPadding(0, dp(34), 0, dp(16));
        return heading;
    }

    public LinearLayout intro(int source, String title, String description) {
        LinearLayout intro = new LinearLayout(context);
        intro.setGravity(Gravity.CENTER_VERTICAL);
        intro.setPadding(dp(27), dp(23), dp(27), dp(23));
        intro.setMinimumHeight(dp(122));
        intro.setBackgroundResource(R.drawable.settings_intro);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(72), dp(72));
        ip.rightMargin = dp(24);
        intro.addView(icon(source, 72, 38), ip);
        TextView detail = text(description, 18, 0xffadbed5);
        detail.setPadding(0, dp(8), 0, 0);
        detail.setLineSpacing(0, 1.45f);
        detail.setMaxWidth(dp(900));
        LinearLayout copy = column(text(title, 26, 0xfff3f5fa), detail);
        detail.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        intro.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        return intro;
    }
}
