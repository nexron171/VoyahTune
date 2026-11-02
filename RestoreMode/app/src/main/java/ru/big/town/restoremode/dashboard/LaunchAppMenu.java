package ru.big.town.restoremode.dashboard;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.appearance.DashboardAppearance;

/** Application destinations in a popup styled independently from the tile beneath it. */
final class LaunchAppMenu {
    private final Context context;
    private final LinearLayout content;
    private final PopupWindow popup;

    LaunchAppMenu(Context context, String packageName) {
        this.context = context;
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(8), dp(8), dp(8));
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content);
        popup = new PopupWindow(scroll, dp(320), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(12));
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(10), dp(12), dp(14));
        ImageView icon = new ImageView(context);
        String label = packageName;
        try {
            android.content.pm.PackageManager manager = context.getPackageManager();
            icon.setImageDrawable(manager.getApplicationIcon(packageName));
            label = manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
        }
        header.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView title = text(label, 18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleLayout =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleLayout.leftMargin = dp(14);
        header.addView(title, titleLayout);
        content.addView(header);
        View separator = new View(context);
        separator.setBackgroundColor(0x30ffffff);
        content.addView(separator,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    void addDestination(String label, int iconResource, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setMinimumHeight(dp(56));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(12));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x66348cd4), null, mask));
        ImageView icon = new ImageView(context);
        icon.setImageResource(iconResource);
        icon.setImageTintList(ColorStateList.valueOf(0xffd3dfe8));
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView title = text(label, 16);
        LinearLayout.LayoutParams titleLayout =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleLayout.leftMargin = dp(14);
        row.addView(title, titleLayout);
        ImageView arrow = new ImageView(context);
        arrow.setImageResource(R.drawable.ic_chevron_left);
        arrow.setRotation(180);
        arrow.setImageTintList(ColorStateList.valueOf(0xffa9bac7));
        row.addView(arrow, new LinearLayout.LayoutParams(dp(18), dp(18)));
        row.setEnabled(action != null);
        row.setAlpha(action == null ? 0.5f : 1f);
        row.setFocusable(action != null);
        if (action != null) {
            row.setOnClickListener(view -> {
                popup.dismiss();
                action.run();
            });
        }
        content.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    void show(View anchor, DashboardAppearance appearance) {
        Rect frame = new Rect();
        anchor.getWindowVisibleDisplayFrame(frame);
        frame.inset(dp(8), dp(8));
        if (frame.isEmpty()) {
            return;
        }
        int width = Math.min(dp(320), frame.width());
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int[] anchorPosition = new int[2];
        anchor.getLocationOnScreen(anchorPosition);
        Rect anchorBounds = new Rect(anchorPosition[0], anchorPosition[1],
                anchorPosition[0] + anchor.getWidth(), anchorPosition[1] + anchor.getHeight());
        Rect placement = PopupMenuPlacement.fit(
                frame, anchorBounds, width, content.getMeasuredHeight(), dp(6));
        popup.setWidth(placement.width());
        popup.setHeight(placement.height());
        Runnable release = appearance.stylePopup(popup.getContentView());
        View.OnAttachStateChangeListener anchorLifecycle = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {}

            @Override
            public void onViewDetachedFromWindow(View view) {
                popup.dismiss();
            }
        };
        anchor.addOnAttachStateChangeListener(anchorLifecycle);
        popup.setOnDismissListener(() -> {
            anchor.removeOnAttachStateChangeListener(anchorLifecycle);
            release.run();
        });
        int[] windowPosition = new int[2];
        anchor.getLocationInWindow(windowPosition);
        int windowOffsetX = anchorPosition[0] - windowPosition[0];
        int windowOffsetY = anchorPosition[1] - windowPosition[1];
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.LEFT,
                placement.left - windowOffsetX, placement.top - windowOffsetY);
    }

    private TextView text(String value, int size) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(Color.WHITE);
        return text;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
