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

    // ВРЕМЕННО: ресайз делителем отключён (порча текстуры/рассинхрон приложений при живом ресайзе VD).
    // Делитель — только визуальный + двойной тап меняет окна местами.
    private final Pane left  = new Pane("L");
    private final Pane right = new Pane("R");

    // Ссылка на активный сплит-хост — чтобы Native мог закрыть сплит, когда пользователь открывает
    // приложение из дока во freeform (иначе приложение-панель «уехало» бы с VD с глитчем). См. closeActiveSplit.
    private static volatile SplitHostActivity sCurrent;

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
        sCurrent = this;   // этот сплит теперь активный (для закрытия из Native при OPEN_FREEFORM)
        left.dpi   = in.getIntExtra(EXTRA_LEFT_DPI, 0);
        right.dpi  = in.getIntExtra(EXTRA_RIGHT_DPI, 0);
        int ratio  = in.getIntExtra(EXTRA_RATIO, 1);

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

        Log.i(TAG, "onCreate single=" + single + " left=" + left.pkg + " right=" + right.pkg
                + " ratio=" + ratio + " lDpi=" + left.dpi + " rDpi=" + right.dpi
                + " defaultDpi=" + defaultDpi);
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

        // Двойной тап по handle-бару — поменять окна местами. Драг-ресайз ВРЕМЕННО отключён.
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                swapApps();
                return true;
            }
        });
        divider.setOnTouchListener((v, e) -> { gd.onTouchEvent(e); return true; });
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
        if (sCurrent == this) sCurrent = null;
        releasePane(left);
        releasePane(right);
        super.onDestroy();
    }

    /** Запустить сплит на VirtualDisplay из статического контекста (напр. из {@link SetModesReceiverDynamic}
     *  по долгому нажатию на слот дока). Дублирует {@code SetModesService.launchVirtualSplit}: включает
     *  freeform-настройки (resizable) и стартует хост с extras. Пустой left/right → no-op. */
    public static void launchSplit(android.content.Context ctx, String left, String right, int ratio, int leftDpi, int rightDpi) {
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
            ctx.startActivity(i);
            Log.i(TAG, "launchSplit host started " + left + "/" + right);
        } catch (Exception e) {
            Log.e(TAG, "launchSplit failed: " + e.getMessage());
        }
    }

    /** Закрыть активный сплит, если есть: завершаем ЗАДАЧИ обеих панелей (removeTask, без убийства
     *  процессов → музыка не глохнет), чтобы приложения не остались «застрявшими» на VD и открылись
     *  заново ЧИСТО там, где их запросили из дока, + finish хоста. Зовёт Native при OPEN_FREEFORM
     *  (пользователь открыл приложение из дока во freeform поверх сплита).
     *  @return true, если сплит был активен (тогда запуск во freeform стоит отложить на teardown). */
    static boolean closeActiveSplit() {
        final SplitHostActivity a = sCurrent;
        if (a == null) return false;
        sCurrent = null;
        try {
            if (a.left.pkg  != null && !a.left.pkg.isEmpty())  a.finishTasksForPackage(a.left.pkg);
            if (a.right.pkg != null && !a.right.pkg.isEmpty()) a.finishTasksForPackage(a.right.pkg);
            a.runOnUiThread(a::finish);
            Log.i(TAG, "closeActiveSplit: сплит закрыт (removeTask панелей + finish)");
        } catch (Exception e) { Log.w(TAG, "closeActiveSplit: " + e.getMessage()); }
        return true;
    }
}
