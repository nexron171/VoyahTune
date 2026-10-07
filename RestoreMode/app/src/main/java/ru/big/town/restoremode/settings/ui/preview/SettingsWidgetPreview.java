package ru.big.town.restoremode.settings.ui.preview;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.widgets.energy.EnergyWidgetView;

/** Explicitly labelled examples, never used as vehicle readings. */
public final class SettingsWidgetPreview extends View
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SharedPreferences prefs;
    private final String kind;

    public SettingsWidgetPreview(Context context, AttributeSet attrs) {
        super(context, attrs);
        kind =
                attrs.getAttributeValue(
                        "http://schemas.android.com/apk/res/android", "contentDescription");
        setContentDescription(
                "Пример оформления виджета: " + kind + ". Демонстрационные значения.");
        prefs = context.getSharedPreferences("DrivePreferences", 0);
        paint.setTypeface(getResources().getFont(R.font.settings_arimo_regular));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        prefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if ("energyCarColor".equals(key)) {
            invalidate();
        }
    }

    private void text(Canvas c, String s, float x, float y, int size, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(s, x, y, paint);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        c.save();
        c.scale(getWidth() / 188f, getHeight() / 110f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff131b26);
        c.drawRoundRect(0, 0, 188, 110, 14, 14, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(0xff3a4a60);
        c.drawRoundRect(.5f, .5f, 187.5f, 109.5f, 14, 14, paint);
        if ("Давление в шинах".equals(kind)) {
            int[] colors = {
                0xff161a20, 0xffe5e5df, 0xff555d66, 0xff28483c, 0xff67394c, 0xff8c7459, 0xff88928c
            };
            int selected =
                    java.util.Arrays.asList(EnergyWidgetView.COLORS)
                            .indexOf(prefs.getString("energyCarColor", "burgundy"));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(colors[Math.max(0, selected)]);
            c.drawRoundRect(71, 8, 117, 84, 17, 17, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xffa1b1c4);
            c.drawRoundRect(71, 8, 117, 84, 17, 17, paint);
            Path windows = new Path();
            windows.moveTo(78, 26);
            windows.quadTo(94, 19, 110, 26);
            windows.lineTo(106, 42);
            windows.lineTo(82, 42);
            windows.close();
            windows.moveTo(82, 60);
            windows.lineTo(106, 60);
            windows.lineTo(110, 72);
            windows.lineTo(78, 72);
            windows.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xff162232);
            c.drawPath(windows, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xff728297);
            c.drawPath(windows, paint);
            text(c, "2,5", 22, 32, 13, 0xffbacfec);
            text(c, "2,5", 22, 68, 13, 0xffbacfec);
            text(c, "2,5", 138, 32, 13, 0xffbacfec);
            text(c, "2,5", 138, 68, 13, 0xffbacfec);
            text(c, "Пример · давление, bar", 14, 101, 11, 0xff7389a6);
        } else {
            String name = kind == null ? "Виджет" : kind;
            text(c, name, 14, 29, 11, 0xff7e92ac);
            String value =
                    "Заряд и топливо".equals(kind)
                            ? "78% · 42 л"
                            : "Расход за 2,5 км".equals(kind)
                                    ? "18,6 кВт·ч"
                                    : "Текущая поездка".equals(kind)
                                            ? "42 км · 00:38"
                                            : "Общий пробег".equals(kind)
                                                    ? "24 680 км"
                                                    : ("Процессор".equals(kind)
                                                                    || "CPU".equals(kind))
                                                            ? "24 %"
                                                            : ("Оперативная память".equals(kind)
                                                                            || "RAM".equals(kind))
                                                                    ? "3,2 ГБ"
                                                                    : "Виджет подвески".equals(kind)
                                                                            ? "— Нормальный"
                                                                            : "➤    ♪    ◈";
            text(c, value, 14, 59, 23, 0xffbbd6ff);
            boolean chart =
                    "Заряд и топливо".equals(kind)
                            || "Расход за 2,5 км".equals(kind)
                            || ("Процессор".equals(kind) || "CPU".equals(kind))
                            || ("Оперативная память".equals(kind) || "RAM".equals(kind));
            if (chart) {
                int[] ys = {20, 15, 18, 9, 12, 7, 14, 5, 8, 4, 9, 3, 6};
                Path path = new Path();
                for (int i = 0; i < ys.length; i++) {
                    float x = 14 + i * 160f / 12, y = 66 + ys[i] * .7f;
                    if (i == 0) {
                        path.moveTo(x, y);
                    } else {
                        path.lineTo(x, y);
                    }
                }
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2);
                paint.setColor(0xff8bbcff);
                c.drawPath(path, paint);
            }
            text(c, "Пример виджета", 14, 98, 11, 0xff7389a6);
        }
        c.restore();
    }
}
