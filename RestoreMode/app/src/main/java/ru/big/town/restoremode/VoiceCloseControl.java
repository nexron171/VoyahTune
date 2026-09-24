package ru.big.town.restoremode;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;

/** A small translucent child window confines system backdrop blur to the close button. */
final class VoiceCloseControl {
    private Dialog glass;

    void attach(Activity activity, LinearLayout column) {
        float density = activity.getResources().getDisplayMetrics().density;
        int width = Math.round(264 * density), height = Math.round(72 * density);
        float corner = 24 * density;
        Button button = new Button(activity);
        button.setText("Отмена");
        button.setAllCaps(false);
        button.setTextSize(24);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(null);
        button.setStateListAnimator(null);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(v -> activity.finish());

        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(width, height);
        position.gravity = Gravity.CENTER_HORIZONTAL;
        position.topMargin = Math.round(28 * density);
        position.bottomMargin = Math.round(8 * density);
        GradientDrawable surface = shape(0xb322242a, corner);
        surface.setStroke(Math.max(1, Math.round(density)), 0x40ffffff);
        RippleDrawable background = new RippleDrawable(ColorStateList.valueOf(0x28ffffff),
                surface, shape(Color.WHITE, corner));

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Android 11 has no public cross-window blur API; retain the translucent dark surface.
            button.setBackground(background);
            column.addView(button, position);
            return;
        }

        View anchor = new View(activity);
        anchor.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        column.addView(anchor, position);
        glass = new Dialog(activity);
        glass.requestWindowFeature(Window.FEATURE_NO_TITLE);
        glass.setCancelable(false);
        glass.setContentView(button);
        Window window = glass.getWindow();
        window.setBackgroundDrawable(background);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        window.setElevation(0);
        window.setGravity(Gravity.TOP | Gravity.LEFT);
        window.setBackgroundBlurRadius(Math.round(28 * density));
        window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setForeground(new RippleDrawable(ColorStateList.valueOf(0x28ffffff),
                null, shape(Color.WHITE, corner)));
        anchor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (activity.isFinishing() || activity.isDestroyed() || !anchor.isAttachedToWindow()) return;
            int[] location = new int[2];
            anchor.getLocationOnScreen(location);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.x = location[0]; attrs.y = location[1];
            attrs.width = anchor.getWidth(); attrs.height = anchor.getHeight();
            window.setAttributes(attrs);
            if (!glass.isShowing()) glass.show();
        });
    }

    private static GradientDrawable shape(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(radius);
        return shape;
    }

    void dispose() {
        if (glass != null) glass.dismiss();
    }
}
