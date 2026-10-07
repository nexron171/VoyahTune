package ru.big.town.updater;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Visual treatment only; daemon state and update actions remain in MainActivity. */
final class UpdaterDesign {
    private static final int BLUE = 0xFFA5C8FF, GREEN = 0xFF70DFA3, RED = 0xFFFF818B;
    private static final int MUTED = 0xFF97A6BC, INK = 0xFFF3F5FA;

    static void install(Activity activity) {
        applyLineHeights(activity.findViewById(R.id.root));
        View header = activity.findViewById(R.id.header);
        header.setBackground(new HeaderMask(header));
        header.setOutlineProvider(null);
        ((ImageView) activity.findViewById(R.id.settings)).setImageTintList(ColorStateList.valueOf(BLUE));
        for (int id : new int[]{R.id.nav0, R.id.nav1, R.id.nav2, R.id.nav3}) {
            TextView item = activity.findViewById(id);
            StateListDrawable background = new StateListDrawable();
            background.addState(new int[]{android.R.attr.state_selected}, shape(activity, 0xFF2A3D58, 0xFF4A6588, 16));
            background.addState(new int[]{android.R.attr.state_activated}, shape(activity, 0xFF202F32, Color.TRANSPARENT, 16));
            background.addState(new int[]{}, shape(activity, Color.TRANSPARENT, Color.TRANSPARENT, 16));
            item.setBackground(background);
        }
        styleAction(activity.findViewById(R.id.primary), true);
        styleAction(activity.findViewById(R.id.secondary), false);
    }

    static void render(Activity activity, UpdatePresentation p, boolean connected, boolean failed) {
        int tone = failed ? RED : p.success ? GREEN : BLUE;
        TextView badge = activity.findViewById(R.id.badge);
        if (!Integer.valueOf(tone).equals(badge.getTag())) {
            badge.setTag(tone);
            badge.setTextColor(tone);
            badge.setBackground(shape(activity, failed ? 0xFF3B2935 : p.success ? 0xFF223C36 : 0xFF273B57,
                    failed ? 0xFF70424B : p.success ? 0xFF427361 : 0xFF526D90, 18));
            ((TextView) activity.findViewById(R.id.eyebrow)).setTextColor(tone);
            ImageView hero = activity.findViewById(R.id.hero_icon);
            hero.setImageResource(failed ? R.drawable.ic_error : p.success ? R.drawable.ic_check : R.drawable.ic_download);
            hero.setImageTintList(ColorStateList.valueOf(tone));
            hero.setBackground(shape(activity, failed ? 0xFF4A303E : p.success ? 0xFF2B4A41 : 0xFF354660, Color.TRANSPARENT, 15));
            ImageView aside = activity.findViewById(R.id.aside_icon);
            aside.setImageResource(failed ? R.drawable.ic_error : R.drawable.ic_shield);
            aside.setImageTintList(ColorStateList.valueOf(tone));
        }
        int[] ids = {R.id.nav0, R.id.nav1, R.id.nav2, R.id.nav3};
        for (int i = 0; i < ids.length; i++) {
            TextView item = activity.findViewById(ids[i]);
            boolean done = connected && (i < p.nav || p.success);
            boolean active = connected && i == p.nav && !p.success;
            item.setSelected(active);
            item.setActivated(done);
            int color = active ? BLUE : done ? GREEN : MUTED;
            if (item.getCurrentTextColor() != color) item.setTextColor(color);
        }
        setTextColor(activity.findViewById(R.id.service), connected ? MUTED : RED);
        setTextColor(activity.findViewById(R.id.footer_note), p.success ? GREEN : MUTED);
        activity.findViewById(R.id.primary).setAlpha(activity.findViewById(R.id.primary).isEnabled() ? 1f : .45f);
        activity.findViewById(R.id.secondary).setAlpha(activity.findViewById(R.id.secondary).isEnabled() ? 1f : .45f);
        activity.findViewById(R.id.settings).setAlpha(activity.findViewById(R.id.settings).isEnabled() ? 1f : .45f);
    }

    private static void setTextColor(TextView view, int color) {
        if (view.getCurrentTextColor() != color) view.setTextColor(color);
    }

    private static void applyLineHeights(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            float size = text.getTextSize();
            float multiplier = text.getLineSpacingMultiplier();
            int sp = Math.round(size / view.getResources().getDisplayMetrics().scaledDensity);
            int line = Math.round(size * (multiplier > 1.3f ? multiplier : sp == 36 ? 43f / 36 : sp == 32 ? 38f / 32 : 1.17f));
            text.setLineSpacing(line - text.getPaint().getFontMetricsInt(null), 1f);
            if (text.getLayoutParams().height == ViewGroup.LayoutParams.WRAP_CONTENT)
                text.setMinHeight(Math.max(text.getMinHeight(), line + text.getPaddingTop() + text.getPaddingBottom()));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyLineHeights(group.getChildAt(i));
        }
    }

    static void styleField(EditText field) {
        field.setBackgroundResource(R.drawable.field);
        field.setBackgroundTintList(null);
        field.setTextColor(INK);
        field.setTextSize(20);
        field.setPadding(dp(field.getContext(), 18), 0, dp(field.getContext(), 18), 0);
        field.setMinHeight(dp(field.getContext(), 64));
    }

    static void styleAction(Button button, boolean primary) {
        Context context = button.getContext();
        button.setAllCaps(false);
        button.setTypeface(context.getResources().getFont(primary ? R.font.updater_arimo_bold : R.font.updater_arimo_regular));
        button.setTextSize(primary ? 23 : 20);
        button.setIncludeFontPadding(false);
        button.setBackgroundResource(primary ? R.drawable.button_primary : R.drawable.button_secondary);
        button.setBackgroundTintList(null);
        button.setStateListAnimator(null);
        button.setTextColor(primary ? 0xFF102541 : INK);
        button.setPadding(dp(context, 24), 0, dp(context, 24), 0);
        button.setMinHeight(dp(context, 64));
    }

    static void styleDialog(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        Context context = dialog.getContext();
        applyFont(window.getDecorView());
        int titleId = context.getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = window.findViewById(titleId);
        if (title != null) {
            title.setTypeface(context.getResources().getFont(R.font.updater_arimo_bold));
            title.setTextColor(INK);
            title.setTextSize(30);
        }
        TextView message = window.findViewById(android.R.id.message);
        if (message != null) {
            message.setTextColor(MUTED);
            message.setTextSize(21);
            message.setLineSpacing(0, 1.45f);
        }
        for (int which : new int[]{AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE}) {
            Button button = dialog.getButton(which);
            if (button == null) continue;
            styleAction(button, which == AlertDialog.BUTTON_POSITIVE);
            ViewGroup.LayoutParams params = button.getLayoutParams();
            params.height = dp(context, 64);
            if (params instanceof LinearLayout.LayoutParams) ((LinearLayout.LayoutParams) params).setMargins(dp(context, 8), dp(context, 16), 0, 0);
            button.setLayoutParams(params);
        }
        window.setBackgroundDrawableResource(R.drawable.card);
        window.setDimAmount(.4f);
        window.setLayout(Math.min(dp(context, 1080), context.getResources().getDisplayMetrics().widthPixels - dp(context, 96)), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static void applyFont(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTypeface(view.getResources().getFont(R.font.updater_arimo_regular));
            text.setIncludeFontPadding(false);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyFont(group.getChildAt(i));
        }
    }

    private static GradientDrawable shape(Context context, int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(context, radius));
        if (stroke != Color.TRANSPARENT) shape.setStroke(dp(context, 1), stroke);
        return shape;
    }

    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    /** Same accelerating 100 → 0 background fade as RestoreMode, without blur. */
    private static final class HeaderMask extends Drawable {
        private final View header;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int shaderHeight;

        HeaderMask(View header) { this.header = header; }

        @Override public void draw(Canvas canvas) {
            View title = header.findViewById(R.id.header_title);
            int height = title.getBottom() + dp(header.getContext(), 12) + ((View) title.getParent()).getTop();
            if (height <= 0) height = getBounds().height();
            if (height != shaderHeight) {
                shaderHeight = height;
                int[] colors = new int[33];
                float[] stops = new float[33];
                for (int i = 0; i < colors.length; i++) {
                    float t = i / 32f;
                    stops[i] = t;
                    colors[i] = Color.argb(Math.round(255 * (1 - t * t * t)), 23, 28, 37);
                }
                paint.setShader(new LinearGradient(0, 0, 0, height, colors, stops, Shader.TileMode.CLAMP));
            }
            canvas.drawRect(getBounds(), paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
