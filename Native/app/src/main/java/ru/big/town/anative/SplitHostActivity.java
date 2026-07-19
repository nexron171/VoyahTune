package ru.big.town.anative;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Outline;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.LinearLayout;

import java.lang.reflect.Method;

/**
 * Хост сплита на VirtualDisplay: каждое из двух приложений запускается на СВОЁМ
 * VirtualDisplay, картинка которого рендерится в свой SurfaceView. Даёт то, чего не может
 * freeform:
 *  - per-app DPI: плотность задаётся на каждый VirtualDisplay ({@code createVirtualDisplay(...,densityDpi,...)});
 *  - живой ресайз пропорций: тянем разделитель → меняем веса SurfaceView → {@code vd.resize(w,h,dpi)}
 *    без перезапуска приложений.
 *
 * ⚠️ Доставка ВВОДА (касаний) в VirtualDisplay требует INJECT_EVENTS — это ЧИСТЫЙ signature
 * пермишен, которого у Native фактически НЕТ (голова на release-keys, подпись dev-ключом; whitelist
 * его не выдаёт). Поэтому {@link #injectTouch} обёрнут в try/catch и на этой голове, скорее всего,
 * бросит SecurityException. Рабочая доставка ввода — отдельная задача (root+Frida-инъекция вызова
 * injectInputEvent в system_server, как делает VoyahTweaks, ЛИБО роутинг ввода самим WM для
 * trusted-дисплея). Рендер, per-app DPI и живой ресайз работают независимо от ввода.
 *
 * Extras: leftPkg, rightPkg (String), ratio (int 0..4), leftDpi, rightDpi (int, 0=дефолт дисплея).
 */
public class SplitHostActivity extends Activity {

    private static final String TAG = "$$$ SplitHostActivity $$$";

    public static final String EXTRA_LEFT     = "leftPkg";
    public static final String EXTRA_RIGHT    = "rightPkg";
    public static final String EXTRA_RATIO    = "ratio";
    public static final String EXTRA_LEFT_DPI = "leftDpi";
    public static final String EXTRA_RIGHT_DPI = "rightDpi";
    // Док-модель (дубли ярлыков/сплитов с нашего главного): dockApps="pkg|dpi",
    // dockSplits="lpkg|rpkg|ratio|leftDpi|rightDpi". Нужны, чтобы док работал поверх открытых окон.
    public static final String EXTRA_DOCK_APPS   = "dockApps";
    public static final String EXTRA_DOCK_SPLITS = "dockSplits";

    private static final String LAUNCHER_PKG = "ru.big.town.restoremode";

    // Флаги VirtualDisplay. TRUSTED(1<<10) обязателен, чтобы на дисплей можно было запускать
    // чужие активити и (в перспективе) роутить ввод; требует ADD_TRUSTED_DISPLAY (privapp whitelist).
    // PUBLIC(1<<0) | OWN_CONTENT_ONLY(1<<3) | DESTROY_CONTENT_ON_REMOVAL(1<<8) | TRUSTED(1<<10) = 1289.
    private static final int VD_FLAGS_TRUSTED  = 1 | 8 | 256 | 1024;
    // Фолбэк без TRUSTED (если ADD_TRUSTED_DISPLAY не выдан, напр. на эмуляторе) — рендер будет,
    // запуск чужой активити может не пройти, но не роняем приложение.
    private static final int VD_FLAGS_FALLBACK = 1 | 8 | 256;

    private DisplayManager displayManager;
    private int defaultDpi = 213;
    private boolean touchWarned = false;

    /** Одна «панель» сплита: контейнер + SurfaceView + свой VirtualDisplay + запускаемое приложение. */
    private static final class Pane {
        final String side;
        View container;     // FrameLayout с весом и скруглением
        SurfaceView view;
        VirtualDisplay vd;
        String pkg;
        int dpi;            // 0 = дефолт дисплея (приходит из per-app настройки RestoreMode)
        int w, h;
        boolean launched;
        Pane(String side) { this.side = side; }
    }

    private final Pane left  = new Pane("L");
    private final Pane right = new Pane("R");

    private String[] dockApps = new String[0];
    private String[] dockSplits = new String[0];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Поверх всего, не гаснуть, landscape. Edge-to-edge — чтобы получить реальные window insets
        // и самим задать отступы (иначе система инсетит контент и мы бы отступали повторно).
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_split_host);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        applyWindowInsets();

        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        defaultDpi = getResources().getDisplayMetrics().densityDpi;

        Intent in = getIntent();
        left.pkg   = in.getStringExtra(EXTRA_LEFT);
        right.pkg  = in.getStringExtra(EXTRA_RIGHT);
        left.dpi   = in.getIntExtra(EXTRA_LEFT_DPI, 0);
        right.dpi  = in.getIntExtra(EXTRA_RIGHT_DPI, 0);
        int ratio  = in.getIntExtra(EXTRA_RATIO, 1);
        String[] da = in.getStringArrayExtra(EXTRA_DOCK_APPS);
        String[] ds = in.getStringArrayExtra(EXTRA_DOCK_SPLITS);
        dockApps   = (da != null) ? da : new String[0];
        dockSplits = (ds != null) ? ds : new String[0];

        left.container  = findViewById(R.id.splitPaneLeft);
        right.container = findViewById(R.id.splitPaneRight);
        left.view       = findViewById(R.id.splitSurfaceLeft);
        right.view      = findViewById(R.id.splitSurfaceRight);

        // Одиночный режим (ярлык): правый пакет пуст → одно окно на всю ширину, без разделителя.
        boolean single = (right.pkg == null || right.pkg.isEmpty());

        if (single) {
            findViewById(R.id.splitDivider).setVisibility(View.GONE);
            right.container.setVisibility(View.GONE);
            setWeight(left.container, 1f);
        } else {
            applyRatioWeights(ratio);
        }

        setupSurface(left);
        applyRoundedCorners(left.container);
        if (!single) {
            setupSurface(right);
            setupDivider();
            applyRoundedCorners(right.container);
        }

        buildDock();

        Log.i(TAG, "onCreate single=" + single + " left=" + left.pkg + " right=" + right.pkg
                + " ratio=" + ratio + " lDpi=" + left.dpi + " rDpi=" + right.dpi
                + " defaultDpi=" + defaultDpi + " dockApps=" + dockApps.length
                + " dockSplits=" + dockSplits.length);
    }

    /**
     * Отступы области окон по window insets + левый док лончера. Левый системный навбар/док головы
     * НЕ сообщает свой размер в insets (висит поверх), поэтому гарантируем минимум ≈144dp слева
     * (как в старом freeform-сплите: контент головы начинается с x≈142px). Статус-бар и прочие
     * системные панели берём из реальных insets (+ фолбэк status_bar_height, если пришёл 0).
     */
    private void applyWindowInsets() {
        final float density = getResources().getDisplayMetrics().density;
        final int gap = Math.round(density * 6f);      // небольшой внутренний зазор
        final LinearLayout panes = findViewById(R.id.splitPanes);
        final View dock = findViewById(R.id.splitDock);
        View root = findViewById(R.id.splitHostRoot);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets sb =
                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            int top = sb.top;
            if (top == 0) {
                int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) top = getResources().getDimensionPixelSize(id);
            }
            // Наш док теперь и есть «левый отступ»: иконки под статус-баром, кубик над навбаром.
            dock.setPadding(sb.left, top, 0, sb.bottom);
            // Область окон — сразу справа от дока, только внутренний зазор + правый/нижний/верхний insets.
            panes.setPadding(gap, top + gap, sb.right + gap, sb.bottom + gap);
            return insets;
        });
    }

    // -------------------------------------------------------------------------
    // Док (тот же, что на нашем главном) — работает поверх открытых окон
    // -------------------------------------------------------------------------

    /** Наполнить левый док иконками из переданной модели + повесить действия. */
    private void buildDock() {
        LinearLayout col = findViewById(R.id.splitDockIcons);
        if (col != null) {
            col.removeAllViews();
            android.content.pm.PackageManager pm = getPackageManager();
            android.view.LayoutInflater inf = android.view.LayoutInflater.from(this);
            int gap = Math.round(getResources().getDisplayMetrics().density * 7f);

            // Сплиты (две мини-иконки) → переключить хост на этот сплит
            for (String s : dockSplits) {
                if (s == null) continue;
                String[] p = s.split("\\|");
                if (p.length < 3) continue;
                final String lp = p[0], rp = p[1];
                final int rt = parseIntSafe(p[2], 1);
                final int ld = p.length > 3 ? parseIntSafe(p[3], 0) : 0;
                final int rd = p.length > 4 ? parseIntSafe(p[4], 0) : 0;
                View item = inf.inflate(R.layout.item_dock_split, col, false);
                setIcon(pm, item, R.id.dockIcoLeft, lp);
                setIcon(pm, item, R.id.dockIcoRight, rp);
                item.setOnClickListener(v -> relaunchHost(lp, rp, rt, ld, rd));
                addDockItem(col, item, gap);
            }
            // Ярлыки приложений (одна иконка) → одиночное полноэкранное окно на VD
            for (String s : dockApps) {
                if (s == null) continue;
                String[] p = s.split("\\|");
                final String pk = p[0];
                final int dp = p.length > 1 ? parseIntSafe(p[1], 0) : 0;
                View item = inf.inflate(R.layout.item_dock_app, col, false);
                setIcon(pm, item, R.id.dockIco, pk);
                item.setOnClickListener(v -> relaunchHost(pk, "", 0, dp, 0));
                addDockItem(col, item, gap);
            }
        }
        // Кубик снизу → вернуться на наш главный экран (RestoreMode)
        View home = findViewById(R.id.splitDockHome);
        if (home != null) home.setOnClickListener(v -> goHomeToLauncher());
    }

    private void setIcon(android.content.pm.PackageManager pm, View item, int id, String pkg) {
        android.widget.ImageView iv = item.findViewById(id);
        try { iv.setImageDrawable(pm.getApplicationIcon(pkg)); } catch (Exception ignored) {}
    }

    private void addDockItem(LinearLayout col, View item, int bottomGap) {
        // WRAP_CONTENT по ширине + gravity=center_horizontal у колонки → иконки строго по центру дока.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = bottomGap;
        col.addView(item, lp);
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    /**
     * Переключить хост на другое приложение/сплит из дока: перезапуск хоста с новым конфигом,
     * док-модель несём дальше. Надёжнее живого пересбора панелей и переиспользует весь onCreate.
     */
    private void relaunchHost(String l, String r, int ratio, int ld, int rd) {
        Intent i = new Intent(this, SplitHostActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        i.putExtra(EXTRA_LEFT, l);
        i.putExtra(EXTRA_RIGHT, r);
        i.putExtra(EXTRA_RATIO, ratio);
        i.putExtra(EXTRA_LEFT_DPI, ld);
        i.putExtra(EXTRA_RIGHT_DPI, rd);
        i.putExtra(EXTRA_DOCK_APPS, dockApps);
        i.putExtra(EXTRA_DOCK_SPLITS, dockSplits);
        startActivity(i);
    }

    /** Кубик — вернуться на наш главный экран (RestoreMode home), закрыв хост. */
    private void goHomeToLauncher() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(LAUNCHER_PKG);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "goHomeToLauncher failed: " + e.getMessage());
        }
        finish();
    }

    // Вес левого окна по соотношению (0=3:4,1=1:1,2=4:3,3=5:2,4=2:5) — как во freeform-движке.
    private void applyRatioWeights(int ratio) {
        float lw;
        switch (ratio) {
            case 0: lw = 3f; break;
            case 2: lw = 4f; break;
            case 3: lw = 5f; break;
            case 4: lw = 2f; break;
            default: lw = 1f; break;
        }
        float rw;
        switch (ratio) {
            case 0: rw = 4f; break;
            case 2: rw = 3f; break;
            case 3: rw = 2f; break;
            case 4: rw = 5f; break;
            default: rw = 1f; break;
        }
        setWeight(left.container, lw);
        setWeight(right.container, rw);
    }

    private void setWeight(View v, float w) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        lp.weight = w;
        v.setLayoutParams(lp);
    }

    // -------------------------------------------------------------------------
    // Surface → VirtualDisplay → запуск приложения
    // -------------------------------------------------------------------------

    private void setupSurface(final Pane pane) {
        pane.view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) { }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                pane.w = width;
                pane.h = height;
                if (pane.vd == null) {
                    createVirtualDisplay(pane, holder.getSurface());
                    launchApp(pane);
                } else {
                    try {
                        pane.vd.resize(width, height, effectiveDpi(pane));
                    } catch (Exception e) {
                        Log.w(TAG, "resize " + pane.side + " failed: " + e.getMessage());
                    }
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                releasePane(pane);
            }
        });

        // Пересылка касаний в соответствующий VirtualDisplay (см. предупреждение в шапке класса).
        pane.view.setOnTouchListener((v, ev) -> {
            if (pane.vd != null) injectTouch(pane, ev);
            return true;
        });
    }

    private void createVirtualDisplay(Pane pane, Surface surface) {
        int dpi = effectiveDpi(pane);
        String name = "voyah-split-" + pane.side;
        try {
            pane.vd = displayManager.createVirtualDisplay(name, pane.w, pane.h, dpi, surface, VD_FLAGS_TRUSTED);
            Log.i(TAG, "VD " + pane.side + " (trusted) id="
                    + (pane.vd != null ? pane.vd.getDisplay().getDisplayId() : -1)
                    + " " + pane.w + "x" + pane.h + " dpi=" + dpi);
        } catch (Exception e) {
            Log.w(TAG, "VD " + pane.side + " trusted failed (" + e.getMessage() + ") → fallback");
            try {
                pane.vd = displayManager.createVirtualDisplay(name, pane.w, pane.h, dpi, surface, VD_FLAGS_FALLBACK);
                Log.i(TAG, "VD " + pane.side + " (fallback) id="
                        + (pane.vd != null ? pane.vd.getDisplay().getDisplayId() : -1));
            } catch (Exception e2) {
                Log.e(TAG, "VD " + pane.side + " fallback failed: " + e2.getMessage());
            }
        }
    }

    private void launchApp(Pane pane) {
        if (pane.vd == null || pane.launched || pane.pkg == null || pane.pkg.isEmpty()) return;
        try {
            Intent li = getPackageManager().getLaunchIntentForPackage(pane.pkg);
            if (li == null) {
                Log.w(TAG, "нет launch intent для " + pane.pkg);
                return;
            }
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(pane.vd.getDisplay().getDisplayId());
            startActivity(li, o.toBundle());
            pane.launched = true;
            Log.i(TAG, "launched " + pane.pkg + " on display " + pane.vd.getDisplay().getDisplayId());
        } catch (Exception e) {
            Log.e(TAG, "launchApp " + pane.pkg + " failed: " + e.getMessage());
        }
    }

    private int effectiveDpi(Pane pane) {
        return pane.dpi > 0 ? pane.dpi : defaultDpi;
    }

    private void releasePane(Pane pane) {
        if (pane.vd != null) {
            try { pane.vd.release(); } catch (Exception ignored) {}
            pane.vd = null;
            pane.launched = false;
        }
    }

    // -------------------------------------------------------------------------
    // Ввод (инъекция в VirtualDisplay) — hidden API, нужен INJECT_EVENTS (см. шапку класса)
    // -------------------------------------------------------------------------

    private void injectTouch(Pane pane, MotionEvent ev) {
        try {
            int displayId = pane.vd.getDisplay().getDisplayId();
            MotionEvent copy = MotionEvent.obtain(ev);
            // MotionEvent.setDisplayId(int) — hidden
            Method setDisplayId = MotionEvent.class.getMethod("setDisplayId", int.class);
            setDisplayId.invoke(copy, displayId);
            // InputManager.injectInputEvent(InputEvent, int) — hidden; 0 = INJECT_INPUT_EVENT_MODE_ASYNC
            Object im = getSystemService("input");
            Method inject = im.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
            inject.invoke(im, copy, 0);
            copy.recycle();
        } catch (Exception e) {
            if (!touchWarned) {
                touchWarned = true;
                Log.w(TAG, "injectTouch недоступен (нет INJECT_EVENTS у Native): " + e.getMessage()
                        + " — ввод в VD требует root+Frida-в-system_server или роутинга WM для trusted-дисплея");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Разделитель (живой ресайз пропорций)
    // -------------------------------------------------------------------------

    private void setupDivider() {
        final View divider = findViewById(R.id.splitDivider);
        final LinearLayout panes = findViewById(R.id.splitPanes);

        // Двойной тап по handle-бару — поменять окна местами.
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                swapApps();
                return true;
            }
        });

        divider.setOnTouchListener(new View.OnTouchListener() {
            float startX;
            float startLW, startRW;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                gd.onTouchEvent(e);  // двойной тап → swap
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getRawX();
                        startLW = ((LinearLayout.LayoutParams) left.container.getLayoutParams()).weight;
                        startRW = ((LinearLayout.LayoutParams) right.container.getLayoutParams()).weight;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float total = startLW + startRW;
                        int usable = panes.getWidth() - divider.getWidth();
                        if (usable <= 0) return true;
                        float dxWeight = (e.getRawX() - startX) / usable * total;
                        float nl = startLW + dxWeight;
                        float nr = startRW - dxWeight;
                        float min = total * 0.15f;   // не даём окну схлопнуться
                        if (nl < min || nr < min) return true;
                        setWeight(left.container, nl);
                        setWeight(right.container, nr);
                        return true;
                    }
                }
                return false;
            }
        });
    }

    /** Скруглённые углы окна (SurfaceView через outline-клип). */
    private void applyRoundedCorners(View v) {
        if (v == null) return;
        final float r = getResources().getDisplayMetrics().density * 18f;
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    /**
     * Меняет приложения (и их DPI) местами: левое ↔ правое. Пересоздаём оба VirtualDisplay на
     * тех же surface — приложения на старых дисплеях уничтожаются (DESTROY_CONTENT_ON_REMOVAL),
     * запускаем поменянные. Без выхода из хоста.
     */
    private void swapApps() {
        String tp = left.pkg; left.pkg = right.pkg; right.pkg = tp;
        int td = left.dpi; left.dpi = right.dpi; right.dpi = td; // DPI едет за приложением
        Log.i(TAG, "swapApps → left=" + left.pkg + " right=" + right.pkg);
        recreatePane(left);
        recreatePane(right);
    }

    private void recreatePane(Pane pane) {
        releasePane(pane);
        Surface s = pane.view.getHolder().getSurface();
        if (s != null && s.isValid() && pane.w > 0 && pane.h > 0) {
            createVirtualDisplay(pane, s);
            launchApp(pane);
        }
    }

    @Override
    protected void onDestroy() {
        releasePane(left);
        releasePane(right);
        super.onDestroy();
    }
}
