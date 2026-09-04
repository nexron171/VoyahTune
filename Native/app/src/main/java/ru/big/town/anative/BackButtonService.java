package ru.big.town.anative;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
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
import android.view.Display;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * Системные действия «Назад»/Home и их опциональный плавающий блок. Реализованы одним сервисом
 * доступности, чтобы:
 *  - рисовать оверлей через TYPE_ACCESSIBILITY_OVERLAY (не нужен SYSTEM_ALERT_WINDOW);
 *  - выполнять системные действия через performGlobalAction.
 * Положение (сторона слева/сверху/справа) + смещение вдоль стороны хранятся в NativePrefs
 * ("floatingBackSide"/"floatingBackOffset"), блок перетаскивается вдоль выбранной стороны.
 */
public class BackButtonService extends AccessibilityService {
    static final String TAG = "$$$ BackButtonService $$$";
    static final int SIDE_LEFT = 0, SIDE_TOP = 1, SIDE_RIGHT = 2;
    private static final String COMPONENT =
            "ru.big.town.anative/ru.big.town.anative.BackButtonService";
    private static final String PREF_FLOATING = "floatingBack";
    private static final String PREF_STEERING = "steeringBack";
    private static final String PREF_FULLSCREEN = "fullscreenApps";
    private static final int BUTTON_VISUAL_SIZE_DP = 45;
    private static final int BUTTON_GAP_DP = 10;
    private static final int BUTTON_TOUCH_OUTSET_DP = 10;

    private static BackButtonService instance;

    private WindowManager wm;
    private LinearLayout buttonView;
    private WindowManager.LayoutParams lp;
    private int btnSize;
    private int btnGap;
    private int touchOutset;
    private final Handler overlayHandler = new Handler(Looper.getMainLooper());
    private String pendingEventPackage = "";
    private String lastForcedPackage = "";
    private final Runnable overlayReevaluate = () -> reevaluateOverlayNow(pendingEventPackage);

    private SharedPreferences prefs() {
        return getSharedPreferences("NativePrefs", Context.MODE_PRIVATE);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        if (prefs().getBoolean(PREF_FLOATING, false)) {
            Log.i(TAG, "onServiceConnected — показываем пользовательский оверлей");
            showButton();
        } else {
            Log.i(TAG, "onServiceConnected — ждём полноэкранное приложение");
        }
        scheduleOverlayEvaluation(null);
    }

    /** Включает оверлей, не отключая accessibility-сервис, если он нужен действию кнопки руля. */
    static void setFloatingButtonEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_FLOATING, enabled).apply();
        syncAccessibility(context);
        BackButtonService live = instance;
        if (live != null) live.scheduleOverlayEvaluation(null);
    }

    /** Держит accessibility-сервис подключённым, когда хотя бы один слот руля вызывает «Назад». */
    static void setSteeringBackEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_STEERING, enabled).apply();
        syncAccessibility(context);
    }

    /** Полноэкранные приложения держат сервис подключённым даже при выключенном постоянном оверлее. */
    static void setFullscreenPackages(Context context, String packagesCsv) {
        String normalized = FullscreenPackagePolicy.normalizeCsv(packagesCsv);
        prefs(context).edit().putString(PREF_FULLSCREEN, normalized).apply();
        syncAccessibility(context);
        BackButtonService live = instance;
        if (live != null) live.scheduleOverlayEvaluation(null);
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
        performGlobalActionNow(GLOBAL_ACTION_BACK, "GLOBAL_ACTION_BACK", source);
    }

    private void performGlobalActionNow(int action, String actionName, String source) {
        boolean ok = performGlobalAction(action);
        Log.i(TAG, actionName + " (" + source + ") -> " + ok);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences("NativePrefs", Context.MODE_PRIVATE);
    }

    /** Сервис нужен постоянному оверлею, рулю или автоматическим кнопкам полноэкранного приложения. */
    private static void syncAccessibility(Context context) {
        SharedPreferences prefs = prefs(context);
        boolean present = FullscreenPackagePolicy.requiresAccessibilityService(
                prefs.getBoolean(PREF_FLOATING, false),
                prefs.getBoolean(PREF_STEERING, false),
                prefs.getString(PREF_FULLSCREEN, ""));
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
                    + "; steering=" + prefs.getBoolean(PREF_STEERING, false)
                    + "; fullscreen=" + prefs.getString(PREF_FULLSCREEN, ""));
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

    private void scheduleOverlayEvaluation(String eventPackage) {
        if (eventPackage != null) pendingEventPackage = eventPackage;
        overlayHandler.removeCallbacks(overlayReevaluate);
        overlayHandler.postDelayed(overlayReevaluate, 120L);
    }

    private void reevaluateOverlayNow(String fallbackPackage) {
        String fullscreenCsv = prefs().getString(PREF_FULLSCREEN, "");
        // Событие выбранного пакета — самый быстрый и точный сигнал входа. Для остальных событий
        // (IME/SystemUI/диалог поверх приложения) перепроверяем реальную верхнюю physical task.
        String topPackage = FullscreenPackagePolicy.contains(fullscreenCsv, fallbackPackage)
                ? fallbackPackage : defaultDisplayTopPackage();
        if (topPackage.isEmpty() && fallbackPackage != null) topPackage = fallbackPackage;
        boolean persistent = prefs().getBoolean(PREF_FLOATING, false);
        boolean forced = FullscreenPackagePolicy.contains(fullscreenCsv, topPackage);
        if (FullscreenPackagePolicy.shouldShowOverlay(persistent, fullscreenCsv, topPackage)) {
            if (buttonView == null) showButton();
        } else {
            hideButton();
        }
        String forcedPackage = forced ? topPackage : "";
        if (!forcedPackage.equals(lastForcedPackage)) {
            Log.i(TAG, forced ? "forced overlay ON for " + topPackage : "forced overlay OFF");
            lastForcedPackage = forcedPackage;
        }
    }

    @SuppressWarnings("deprecation")
    private String defaultDisplayTopPackage() {
        try {
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (manager == null) return "";
            String firstVisiblePackage = "";
            for (ActivityManager.RunningTaskInfo task : manager.getRunningTasks(20)) {
                if (task.topActivity == null) continue;
                if (firstVisiblePackage.isEmpty()) {
                    firstVisiblePackage = task.topActivity.getPackageName();
                }
                if (runningTaskDisplayId(task) == Display.DEFAULT_DISPLAY) {
                    return task.topActivity.getPackageName();
                }
            }
            return firstVisiblePackage;
        } catch (Exception e) {
            Log.w(TAG, "top task unavailable: " + e.getMessage());
        }
        return "";
    }

    /** displayId скрыт в SDK этой Android 11 ROM, но присутствует в runtime TaskInfo. */
    private static int runningTaskDisplayId(ActivityManager.RunningTaskInfo task) {
        Class<?> type = task.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField("displayId");
                field.setAccessible(true);
                return field.getInt(task);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (Exception e) {
                return -1;
            }
        }
        return -1;
    }

    private void showButton() {
        if (buttonView != null) { applyLayout(); return; }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) { Log.e(TAG, "WindowManager == null"); return; }

        btnSize = dp(BUTTON_VISUAL_SIZE_DP);
        btnGap = dp(BUTTON_GAP_DP);
        touchOutset = dp(BUTTON_TOUCH_OUTSET_DP);
        int initialSide = prefs().getInt("floatingBackSide", SIDE_LEFT);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setShowDividers(LinearLayout.SHOW_DIVIDER_NONE);
        buttons.setBackground(null);
        buttons.addView(createButton(
                R.drawable.ic_back_arrow,
                R.string.floating_back_button_desc));
        buttons.addView(createButton(
                R.drawable.ic_home,
                R.string.floating_home_button_desc));
        buttons.setOnTouchListener(new DragTouchListener());

        int overlayLength = btnSize * 2 + btnGap + touchOutset * 2;
        int overlayThickness = btnSize + touchOutset * 2;

        lp = new WindowManager.LayoutParams(
                initialSide == SIDE_TOP ? overlayLength : overlayThickness,
                initialSide == SIDE_TOP ? overlayThickness : overlayLength,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.START | Gravity.TOP;

        try {
            wm.addView(buttons, lp);
            buttonView = buttons;
            applyLayout();
            Log.i(TAG, "кнопки Назад/Home добавлены");
        } catch (Exception e) {
            Log.e(TAG, "addView failed: " + e.getMessage());
        }
    }

    private ImageView createButton(int iconRes, int descriptionRes) {
        ImageView button = new ImageView(this);
        button.setImageResource(iconRes);
        button.setBackground(null);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setContentDescription(getString(descriptionRes));
        int pad = dp(10);
        button.setPadding(pad, pad, pad, pad);
        button.setLayoutParams(new LinearLayout.LayoutParams(btnSize, btnSize));
        return button;
    }

    /**
     * Видимые кнопки имеют размер 45 dp и зазор 10 dp. Прозрачный отступ вокруг блока входит
     * в окно и делится между ближайшими кнопками, поэтому touch-зона каждой получается заметно
     * больше изображения, включая половину межкнопочного зазора.
     */
    private void applyButtonGeometry(int side) {
        boolean horizontal = side == SIDE_TOP;
        buttonView.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        buttonView.setPadding(touchOutset, touchOutset, touchOutset, touchOutset);
        for (int i = 0; i < buttonView.getChildCount(); i++) {
            LinearLayout.LayoutParams childLp =
                    new LinearLayout.LayoutParams(btnSize, btnSize);
            if (i == 0) {
                if (horizontal) childLp.rightMargin = btnGap;
                else childLp.bottomMargin = btnGap;
            }
            buttonView.getChildAt(i).setLayoutParams(childLp);
        }
    }

    /** Раскладывает блок по выбранной стороне и сохранённому смещению (offset<0 = по центру стороны). */
    private void applyLayout() {
        if (buttonView == null || wm == null || lp == null) return;
        int side = prefs().getInt("floatingBackSide", SIDE_LEFT);
        int offset = prefs().getInt("floatingBackOffset", -1);

        Point size = new Point();
        wm.getDefaultDisplay().getSize(size);
        int sw = size.x, sh = size.y;
        int overlayLength = btnSize * 2 + btnGap + touchOutset * 2;
        int overlayThickness = btnSize + touchOutset * 2;

        lp.gravity = Gravity.START | Gravity.TOP;
        applyButtonGeometry(side);
        if (side == SIDE_TOP) {
            lp.width = overlayLength;
            lp.height = overlayThickness;
            int maxX = Math.max(0, sw - lp.width);
            lp.x = (offset < 0) ? maxX / 2 : clamp(offset, 0, maxX);
            lp.y = 0;
        } else { // LEFT / RIGHT — двигается по вертикали
            lp.width = overlayThickness;
            lp.height = overlayLength;
            int maxY = Math.max(0, sh - lp.height);
            lp.y = (offset < 0) ? maxY / 2 : clamp(offset, 0, maxY);
            lp.x = (side == SIDE_RIGHT) ? Math.max(0, sw - lp.width) : 0;
        }
        try {
            wm.updateViewLayout(buttonView, lp);
            Log.i(TAG, "applyLayout side=" + side + " x=" + lp.x + " y=" + lp.y);
        } catch (Exception ignored) {
        }
    }

    /** Тап = действие выбранной кнопки, перетаскивание = смена позиции всего блока. */
    private class DragTouchListener implements View.OnTouchListener {
        private int globalAction;
        private String actionName;
        private int startX, startY;
        private float rawX0, rawY0;
        private boolean dragging;
        private final int slop = ViewConfiguration.get(BackButtonService.this).getScaledTouchSlop();

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    int side = prefs().getInt("floatingBackSide", SIDE_LEFT);
                    boolean home = side == SIDE_TOP
                            ? e.getX() >= v.getWidth() / 2f
                            : e.getY() >= v.getHeight() / 2f;
                    globalAction = home ? GLOBAL_ACTION_HOME : GLOBAL_ACTION_BACK;
                    actionName = home ? "GLOBAL_ACTION_HOME" : "GLOBAL_ACTION_BACK";
                    startX = lp.x; startY = lp.y;
                    rawX0 = e.getRawX(); rawY0 = e.getRawY();
                    dragging = false;
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (e.getRawX() - rawX0);
                    int dy = (int) (e.getRawY() - rawY0);
                    if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                    if (dragging) {
                        Point size = new Point();
                        wm.getDefaultDisplay().getSize(size);
                        int side = prefs().getInt("floatingBackSide", SIDE_LEFT);
                        if (side == SIDE_TOP) {
                            lp.x = clamp(startX + dx, 0, Math.max(0, size.x - lp.width));
                        } else {
                            lp.y = clamp(startY + dy, 0, Math.max(0, size.y - lp.height));
                        }
                        try { wm.updateViewLayout(buttonView, lp); } catch (Exception ignored) {}
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        int side = prefs().getInt("floatingBackSide", SIDE_LEFT);
                        int offset = (side == SIDE_TOP) ? lp.x : lp.y;
                        // commit() (синхронно) — иначе при засыпании Native убивается до сброса
                        // на диск и позиция теряется (кнопка появляется по центру).
                        prefs().edit().putInt("floatingBackOffset", offset).commit();
                        Log.i(TAG, "позиция сохранена offset=" + offset);
                    } else {
                        performGlobalActionNow(globalAction, actionName, "floating button");
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    applyLayout();
                    return true;
            }
            return false;
        }
    }

    private void hideButton() {
        if (buttonView != null && wm != null) {
            try { wm.removeView(buttonView); } catch (Exception ignored) {}
            buttonView = null;
            Log.i(TAG, "блок кнопок убран");
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event == null ? null : event.getPackageName();
        scheduleOverlayEvaluation(pkg == null ? null : pkg.toString());
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        overlayHandler.removeCallbacks(overlayReevaluate);
        hideButton();
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        overlayHandler.removeCallbacks(overlayReevaluate);
        hideButton();
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
