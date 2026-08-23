package ru.big.town.anative;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.reflect.Method;

/**
 * Хост одного приложения или сплита на VirtualDisplay: каждая видимая панель запускается на СВОЁМ
 * VirtualDisplay, картинка которого рендерится в SurfaceView. Однопанельный режим заменяет legacy
 * system_server freeform hot-hooks; двухпанельный даёт полноценный сплит. Оба режима поддерживают:
 *  - per-app DPI: плотность задаётся на каждый VirtualDisplay ({@code createVirtualDisplay(...,densityDpi,...)});
 *  - ресайз пропорций: во время жеста двигаем безопасное превью, на отпускании один раз меняем веса
 *    SurfaceView → {@code vd.resize(w,h,dpi)}. Activity стороннего приложения при этом может штатно
 *    пересоздаться из-за configuration change.
 *
 * ⚠️ Доставка ВВОДА (касаний) в VirtualDisplay требует INJECT_EVENTS — это ЧИСТЫЙ signature
 * пермишен, которого у Native фактически НЕТ (голова на release-keys, подпись dev-ключом; whitelist
 * его не выдаёт). Поэтому {@link #injectTouch} обёрнут в try/catch и на этой голове, скорее всего,
 * бросит SecurityException. Рабочая доставка ввода — отдельная задача (root+Frida-инъекция вызова
 * injectInputEvent в system_server ЛИБО роутинг ввода самим WM для
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
    public static final String EXTRA_RESIZABLE  = "resizable";
    public static final String EXTRA_SPLIT      = "split";
    public static final String EXTRA_PRESET_IDX = "presetIdx";
    public static final String EXTRA_PRESET_ID  = "presetId";

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
        long resizeVersion; // успешные vd.resize; нужен для снятия маски после реального layout обеих панелей
        boolean launched;
        long launchedAt;    // когда стартовали приложение — надзирателю нужно дать ему подняться
        int restarts;       // сколько раз надзиратель уже перезапускал панель за эту сессию сплита
        Pane(String side) { this.side = side; }
    }

    // --- Надзиратель панелей ---------------------------------------------------------------------
    // Приложение может умереть УЖЕ ПОСЛЕ успешного запуска (краш в чужом VirtualDisplay). Раньше это
    // не замечал никто: pane.launched оставался true, панель просто чернела, и лечил только перезапуск
    // сплита руками. Надзиратель периодически проверяет, жива ли задача приложения НА СВОЁМ дисплее,
    // и поднимает её заново.
    private static final long WATCH_PERIOD_MS  = 2500;  // период опроса
    private static final long WATCH_GRACE_MS   = 8000;  // столько не трогаем панель после запуска (старт приложения)
    private static final int  WATCH_MAX_RESTARTS = 3;   // предохранитель от бесконечного цикла перезапусков
    private final android.os.Handler watchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable watchTick = new Runnable() {
        @Override public void run() {
            try { supervisePanes(); } catch (Exception e) { Log.w(TAG, "supervise: " + e.getMessage()); }
            watchHandler.postDelayed(this, WATCH_PERIOD_MS);
        }
    };

    // Для фиксированного пресета делитель только визуальный; resizable-пресет коммитит размер один раз
    // на отпускании. Двойной тап в обоих режимах меняет окна местами.
    private final Pane left  = new Pane("L");
    private final Pane right = new Pane("R");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // LIGHT-сборка: VD-сплит-хост отключён (нет Frida/trusted-display) — сразу закрываемся.
        if (!BuildConfig.IS_FULL) { finish(); return; }
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
        resizable  = in.getBooleanExtra(EXTRA_RESIZABLE, false);
        presetIdx  = in.getIntExtra(EXTRA_PRESET_IDX, -1);
        presetId   = in.getStringExtra(EXTRA_PRESET_ID);
        float startSplit = in.getFloatExtra(EXTRA_SPLIT, 0f);

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
        } else if (resizable && startSplit > 0.05f && startSplit < 0.95f) {
            applyFraction(startSplit);      // пропорция, выставленная рукой в прошлый раз
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

        Log.i(TAG, "onCreate single=" + single + " left=" + left.pkg + " right=" + right.pkg
                + " ratio=" + ratio + " lDpi=" + left.dpi + " rDpi=" + right.dpi
                + " defaultDpi=" + defaultDpi + " resizable=" + resizable
                + " split=" + startSplit + " presetId=" + presetId);
    }

    /**
     * Хост уже открыт (launchMode=singleTop) и пришёл НОВЫЙ запрос — напр. клик по иконке дока
     * (одиночный запуск) поверх ранее открытого сплита. Без этого singleTop получил бы onNewIntent,
     * а старый сплит остался бы на экране. Обновляем интент и пересобираем хост с чистого листа:
     * старые VirtualDisplay/панели освобождаются в onDestroy → onCreate перечитывает новые extras.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        recreate();
    }

    /**
     * Отступы области окон по window insets + левый док лончера. Левый системный навбар/док головы
     * НЕ сообщает свой размер в insets (висит поверх), поэтому гарантируем минимум ≈144dp слева
     * (как в старом freeform-сплите: контент головы начинается с x≈142px). Статус-бар и прочие
     * системные панели берём из реальных insets (+ фолбэк status_bar_height, если пришёл 0).
     */
    private void applyWindowInsets() {
        final float density = getResources().getDisplayMetrics().density;
        final int gap = Math.round(density * 6f);          // небольшой внутренний зазор
        final int nativeDock = Math.round(density * 145f); // родной док головы висит поверх слева (в insets не приходит)
        View root = findViewById(R.id.splitHostRoot);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets sb =
                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            int top = sb.top;
            if (top == 0) {
                int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) top = getResources().getDimensionPixelSize(id);
            }
            // Инсет на КОРЕНЬ (FrameLayout) → и панели, и оверлей-маска отступают одинаково и совпадают.
            v.setPadding(nativeDock + sb.left + gap, top + gap, sb.right + gap, sb.bottom + gap);
            return insets;
        });
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

    /** Разложить панели по доле левого окна (0..1). Веса суммируем в 1 — так проще считать драг. */
    private void applyFraction(float f) {
        setWeight(left.container, f);
        setWeight(right.container, 1f - f);
    }

    /** Текущая доля левого окна по фактической ширине панелей. */
    private float currentFraction() {
        int lw = left.container.getWidth(), rw = right.container.getWidth();
        return (lw + rw > 0) ? (float) lw / (lw + rw) : 0.5f;
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
                    // Реальные окна ресайзятся ТОЛЬКО на отпускании делителя (endResizeMask меняет вес
                    // контейнера один раз) → ровно один surfaceChanged → один чистый vd.resize. Во время
                    // драга сюда не заходим (веса панелей не меняем, двигаем только оверлей-маску).
                    try {
                        pane.vd.resize(width, height, effectiveDpi(pane));
                        pane.resizeVersion++;
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
        final Intent li = getPackageManager().getLaunchIntentForPackage(pane.pkg);
        if (li == null) {
            Log.w(TAG, "нет launch intent для " + pane.pkg);
            return;
        }
        // ВАЖНО (фикс «пустая панель + уехавшее приложение»): приложение-одиночка (launchMode
        // singleTask/singleInstance или общий taskAffinity), УЖЕ открытое на другом дисплее (freeform на
        // display 0/1 или в другом сплите), при setLaunchDisplayId НЕ дублируется, а ПЕРЕЕЗЖАЕТ на наш VD —
        // WM бросает "Failed to find a stack behind stack", панель остаётся пустой. Поэтому освобождаем
        // приложение с исходного дисплея — но НЕ force-stop процесса (иначе музыка глохнет), а завершаем
        // только его ЗАДАЧУ (removeTask): активити умирает, процесс + foreground-плейбек живут → музыка
        // продолжает играть, а окно стартует ЗАНОВО на нашем VD. Teardown асинхронный → запуск с задержкой.
        finishTasksForPackage(pane.pkg);
        pane.launched = true;   // помечаем сразу — повторные проходы (surface recreate) не запустят дважды
        pane.launchedAt = System.currentTimeMillis();   // отсчёт «времени на подъём» для надзирателя
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (pane.vd == null) return;   // сплит закрыли/пересоздали за время задержки
            try {
                int displayId = pane.vd.getDisplay().getDisplayId();
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                ActivityOptions o = ActivityOptions.makeBasic();
                o.setLaunchDisplayId(displayId);
                startActivity(li, o.toBundle());
                Log.i(TAG, "launched " + pane.pkg + " on display " + displayId + " (после force-stop)");
            } catch (Exception e) {
                pane.launched = false;
                Log.e(TAG, "launchApp " + pane.pkg + " failed: " + e.getMessage());
            }
        }, 450);
    }

    /**
     * Завершает ЗАДАЧИ приложения (по всем дисплеям) через {@code IActivityTaskManager.removeTask},
     * НЕ убивая процесс. Активити приложения финишируются и освобождают исходный дисплей (снимается
     * коллизия «одиночка на двух дисплеях»), но процесс с foreground-сервисом жив → плейбек музыки НЕ
     * прерывается. Требует REAL_GET_TASKS (перечислить чужие задачи) + REMOVE_TASKS (завершить их) —
     * оба в privapp-whitelist. Не найдено задач → дешёвый no-op (приложение нигде не открыто, коллизии нет).
     * @return число завершённых задач.
     */
    private int finishTasksForPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return 0;
        int removed = 0;
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(android.content.Context.ACTIVITY_SERVICE);
            Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            java.lang.reflect.Method removeTask = atm.getClass().getMethod("removeTask", int.class);
            for (android.app.ActivityManager.RunningTaskInfo t : am.getRunningTasks(1000)) {
                String p = (t.topActivity != null) ? t.topActivity.getPackageName()
                        : (t.baseActivity != null ? t.baseActivity.getPackageName() : null);
                if (pkg.equals(p)) {
                    try { removeTask.invoke(atm, t.taskId); removed++; }
                    catch (Exception e) { Log.w(TAG, "removeTask " + t.taskId + " (" + pkg + "): " + e.getMessage()); }
                }
            }
            Log.i(TAG, "finishTasksForPackage " + pkg + " → завершено задач: " + removed + " (процесс жив)");
        } catch (Exception e) {
            Throwable c = (e instanceof java.lang.reflect.InvocationTargetException
                    && e.getCause() != null) ? e.getCause() : e;
            Log.w(TAG, "finishTasksForPackage " + pkg + ": " + c);
        }
        return removed;
    }

    /**
     * Жива ли задача приложения панели ИМЕННО НА ЕЁ дисплее. Проверяем привязку к дисплею, а не просто
     * «процесс есть»: приложение могло уехать на другой экран — для панели это равносильно пропаже.
     * Не смогли определить (нет доступа к задачам/полю displayId) — считаем живым, чтобы не перезапускать
     * вслепую.
     */
    private boolean paneAppAlive(Pane pane) {
        if (pane.vd == null || pane.pkg == null || pane.pkg.isEmpty()) return true;
        int paneDisplay;
        try { paneDisplay = pane.vd.getDisplay().getDisplayId(); } catch (Exception e) { return true; }
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(android.content.Context.ACTIVITY_SERVICE);
            for (android.app.ActivityManager.RunningTaskInfo t : am.getRunningTasks(1000)) {
                String p = (t.topActivity != null) ? t.topActivity.getPackageName()
                        : (t.baseActivity != null ? t.baseActivity.getPackageName() : null);
                if (!pane.pkg.equals(p)) continue;
                // TaskInfo.displayId — hidden-поле; для priv-app на /system доступно рефлексией.
                try {
                    java.lang.reflect.Field f = t.getClass().getField("displayId");
                    if (f.getInt(t) == paneDisplay) return true;
                } catch (Exception noField) {
                    return true;   // поля нет — судить не о чем, панель не трогаем
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "paneAppAlive " + pane.pkg + ": " + e.getMessage());
            return true;
        }
        return false;
    }

    /** Один проход надзирателя: поднять панели, чьё приложение исчезло со своего дисплея. */
    private void supervisePanes() {
        if (!watchEnabled()) return;
        // Во время ресайза приложение получает смену конфигурации и может пересоздаться — в этот
        // момент его задачи на дисплее нет. Без этой паузы надзиратель принял бы это за падение и
        // перезапустил приложение прямо под рукой пользователя.
        if (dragging || System.currentTimeMillis() < resizeUntil) return;
        supervisePane(left);
        supervisePane(right);
    }

    private void supervisePane(Pane pane) {
        if (pane.vd == null || !pane.launched) return;                       // панель не запущена — нечего стеречь
        if (System.currentTimeMillis() - pane.launchedAt < WATCH_GRACE_MS) return;  // даём приложению подняться
        if (paneAppAlive(pane)) { pane.restarts = 0; return; }               // живо → счётчик попыток сбрасываем
        if (pane.restarts >= WATCH_MAX_RESTARTS) {
            // Приложение стабильно не живёт на VirtualDisplay — дальнейшие попытки только мигали бы
            // экраном. Останавливаемся и оставляем след в логе, чтобы причину можно было найти.
            if (pane.restarts == WATCH_MAX_RESTARTS) {
                pane.restarts++;
                Log.w(TAG, "надзиратель: " + pane.pkg + " (" + pane.side + ") не удержался после "
                        + WATCH_MAX_RESTARTS + " попыток — перезапуск прекращён");
            }
            return;
        }
        pane.restarts++;
        Log.i(TAG, "надзиратель: " + pane.pkg + " (" + pane.side + ") пропал со своего дисплея"
                + " → перезапуск " + pane.restarts + "/" + WATCH_MAX_RESTARTS);
        pane.launched = false;      // launchApp выходит по этому флагу — снимаем, иначе перезапуска не будет
        launchApp(pane);
    }

    /** Аварийное отключение надзирателя: settings put global voyahtune_splitwatch 0 */
    private boolean watchEnabled() {
        try {
            String v = android.provider.Settings.Global.getString(getContentResolver(), "voyahtune_splitwatch");
            return !"0".equals(v);
        } catch (Exception e) { return true; }
    }

    private int effectiveDpi(Pane pane) {
        return pane.dpi > 0 ? pane.dpi : defaultDpi;
    }

    private void releasePane(Pane pane) {
        if (pane.vd != null) {
            try { pane.vd.release(); } catch (Exception ignored) {}
            pane.vd = null;
            pane.launched = false;
            // Счётчик попыток — свойство ПОПЫТКИ, а не панели: пересоздание (своп, новый сплит) начинает
            // с чистого листа. Иначе исчерпанный лимит переезжал бы на другое приложение и надзиратель
            // молча отказывался бы его поднимать.
            pane.restarts = 0;
        }
    }

    // -------------------------------------------------------------------------
    // Ввод (инъекция в VirtualDisplay) — hidden API, нужен INJECT_EVENTS (см. шапку класса)
    // -------------------------------------------------------------------------

    private void injectTouch(Pane pane, MotionEvent ev) {
        MotionEvent copy = null;
        try {
            int displayId = pane.vd.getDisplay().getDisplayId();
            copy = MotionEvent.obtain(ev);
            // MotionEvent.setDisplayId(int) — hidden
            Method setDisplayId = MotionEvent.class.getMethod("setDisplayId", int.class);
            setDisplayId.invoke(copy, displayId);
            // InputManager.injectInputEvent(InputEvent, int) — hidden; 0 = INJECT_INPUT_EVENT_MODE_ASYNC
            Object im = getSystemService("input");
            Method inject = im.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
            inject.invoke(im, copy, 0);
        } catch (Exception e) {
            if (!touchWarned) {
                touchWarned = true;
                Log.w(TAG, "injectTouch недоступен (нет INJECT_EVENTS у Native): " + e.getMessage()
                        + " — ввод в VD требует root+Frida-в-system_server или роутинга WM для trusted-дисплея");
            }
        } finally {
            if (copy != null) copy.recycle();
        }
    }

    // -------------------------------------------------------------------------
    // Разделитель (живой ресайз пропорций)
    // -------------------------------------------------------------------------

    private void setupDivider() {
        final View divider = findViewById(R.id.splitDivider);

        // Двойной тап по handle-бару — поменять окна местами (работает всегда).
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) { return true; }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                cancelResizeGesture();
                swapApps();
                doubleTapConsumed = true;
                return true;
            }
        });

        final View grip = findViewById(R.id.splitHandleGrip);

        // Диагностика: без неё «делитель мёртв» неотличимо от «resizable не доехал до хоста».
        Log.i(TAG, "setupDivider: resizable=" + resizable + " presetIdx=" + presetIdx
                + " presetId=" + presetId);

        if (!resizable) {   // пропорция зафиксирована пресетом — делитель только визуальный + свап
            divider.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    gripPressed(grip, true);      // видимый щуп: рукоятка реагирует на касание
                    Log.i(TAG, "делитель нажат, но пропорция зафиксирована (resizable=false)");
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                        || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    gripPressed(grip, false);
                }
                gd.onTouchEvent(e);
                return true;
            });
            return;
        }

        divider.setOnTouchListener(new View.OnTouchListener() {
            float startX, startFraction;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                gd.onTouchEvent(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (doubleTapConsumed) { doubleTapConsumed = false; return true; }
                        startX = e.getRawX();
                        startFraction = currentFraction();
                        lastDragFraction = startFraction;
                        gripPressed(grip, true);          // видимый щуп — рукоятка «загорается»
                        Log.i(TAG, "драг начат: fraction=" + startFraction);
                        // beginResize (снимки/оверлей) НЕ должен ронять жест: если PixelCopy/overlay
                        // бросит, делитель всё равно обязан ездить. Поэтому маска — в try, а движение
                        // делителя ниже от неё не зависит.
                        try { beginResize(); } catch (Throwable t) { Log.w(TAG, "beginResize: " + t); }
                        moveDivider(startFraction);       // сразу поставить в текущую позицию
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (resizeState != ResizeState.DRAGGING) return true;
                        float f = fractionForDx(startFraction, e.getRawX() - startX);
                        lastDragFraction = f;
                        moveDivider(f);                   // делитель едет НЕЗАВИСИМО от маски
                        try { previewFraction(f); } catch (Throwable t) { Log.w(TAG, "preview: " + t); }
                        return true;
                    case MotionEvent.ACTION_UP:
                        gripPressed(grip, false);
                        if (resizeState == ResizeState.DRAGGING) {
                            lastDragFraction = fractionForDx(startFraction, e.getRawX() - startX);
                            endResize(lastDragFraction);
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        gripPressed(grip, false);
                        cancelResizeGesture();
                        return true;
                }
                return false;
            }
        });
    }

    /** Сдвиг делителя за пальцем через translationX (не вызывает layout → не будит surfaceChanged). */
    private void moveDivider(float f) {
        View panes = findViewById(R.id.splitPanes);
        View divider = findViewById(R.id.splitDivider);
        if (panes == null || divider == null) return;
        int usable = panes.getWidth() - divider.getWidth();
        if (usable <= 0) return;
        divider.setTranslationX(Math.round(usable * f) - left.container.getWidth());
    }

    /** Видимая индикация нажатия рукоятки (неразрушающе — масштабом, drawable не трогаем). Это и щуп
     *  «касание дошло до делителя»: если при нажатии рукоятка не увеличивается — касания сюда не доходят. */
    private void gripPressed(View grip, boolean pressed) {
        if (grip == null) return;
        float s = pressed ? 1.6f : 1f;
        grip.setScaleX(s);
        grip.setScaleY(s);
    }

    // -------------------------------------------------------------------------
    // Живой ресайз пропорции: во время драга РЕАЛЬНЫЕ панели не трогаем
    // -------------------------------------------------------------------------
    //
    // Почему так. Смена веса панели → layout → surfaceChanged → vd.resize(). А vd.resize() для
    // приложения на этом дисплее — СМЕНА КОНФИГУРАЦИИ: приложение, не объявившее configChanges,
    // пересоздаётся целиком. При драге это десятки пересозданий в секунду — приложение не успевает
    // сойтись ни к какому состоянию (его «плющит», размер не совпадает с окном, и оно не выправляется).
    // Плюс vd.resize() асинхронный: поверхность уже нового размера, а последний кадр приложения —
    // старого, и композитор его растягивает.
    //
    // Поэтому во время драга панели СТОЯТ НА МЕСТЕ, а поверх лежит оверлей с размытым снимком обоих
    // окон — тянется именно он. На отпускании один раз выставляем вес → ровно один surfaceChanged →
    // ровно один vd.resize. Оверлей убираем не сразу, а дав приложению отрисовать новый кадр, иначе
    // пользователь увидит тот самый растянутый кадр, ради сокрытия которого всё и затевалось.

    private boolean resizable = false;
    private int presetIdx = -1;
    private String presetId = "";
    private enum ResizeState { IDLE, DRAGGING, SETTLING }
    private ResizeState resizeState = ResizeState.IDLE;
    private boolean dragging = false; // совместимость с watchdog; true только в DRAGGING
    private boolean doubleTapConsumed = false;
    private float lastDragFraction = 0.5f;
    private int resizeGeneration = 0;
    private long resizeUntil = 0L;          // до этого момента надзиратель панелей молчит
    private android.widget.FrameLayout maskOverlay;
    private android.widget.ImageView maskLeft, maskRight;
    private View maskDivider, maskGrip;

    private static final float MIN_PANE_DP = 260f;   // уже этого приложения начинают падать честно
    private static final long  MASK_HOLD_MS = 450;   // сколько ждём новый кадр перед снятием маски

    /**
     * Доля левого окна для смещения пальца, с ограничением минимальной ЛОГИЧЕСКОЙ ширины обеих VD.
     * Пиксельный минимум у панелей разный: 260dp при 320dpi — это 520px, а не 260px физического
     * экрана. Старый clamp использовал density хоста и позволял загнать VD с пользовательским DPI
     * в телефонный/compat-размер, где Activity начинала letterbox'иться маленьким прямоугольником.
     */
    private float fractionForDx(float startFraction, float dx) {
        View panes = findViewById(R.id.splitPanes);
        View divider = findViewById(R.id.splitDivider);
        int usable = panes.getWidth() - divider.getWidth();
        if (usable <= 0) return startFraction;

        float leftMin = MIN_PANE_DP * effectiveDpi(left) / 160f / usable;
        float rightMin = MIN_PANE_DP * effectiveDpi(right) / 160f / usable;
        // Если выбранные DPI физически не оставляют 260dp обеим панелям, сохраняем их пропорцию,
        // но оставляем хотя бы 10% общего диапазона для движения делителя.
        float minSum = leftMin + rightMin;
        if (minSum > 0.90f) {
            float scale = 0.90f / minSum;
            leftMin *= scale;
            rightMin *= scale;
        }
        float f = startFraction + dx / usable;
        return Math.max(leftMin, Math.min(1f - rightMin, f));
    }

    /** Снять размытые снимки обеих панелей и показать оверлей вместо живых окон. */
    private void beginResize() {
        resizeGeneration++;                 // инвалидировать callbacks прошлого жеста
        resizeState = ResizeState.DRAGGING;
        dragging = true;
        resizeUntil = System.currentTimeMillis() + 60_000;  // надзиратель молчит, пока тянем
        ensureMaskOverlay();
        gripPressed(maskGrip, true);
        if (maskOverlay != null) maskOverlay.animate().cancel();
        if (maskLeft != null) maskLeft.setImageDrawable(null);
        if (maskRight != null) maskRight.setImageDrawable(null);
        captureBlurred(left,  maskLeft, resizeGeneration);
        captureBlurred(right, maskRight, resizeGeneration);
        maskOverlay.setVisibility(View.VISIBLE);
        maskOverlay.setAlpha(1f);
        previewFraction(currentFraction());
    }

    /**
     * Превью пропорции. Настоящие панели и поверхности НЕ трогаем (иначе полетят vd.resize) — двигаем
     * картинки оверлея И САМ ДЕЛИТЕЛЬ.
     *
     * Делитель обязателен: без него рукоятка остаётся там, куда её поставили неизменные веса, и ресайз
     * выглядит намертво залипшим — палец едет, а на экране ничего не происходит. Сдвигаем через
     * translationX: это не вызывает layout, а значит не будит surfaceChanged.
     */
    private void previewFraction(float f) {
        if (maskOverlay == null) return;
        View panes = findViewById(R.id.splitPanes);
        View divider = findViewById(R.id.splitDivider);
        int usable = panes.getWidth() - divider.getWidth();
        if (usable <= 0) return;
        int lw = Math.round(usable * f);
        // Preview обязан сам рисовать непрозрачный divider. Настоящий divider находится под этим
        // оверлеем; прозрачный зазор между maskLeft/maskRight показывал бы старые SurfaceView.
        setLp(maskLeft,  lw, 0);
        setLp(maskRight, usable - lw, lw + divider.getWidth());
        setLp(maskDivider, divider.getWidth(), lw);
    }

    private void setLp(View v, int w, int leftMargin) {
        if (v == null) return;
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) v.getLayoutParams();
        lp.width = w;
        lp.leftMargin = leftMargin;
        v.setLayoutParams(lp);
    }

    /**
     * Отпустили делитель: один раз меняем пропорцию, ждём, пока приложения отрисуются в новом
     * размере, и уводим маску кроссфейдом. Если приложение не пережило конфигурацию, его поднимет
     * общий watchdog после полноценного grace-периода.
     */
    private void endResize(final float f) {
        if (resizeState != ResizeState.DRAGGING) return;
        dragging = false;
        resizeState = ResizeState.SETTLING;
        gripPressed(maskGrip, false);
        final int generation = resizeGeneration;
        final long leftVersion = left.resizeVersion;
        final long rightVersion = right.resizeVersion;
        // Сдвиг делителя был визуальным (translationX) — снимаем его, дальше позицию задаёт вес.
        View divider = findViewById(R.id.splitDivider);
        if (divider != null) divider.setTranslationX(0f);
        applyFraction(f);                                    // ЕДИНСТВЕННАЯ смена веса за весь жест
        // Activity приложения может пересоздаваться после display configuration change. Даём тот же
        // grace, что при первоначальном запуске, и не пытаемся объявить её мёртвой через 630 ms.
        long now = System.currentTimeMillis();
        left.launchedAt = now;
        right.launchedAt = now;
        resizeUntil = now + WATCH_GRACE_MS;
        saveFraction(f);
        waitForSurfaceResize(generation, leftVersion, rightVersion, 0);
    }

    /** Ждём, пока оба SurfaceView действительно вызовут vd.resize; затем даём приложениям кадр и гасим маску. */
    private void waitForSurfaceResize(int generation, long leftBefore, long rightBefore, int attempt) {
        View root = findViewById(R.id.splitHostRoot);
        if (root == null) return;
        boolean resized = left.resizeVersion > leftBefore && right.resizeVersion > rightBefore;
        if (!resized && attempt < 20) {
            root.postDelayed(() -> waitForSurfaceResize(generation, leftBefore, rightBefore, attempt + 1), 50);
            return;
        }
        root.postDelayed(() -> finishResizeVisual(generation), MASK_HOLD_MS);
    }

    private void finishResizeVisual(int generation) {
        if (generation != resizeGeneration || resizeState != ResizeState.SETTLING) return;
        Runnable done = () -> {
            if (generation != resizeGeneration) return;
            if (maskOverlay != null) maskOverlay.setVisibility(View.GONE);
            resizeState = ResizeState.IDLE;
        };
        if (maskOverlay == null) { done.run(); return; }
        maskOverlay.animate().cancel();
        maskOverlay.animate().alpha(0f).setDuration(180).withEndAction(done).start();
    }

    /** CANCEL и двойной тап не должны менять пропорцию или сохранять случайную rawX. */
    private void cancelResizeGesture() {
        resizeGeneration++;
        dragging = false;
        resizeState = ResizeState.IDLE;
        gripPressed(maskGrip, false);
        View divider = findViewById(R.id.splitDivider);
        if (divider != null) divider.setTranslationX(0f);
        if (maskOverlay != null) {
            maskOverlay.animate().cancel();
            maskOverlay.setVisibility(View.GONE);
        }
        resizeUntil = 0L;
    }

    /** Вернуть выставленную пропорцию в пресет RestoreMode (единственный источник истины). */
    private void saveFraction(float f) {
        if (presetIdx < 0 && (presetId == null || presetId.isEmpty())) return;
        try {
            Intent i = new Intent("ru.big.town.restoremode.SPLIT_RATIO_SAVE");
            i.setClassName("ru.big.town.restoremode",
                    "ru.big.town.restoremode.SplitRatioSaveReceiver");
            i.putExtra("presetIdx", presetIdx);
            i.putExtra("presetId", presetId == null ? "" : presetId);
            i.putExtra("split", f);
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            sendBroadcast(i);
        } catch (Exception e) { Log.w(TAG, "saveFraction: " + e.getMessage()); }
    }

    /**
     * Оверлей поверх панелей: две картинки + собственный непрозрачный divider с grip.
     *
     * SurfaceView живёт в отдельном Surface-слое. Поэтому нельзя оставлять между картинками
     * прозрачную щель и рассчитывать, что перемещённый divider из нижней view-иерархии её закроет:
     * через щель композитор показывает старые буферы приложений. Оверлей полностью непрозрачен ещё
     * до завершения асинхронного PixelCopy, а divider рисуется в том же верхнем слое, что и preview.
     */
    private void ensureMaskOverlay() {
        if (maskOverlay != null) return;
        maskOverlay = new android.widget.FrameLayout(this);
        maskOverlay.setBackgroundColor(android.graphics.Color.BLACK);
        maskOverlay.setClickable(false);
        maskLeft  = newMaskImage();
        maskRight = newMaskImage();
        android.widget.FrameLayout divider = new android.widget.FrameLayout(this);
        divider.setBackgroundColor(android.graphics.Color.BLACK);
        divider.setLayoutParams(new android.widget.FrameLayout.LayoutParams(0,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        maskDivider = divider;

        maskGrip = new View(this);
        float density = getResources().getDisplayMetrics().density;
        android.widget.FrameLayout.LayoutParams gripLp = new android.widget.FrameLayout.LayoutParams(
                Math.round(7f * density), Math.round(80f * density));
        gripLp.gravity = android.view.Gravity.CENTER;
        maskGrip.setLayoutParams(gripLp);
        maskGrip.setBackgroundResource(R.drawable.split_handle_grip);
        divider.addView(maskGrip);

        maskOverlay.addView(maskLeft);
        maskOverlay.addView(maskRight);
        maskOverlay.addView(maskDivider); // последним: divider/grip всегда поверх снимков
        // Кладём в КОРЕНЬ (FrameLayout), а не в splitPanes: тот горизонтальный LinearLayout, и оверлей
        // стал бы в нём ещё одной колонкой, отобрав ширину у самих панелей.
        // SurfaceView здесь в обычном z-порядке (setZOrderOnTop не вызывается нигде), поэтому обычная
        // вьюха поверх него в иерархии перекрывает поверхность штатно.
        ((ViewGroup) findViewById(R.id.splitHostRoot)).addView(maskOverlay,
                new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        maskOverlay.setVisibility(View.GONE);
    }

    private android.widget.ImageView newMaskImage() {
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);   // тянется вместе с окном
        iv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(0,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        applyRoundedCorners(iv);
        return iv;
    }

    /**
     * Снимок панели → сразу в МАЛЕНЬКИЙ bitmap (PixelCopy сам масштабирует) → box-blur → в ImageView.
     *
     * Именно так уходит «шакальность» прошлой версии: там картинку просто уменьшали и растягивали
     * обратно, а голый даунскейл без размытия и без фильтрации при растяжении даёт блочные пиксели.
     * Здесь маленький кадр честно размывается (3 прохода бокса ≈ гаусс — на картинке в ~160px это
     * доли миллисекунды), а обратно тянется билинейно, так что видно мягкое пятно, а не «квадратики».
     *
     * RenderEffect.createBlurEffect тут недоступен — это API 31, а голова на API 30.
     */
    private void captureBlurred(final Pane pane, final android.widget.ImageView target, final int generation) {
        if (pane.view == null || pane.view.getWidth() <= 0) return;
        final int w = Math.max(16, pane.view.getWidth() / 8);
        final int h = Math.max(16, pane.view.getHeight() / 8);
        try {
            final Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            PixelCopy.request(pane.view, small, res -> {
                if (res != PixelCopy.SUCCESS) {
                    small.recycle();
                    Log.w(TAG, "PixelCopy " + pane.side + " = " + res);
                    return;
                }
                if (generation != resizeGeneration || resizeState != ResizeState.DRAGGING) {
                    small.recycle();
                    return;
                }
                boxBlur(small, 3);
                target.setImageBitmap(small);
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Exception e) {
            Log.w(TAG, "captureBlurred " + pane.side + ": " + e.getMessage());
        }
    }

    /** Box-blur по маленькому битмапу: несколько проходов приближают гаусс. Радиус в пикселях СНИМКА. */
    private static void boxBlur(Bitmap bmp, int passes) {
        final int w = bmp.getWidth(), h = bmp.getHeight();
        if (w < 3 || h < 3) return;
        final int r = 2;
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        int[] tmp = new int[w * h];
        for (int p = 0; p < passes; p++) {
            // Каждый проход размывает ПО СТРОКАМ и пишет результат транспонированным. Два таких
            // прохода подряд дают горизонталь + вертикаль, причём чтение всегда идёт последовательно
            // по памяти — отдельная «вертикальная» ветка не нужна.
            blurPass(px, tmp, w, h, r);
            blurPass(tmp, px, h, w, r);
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    /** Один проход усреднения по строке шириной w; результат кладётся транспонированным (h×w). */
    private static void blurPass(int[] src, int[] dst, int w, int h, int r) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = 0, rr = 0, gg = 0, bb = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = x + k;
                    if (xx < 0 || xx >= w) continue;
                    int c = src[y * w + xx];
                    a += (c >>> 24); rr += (c >> 16) & 0xFF; gg += (c >> 8) & 0xFF; bb += c & 0xFF;
                    n++;
                }
                dst[x * h + y] = ((a / n) << 24) | ((rr / n) << 16) | ((gg / n) << 8) | (bb / n);
            }
        }
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
    protected void onResume() {
        super.onResume();
        // Стережём панели только пока сплит на переднем плане: свёрнутый сплит приложения не показывает,
        // и «пропажа» там ожидаема — перезапускать нечего.
        watchHandler.removeCallbacks(watchTick);
        watchHandler.postDelayed(watchTick, WATCH_PERIOD_MS);
    }

    @Override
    protected void onPause() {
        watchHandler.removeCallbacks(watchTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        watchHandler.removeCallbacks(watchTick);
        cancelResizeGesture();
        releasePane(left);
        releasePane(right);
        super.onDestroy();
    }

    /**
     * Открыть одно приложение в VD-панели на физическом экране. Геометрия задаётся layout хоста,
     * DPI — самим VirtualDisplay, поэтому WindowManager system_server не требует hot-path hooks.
     */
    public static void launchSingle(android.content.Context ctx, String pkg, int dpi, int displayId) {
        if (ctx == null || pkg == null || pkg.isEmpty()) {
            Log.w(TAG, "launchSingle: пустой пакет — пропуск");
            return;
        }
        if (displayId != 0 && displayId != 1) displayId = 0;
        if (ctx.getPackageManager().getLaunchIntentForPackage(pkg) == null) {
            Log.w(TAG, "launchSingle: нет launch intent для " + pkg);
            return;
        }
        try {
            Intent i = new Intent(ctx, SplitHostActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            i.putExtra(EXTRA_LEFT, pkg);
            i.putExtra(EXTRA_RIGHT, "");
            i.putExtra(EXTRA_RATIO, 1);
            i.putExtra(EXTRA_LEFT_DPI, Math.max(0, dpi));
            i.putExtra(EXTRA_RIGHT_DPI, 0);
            DockLaunchGuard.arm(ctx, displayId, "ru.big.town.anative");
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);
            ctx.startActivity(i, options.toBundle());
            Log.i(TAG, "launchSingle host started pkg=" + pkg + " dpi=" + dpi
                    + " display=" + displayId);
        } catch (Exception e) {
            Log.e(TAG, "launchSingle failed: " + e.getMessage());
        }
    }

    /** Запустить сплит на VirtualDisplay из статического контекста (напр. из {@link SetModesReceiverDynamic}
     *  по долгому нажатию на слот дока). Дублирует {@code SetModesService.launchVirtualSplit}: включает
     *  freeform-настройки (resizable) и стартует хост с extras. Пустой left/right → no-op. */
    public static void launchSplit(android.content.Context ctx, String left, String right, int ratio, int leftDpi, int rightDpi) {
        launchSplit(ctx, left, right, ratio, leftDpi, rightDpi, false, 0f, -1, "");
    }

    /** @param resizable разрешить менять пропорцию перетаскиванием делителя
     *  @param split     стартовая доля левого окна 0..1 (0 = вычислить из ratio)
     *  @param presetIdx индекс пресета в RestoreMode — по нему туда вернётся новая пропорция */
    public static void launchSplit(android.content.Context ctx, String left, String right, int ratio,
                                   int leftDpi, int rightDpi, boolean resizable, float split, int presetIdx) {
        launchSplit(ctx, left, right, ratio, leftDpi, rightDpi, resizable, split, presetIdx, "");
    }

    public static void launchSplit(android.content.Context ctx, String left, String right, int ratio,
                                   int leftDpi, int rightDpi, boolean resizable, float split,
                                   int presetIdx, String presetId) {
        if (left == null || left.isEmpty() || right == null || right.isEmpty()) {
            Log.w(TAG, "launchSplit: пустой пакет — пропуск");
            return;
        }
        try {
            android.provider.Settings.Global.putInt(ctx.getContentResolver(), "enable_freeform_support", 1);
            android.provider.Settings.Global.putInt(ctx.getContentResolver(), "force_resizable_activities", 1);
        } catch (Exception e) {
            Log.w(TAG, "launchSplit freeform settings: " + e.getMessage());
        }
        try {
            Intent i = new Intent(ctx, SplitHostActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            i.putExtra(EXTRA_LEFT, left);
            i.putExtra(EXTRA_RIGHT, right);
            i.putExtra(EXTRA_RATIO, ratio);
            i.putExtra(EXTRA_LEFT_DPI, leftDpi);
            i.putExtra(EXTRA_RIGHT_DPI, rightDpi);
            i.putExtra(EXTRA_RESIZABLE, resizable);
            i.putExtra(EXTRA_SPLIT, split);
            i.putExtra(EXTRA_PRESET_IDX, presetIdx);
            i.putExtra(EXTRA_PRESET_ID, presetId);
            DockLaunchGuard.arm(ctx, 0, "ru.big.town.anative");
            ctx.startActivity(i);
            Log.i(TAG, "launchSplit host started " + left + "/" + right);
        } catch (Exception e) {
            Log.e(TAG, "launchSplit failed: " + e.getMessage());
        }
    }

}
