package ru.big.town.restoremode;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;

import ru.big.town.common.TaskManagerProtocol;

/**
 * «Диспетчер задач»: карточки запущенных сторонних приложений (иконка, имя, «Закрыть») и карточка
 * «Закрыть все приложения». В ряд помещается {@link TaskManagerCards#VISIBLE_SLOTS} слотов, остальное —
 * горизонтальным скроллом; без запущенных приложений по центру показывается текст пустого состояния.
 *
 * <p>В список входят и приложения, запущенные внутри виджетов: они помечаются бейджем «в виджете», а
 * тап по ним открывает приложение на физическом экране (задача живёт на VirtualDisplay виджета).</p>
 *
 * <p>Список задач и действия по ним выполняет Native (priv-app): у RestoreMode нет REAL_GET_TASKS, а
 * закрытие задачи требует FORCE_STOP_PACKAGES. Активность биндится к {@code SetModesService} сама —
 * её открывает и долгий тап по нижней кнопке родного дока, когда главный экран VoyahTune не запущен.</p>
 */
public class TaskManagerActivity extends AppCompatActivity {
    private static final String TAG = "TaskManager";
    private static final String NATIVE_PKG = "ru.big.town.anative";
    private static final String NATIVE_SERVICE = "ru.big.town.anative.SetModesService";
    /** forceStopPackage в AMS асинхронный: без страховочного повтора список может остаться старым. */
    private static final long REFRESH_FALLBACK_MS = 1_500L;
    /** Полоса родного дока головы (как в MainActivity): контент отступаем, док остаётся видимым. */
    private static final float NATIVE_DOCK_DP = 145f;
    private static final int GAP_DP = 8;

    private View root;
    private LinearLayout cardsRow;
    private HorizontalScrollView scroll;
    private TextView empty;

    private Messenger nativeService;
    private boolean bound;
    private boolean listReceived;
    private int cardWidthPx, gapPx, lastWidth;
    private final List<String> cardPackages = new ArrayList<>();
    private final List<String> cardLabels = new ArrayList<>();
    /** Параллельно cardPackages: приложение зафиксировано и не закроется кнопкой «Закрыть все». */
    private final List<Boolean> cardPinned = new ArrayList<>();
    /** Параллельно cardPackages: приложение запущено внутри виджета (тап открывает его на экране). */
    private final List<Boolean> cardWidget = new ArrayList<>();
    // LayoutInflater берётся в onCreate: до attach() у Activity нет базового контекста.
    private LayoutInflater inflater;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refreshFallback = this::requestList;

    private final Messenger client = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        if (msg.what == TaskManagerProtocol.LIST) {
            onTaskList(msg.getData());
            return true;
        }
        return false;
    }));

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            nativeService = new Messenger(service);
            bound = true;
            requestList();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            nativeService = null;
            bound = false;
            showEmpty("Нет связи с сервисом автомобиля");
        }
        @Override public void onBindingDied(ComponentName name) { nativeService = null; bound = false; }
        @Override public void onNullBinding(ComponentName name) { nativeService = null; bound = false; }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Как в остальных экранах VoyahTune: без edge-to-edge система сама поглощает insets и до
        // контента они не доходят (родной док в insets не приходит вообще).
        EdgeToEdge.enable(this);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_task_manager);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        root = findViewById(R.id.taskManagerRoot);
        cardsRow = findViewById(R.id.taskManagerCards);
        scroll = findViewById(R.id.taskManagerScroll);
        empty = findViewById(R.id.taskManagerEmpty);
        inflater = LayoutInflater.from(this);
        findViewById(R.id.taskManagerClose).setOnClickListener(v -> finish());

        float density = getResources().getDisplayMetrics().density;
        gapPx = Math.round(GAP_DP * density);
        final int dockInset = Math.round(NATIVE_DOCK_DP * density);

        // Полосу родного дока резервируем сразу: она висит поверх окна и в system bar insets
        // НЕ приходит, а без этого левый край экрана уезжает под док.
        root.setPadding(dockInset, 0, 0, 0);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int top = sb.top;
            if (top == 0) {
                // На голове статус-бар не сообщает высоту в insets — берём системный status_bar_height.
                int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) top = getResources().getDimensionPixelSize(id);
            }
            v.setPadding(dockInset + sb.left, top, sb.right, 0);
            renderCards();
            return insets;
        });
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) != lastWidth) {
                lastWidth = r - l;
                renderCards();
            }
        });

        boolean requested;
        try {
            requested = bindService(new Intent().setClassName(NATIVE_PKG, NATIVE_SERVICE),
                    connection, BIND_AUTO_CREATE);
        } catch (RuntimeException e) {
            Log.w(TAG, "bindService: " + e.getMessage());
            requested = false;
        }
        if (!requested) showEmpty("Сервис автомобиля недоступен");
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(refreshFallback);
        if (bound) {
            try {
                unbindService(connection);
            } catch (RuntimeException e) {
                Log.w(TAG, "unbindService: " + e.getMessage());
            }
            bound = false;
        }
        super.onDestroy();
    }

    /** Запросить у Native актуальный список запущенных сторонних задач. */
    private void requestList() {
        if (nativeService == null) return;
        try {
            Message msg = Message.obtain(null, TaskManagerProtocol.REQUEST);
            msg.replyTo = client;
            nativeService.send(msg);
        } catch (RemoteException e) {
            Log.w(TAG, "requestList: " + e.getMessage());
        }
    }

    private void onTaskList(Bundle data) {
        ui.removeCallbacks(refreshFallback);
        listReceived = true;
        ArrayList<String> packages = (data == null) ? null
                : data.getStringArrayList(TaskManagerProtocol.PACKAGES);
        ArrayList<String> labels = (data == null) ? null
                : data.getStringArrayList(TaskManagerProtocol.LABELS);
        boolean[] pinned = (data == null) ? null
                : data.getBooleanArray(TaskManagerProtocol.PINNED);
        boolean[] widgets = (data == null) ? null
                : data.getBooleanArray(TaskManagerProtocol.WIDGETS);
        cardPackages.clear();
        cardLabels.clear();
        cardPinned.clear();
        cardWidget.clear();
        for (int i = 0; packages != null && i < packages.size(); i++) {
            String pkg = packages.get(i);
            if (pkg == null || pkg.isEmpty()) continue;
            cardPackages.add(pkg);
            String label = (labels != null && i < labels.size()) ? labels.get(i) : null;
            cardLabels.add((label == null || label.isEmpty()) ? pkg : label);
            cardPinned.add(pinned != null && i < pinned.length && pinned[i]);
            cardWidget.add(widgets != null && i < widgets.length && widgets[i]);
        }
        Log.i(TAG, "task list: " + cardPackages.size() + " apps");
        renderCards();
    }

    /** Перерисовать ряд карточек текущим списком. Вызывается после инсетов, layout и каждого ответа. */
    private void renderCards() {
        if (cardsRow == null) return;
        int available = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        if (available <= 0) return;   // раскладки ещё нет — перерисуем по addOnLayoutChangeListener
        cardWidthPx = TaskManagerCards.cardWidth(available, gapPx);
        if (cardWidthPx <= 0) return;

        cardsRow.removeAllViews();
        if (cardPackages.isEmpty()) {
            // До первого ответа Native списка ещё нет — пустой экран не показываем.
            showEmpty(listReceived ? "Нет запущенных приложений" : "Загрузка…");
            return;
        }
        scroll.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        for (int i = 0; i < cardPackages.size(); i++) {
            addAppCard(cardPackages.get(i), cardLabels.get(i), cardPinned.get(i), cardWidget.get(i));
        }
        if (TaskManagerCards.hasCloseAllCard(cardPackages)) addCloseAllCard();
    }

    private void addAppCard(String pkg, String label, boolean pinned, boolean widget) {
        final View card = inflater.inflate(R.layout.item_task_card, cardsRow, false);
        ImageView icon = card.findViewById(R.id.taskCardIcon);
        TextView name = card.findViewById(R.id.taskCardLabel);
        name.setText(label);
        icon.setImageDrawable(iconFor(pkg));
        // Приложение живёт внутри виджета: тап переносит его на физический экран, поэтому бейдж
        // подсказывает, откуда взялась карточка.
        card.findViewById(R.id.taskCardWidget).setVisibility(widget ? View.VISIBLE : View.GONE);
        // Тап по иконке и имени выводит задачу приложения на передний план, закрытие — отдельной кнопкой.
        View.OnClickListener switchAction = v -> sendSwitch(pkg);
        icon.setOnClickListener(switchAction);
        name.setOnClickListener(switchAction);
        card.findViewById(R.id.taskCardClose).setOnClickListener(v -> sendClose(pkg));
        // Булавка защищает приложение только от «Закрыть все»; обычная «Закрыть» работает всегда.
        ImageButton pin = card.findViewById(R.id.taskCardPin);
        setPinState(pin, pinned);
        pin.setOnClickListener(v -> sendPin(pkg, !pinned));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardWidthPx, -1);
        lp.rightMargin = gapPx;
        cardsRow.addView(card, lp);
    }

    /** Состояние булавки: зафиксированная подсвечена акцентом, свободная — приглушена. */
    private void setPinState(ImageButton pin, boolean pinned) {
        if (pin == null) return;
        pin.setImageTintList(ColorStateList.valueOf(pinned ? 0xff2f7fd0 : 0xffffffff));
        pin.setAlpha(pinned ? 1f : 0.45f);
        pin.setContentDescription(pinned
                ? "Снять фиксацию: приложение защищено от «Закрыть все»"
                : "Зафиксировать: приложение не закроется кнопкой «Закрыть все»");
    }

    private void addCloseAllCard() {
        final View card = inflater.inflate(R.layout.item_task_close_all, cardsRow, false);
        card.findViewById(R.id.taskCloseAll).setOnClickListener(v -> {
            send(TaskManagerProtocol.CLOSE_ALL, null);
            // «Закрыть все» завершает диспетчер: возвращаемся туда, откуда его открыли.
            finish();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardWidthPx, -1);
        lp.rightMargin = gapPx;
        cardsRow.addView(card, lp);
    }

    private void sendClose(String pkg) {
        if (!send(TaskManagerProtocol.CLOSE, pkg)) {
            showEmpty("Нет связи с сервисом автомобиля");
            return;
        }
        // Native ответит свежим списком сам; повтор — страховка на случай потерянного ответа.
        ui.removeCallbacks(refreshFallback);
        ui.postDelayed(refreshFallback, REFRESH_FALLBACK_MS);
    }

    private void sendSwitch(String pkg) {
        if (!send(TaskManagerProtocol.SWITCH, pkg)) showEmpty("Нет связи с сервисом автомобиля");
    }

    /** Фиксация/снятие фиксации: Native хранит список и отвечает свежим LIST (перерисовка карточек). */
    private void sendPin(String pkg, boolean pinned) {
        if (!send(TaskManagerProtocol.PIN, pkg, pinned)) {
            showEmpty("Нет связи с сервисом автомобиля");
        }
    }

    private boolean send(int what, String pkg) {
        return send(what, pkg, null);
    }

    private boolean send(int what, String pkg, Boolean pinned) {
        if (nativeService == null) return false;
        try {
            Message msg = Message.obtain(null, what);
            if (pkg != null || pinned != null) {
                Bundle data = new Bundle();
                if (pkg != null) data.putString(TaskManagerProtocol.PACKAGE, pkg);
                if (pinned != null) data.putBoolean(TaskManagerProtocol.PINNED, pinned);
                msg.setData(data);
            }
            msg.replyTo = client;
            nativeService.send(msg);
            return true;
        } catch (RemoteException e) {
            Log.w(TAG, "send " + what + ": " + e.getMessage());
            return false;
        }
    }

    private Drawable iconFor(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            return pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return pm.getDefaultActivityIcon();
        }
    }

    private void showEmpty(String text) {
        if (empty == null) return;
        cardsRow.removeAllViews();
        scroll.setVisibility(View.GONE);
        empty.setVisibility(View.VISIBLE);
        empty.setText(text);
    }
}
