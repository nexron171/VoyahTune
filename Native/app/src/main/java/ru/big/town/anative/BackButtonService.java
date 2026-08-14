package ru.big.town.anative;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;

/**
 * Системное действие «Назад» и его опциональная плавающая кнопка. Реализованы одним сервисом
 * доступности, чтобы:
 *  - рисовать оверлей через TYPE_ACCESSIBILITY_OVERLAY (не нужен SYSTEM_ALERT_WINDOW);
 *  - выполнять системное «назад» через performGlobalAction(GLOBAL_ACTION_BACK).
 * Положение (сторона слева/сверху/справа) + смещение вдоль стороны хранятся в NativePrefs
 * ("floatingBackSide"/"floatingBackOffset"), кнопка перетаскивается вдоль выбранной стороны.
 */
public class BackButtonService extends AccessibilityService {
    static final String TAG = "$$$ BackButtonService $$$";
    static final int SIDE_LEFT = 0, SIDE_TOP = 1, SIDE_RIGHT = 2;
    private static final String COMPONENT =
            "ru.big.town.anative/ru.big.town.anative.BackButtonService";
    private static final String PREF_FLOATING = "floatingBack";
    private static final String PREF_STEERING = "steeringBack";

    private static BackButtonService instance;

    private WindowManager wm;
    private ImageView buttonView;
    private WindowManager.LayoutParams lp;
    private int btnSize;

    private SharedPreferences prefs() {
        return getSharedPreferences("NativePrefs", Context.MODE_PRIVATE);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        if (prefs().getBoolean(PREF_FLOATING, false)) {
            Log.i(TAG, "onServiceConnected — показываем кнопку");
            showButton();
        } else {
            Log.i(TAG, "onServiceConnected — без оверлея (сервис нужен кнопке руля)");
        }
    }

    /** Включает оверлей, не отключая accessibility-сервис, если он нужен действию кнопки руля. */
    static void setFloatingButtonEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_FLOATING, enabled).apply();
        syncAccessibility(context);
        BackButtonService live = instance;
        if (live != null) {
            if (enabled) live.showButton(); else live.hideButton();
        }
    }

    /** Держит accessibility-сервис подключённым, когда хотя бы один слот руля вызывает «Назад». */
    static void setSteeringBackEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_STEERING, enabled).apply();
        syncAccessibility(context);
    }

    /** Выполнить тот же GLOBAL_ACTION_BACK, что и тап по плавающей кнопке. */
    static void performBack(Context context) {
        BackButtonService live = instance;
        if (live != null) {
            live.performBackNow("steering wheel");
            return;
        }

        // Обычно сервис уже подключён после STEER_CONFIG. На случай самого первого быстрого нажатия
        // или убитого процесса форсируем rebind и коротко ждём подключения.
        disableForReconnect(context);
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> syncAccessibility(context), 150);
        handler.postDelayed(() -> {
            BackButtonService retry = instance;
            if (retry != null) retry.performBackNow("steering wheel delayed");
            else Log.w(TAG, "GLOBAL_ACTION_BACK пропущен: accessibility-сервис не подключён");
        }, 700);
    }

    private void performBackNow(String source) {
        boolean ok = performGlobalAction(GLOBAL_ACTION_BACK);
        Log.i(TAG, "GLOBAL_ACTION_BACK (" + source + ") -> " + ok);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences("NativePrefs", Context.MODE_PRIVATE);
    }

    /** Сервис нужен либо оверлею, либо назначенному на руль системному действию. */
    private static void syncAccessibility(Context context) {
        SharedPreferences prefs = prefs(context);
        boolean present = prefs.getBoolean(PREF_FLOATING, false)
                || prefs.getBoolean(PREF_STEERING, false);
        writeAccessibility(context, present);
    }

    /** Кратко снять компонент для принудительного rebind, не меняя причины, по которым он нужен. */
    static void disableForReconnect(Context context) {
        writeAccessibility(context, false);
    }

    private static void writeAccessibility(Context context, boolean present) {
        SharedPreferences prefs = prefs(context);
        try {
            android.content.ContentResolver cr = context.getContentResolver();
            String current = android.provider.Settings.Secure.getString(
                    cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            if (current != null) {
                for (String service : current.split(":")) {
                    if (!service.isEmpty()) set.add(service);
                }
            }
            if (present) set.add(COMPONENT); else set.remove(COMPONENT);

            StringBuilder enabled = new StringBuilder();
            for (String service : set) {
                if (enabled.length() > 0) enabled.append(":");
                enabled.append(service);
            }
            android.provider.Settings.Secure.putString(
                    cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    enabled.toString());
            android.provider.Settings.Secure.putInt(
                    cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
                    set.isEmpty() ? 0 : 1);
            Log.i(TAG, "back a11y " + (present ? "ON" : "OFF")
                    + "; floating=" + prefs.getBoolean(PREF_FLOATING, false)
                    + "; steering=" + prefs.getBoolean(PREF_STEERING, false));
        } catch (Exception e) {
            Log.e(TAG, "syncAccessibility failed: " + e.getMessage());
        }
    }

    /** Живое обновление раскладки при смене стороны из настроек (тот же процесс). */
    static void updatePosition() {
        if (instance != null) instance.applyLayout();
    }

    /**
     * Пере-показать оверлей, если сервис доступности подключён (окно могло сняться при
     * засыпании экрана). Возвращает true, если сервис жив (иначе нужно поднять его заново).
     */
    static boolean reshow() {
        if (instance == null) return false;
        instance.reshowOverlay();
        return true;
    }

    private void reshowOverlay() {
        // Пересоздаём окно: старое могло быть снято системой при засыпании.
        if (buttonView != null && wm != null) {
            try { wm.removeView(buttonView); } catch (Exception ignored) {}
        }
        buttonView = null;
        showButton();
        Log.i(TAG, "reshowOverlay — оверлей пересоздан");
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void showButton() {
        if (buttonView != null) { applyLayout(); return; }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) { Log.e(TAG, "WindowManager == null"); return; }

        btnSize = dp(56);
        ImageView btn = new ImageView(this);
        btn.setImageResource(R.drawable.ic_back_arrow);
        btn.setBackgroundResource(R.drawable.floating_back_bg);
        btn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(12);
        btn.setPadding(pad, pad, pad, pad);
        btn.setOnTouchListener(new DragTouchListener());

        lp = new WindowManager.LayoutParams(
                btnSize, btnSize,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.START | Gravity.TOP;

        try {
            wm.addView(btn, lp);
            buttonView = btn;
            applyLayout();
            Log.i(TAG, "кнопка добавлена");
        } catch (Exception e) {
            Log.e(TAG, "addView failed: " + e.getMessage());
        }
    }

    /** Раскладывает кнопку по выбранной стороне и сохранённому смещению (offset<0 = по центру стороны). */
    private void applyLayout() {
        if (buttonView == null || wm == null || lp == null) return;
        int side = prefs().getInt("floatingBackSide", SIDE_LEFT);
        int offset = prefs().getInt("floatingBackOffset", -1);

        Point size = new Point();
        wm.getDefaultDisplay().getSize(size);
        int sw = size.x, sh = size.y;

        lp.gravity = Gravity.START | Gravity.TOP;
        if (side == SIDE_TOP) {
            int maxX = Math.max(0, sw - btnSize);
            lp.x = (offset < 0) ? maxX / 2 : clamp(offset, 0, maxX);
            lp.y = 0;
        } else { // LEFT / RIGHT — двигается по вертикали
            int maxY = Math.max(0, sh - btnSize);
            lp.y = (offset < 0) ? maxY / 2 : clamp(offset, 0, maxY);
            lp.x = (side == SIDE_RIGHT) ? Math.max(0, sw - btnSize) : 0;
        }
        try {
            wm.updateViewLayout(buttonView, lp);
            Log.i(TAG, "applyLayout side=" + side + " x=" + lp.x + " y=" + lp.y);
        } catch (Exception ignored) {
        }
    }

    /** Тап = «назад», перетаскивание вдоль стороны = смена позиции (с сохранением). */
    private class DragTouchListener implements View.OnTouchListener {
        private int startX, startY;
        private float rawX0, rawY0;
        private boolean dragging;
        private final int slop = ViewConfiguration.get(BackButtonService.this).getScaledTouchSlop();

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lp.x; startY = lp.y;
                    rawX0 = e.getRawX(); rawY0 = e.getRawY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (e.getRawX() - rawX0);
                    int dy = (int) (e.getRawY() - rawY0);
                    if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                    if (dragging) {
                        Point size = new Point();
                        wm.getDefaultDisplay().getSize(size);
                        int side = prefs().getInt("floatingBackSide", SIDE_LEFT);
                        if (side == SIDE_TOP) {
                            lp.x = clamp(startX + dx, 0, Math.max(0, size.x - btnSize));
                        } else {
                            lp.y = clamp(startY + dy, 0, Math.max(0, size.y - btnSize));
                        }
                        try { wm.updateViewLayout(buttonView, lp); } catch (Exception ignored) {}
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        int side = prefs().getInt("floatingBackSide", SIDE_LEFT);
                        int offset = (side == SIDE_TOP) ? lp.x : lp.y;
                        // commit() (синхронно) — иначе при засыпании Native убивается до сброса
                        // на диск и позиция теряется (кнопка появляется по центру).
                        prefs().edit().putInt("floatingBackOffset", offset).commit();
                        Log.i(TAG, "позиция сохранена offset=" + offset);
                    } else {
                        performBackNow("floating button");
                    }
                    return true;
            }
            return false;
        }
    }

    private void hideButton() {
        if (buttonView != null && wm != null) {
            try { wm.removeView(buttonView); } catch (Exception ignored) {}
            buttonView = null;
            Log.i(TAG, "кнопка убрана");
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        hideButton();
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        hideButton();
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
