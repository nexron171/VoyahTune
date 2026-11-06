package ru.big.town.restoremode.dashboard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import ru.big.town.common.CarPlayTarget;
import ru.big.town.common.CarPlayWidgetProtocol;
import ru.big.town.common.TripProtocol;
import ru.big.town.restoremode.AdvanceActivity;
import ru.big.town.restoremode.AdvanceActivityStarButton;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.TaskManagerActivity;
import ru.big.town.restoremode.TripHistoryActivity;
import ru.big.town.restoremode.VoiceActivity;
import ru.big.town.restoremode.appearance.AppearanceSettings;
import ru.big.town.restoremode.appearance.DashboardAppearance;
import ru.big.town.restoremode.apps.AppDpiStore;
import ru.big.town.restoremode.apps.split.SplitStore;
import ru.big.town.restoremode.carplay.CarPlayWidgetPlaceholder;
import ru.big.town.restoremode.dashboard.tiles.DashboardEditGrid;
import ru.big.town.restoremode.dashboard.tiles.TileDragController;
import ru.big.town.restoremode.dashboard.tiles.TileGridPacking;
import ru.big.town.restoremode.dashboard.tiles.TileOrderStore;
import ru.big.town.restoremode.dashboard.tiles.TileScrollSnap;
import ru.big.town.restoremode.dashboard.tiles.TileSizeStore;
import ru.big.town.restoremode.integration.GlobalVars;
import ru.big.town.restoremode.integration.config.SplitConfigSync;
import ru.big.town.restoremode.scenarios.ScenarioQuickList;
import ru.big.town.restoremode.scenarios.ScenarioStore;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.trips.location.TripLocationPermission;
import ru.big.town.restoremode.voice.commands.VoiceCommands;
import ru.big.town.restoremode.widgets.apps.AppShortcutStore;
import ru.big.town.restoremode.widgets.apps.AppWidgetSessionStore;
import ru.big.town.restoremode.widgets.apps.AppWidgetStore;
import ru.big.town.restoremode.widgets.brightness.BrightnessWidgetDashboard;
import ru.big.town.restoremode.widgets.brightness.BrightnessWidgetLayout;
import ru.big.town.restoremode.widgets.climate.CabinTemperatureWidgetDashboard;
import ru.big.town.restoremode.widgets.climate.CabinTemperatureWidgetLayout;
import ru.big.town.restoremode.widgets.climate.RecirculationWidgetDashboard;
import ru.big.town.restoremode.widgets.climate.RecirculationWidgetLayout;
import ru.big.town.restoremode.widgets.clock.ClockWidgetView;
import ru.big.town.restoremode.widgets.dials.DialWidgetStore;
import ru.big.town.restoremode.widgets.energy.CarAppearance;
import ru.big.town.restoremode.widgets.energy.EnergyWidgetPreferences;
import ru.big.town.restoremode.widgets.energy.TripWidgetView;
import ru.big.town.restoremode.widgets.energy.VehicleWidgetView;
import ru.big.town.restoremode.widgets.energy.VehicleWidgets;
import ru.big.town.restoremode.widgets.fuel.FuelFlapWidgetDashboard;
import ru.big.town.restoremode.widgets.fuel.FuelFlapWidgetLayout;
import ru.big.town.restoremode.widgets.headlights.HeadlightWidgetDashboard;
import ru.big.town.restoremode.widgets.headlights.HeadlightWidgetLayout;
import ru.big.town.restoremode.widgets.links.LinkShortcutLauncher;
import ru.big.town.restoremode.widgets.links.LinkShortcutStore;
import ru.big.town.restoremode.widgets.media.MediaWidgetDashboard;
import ru.big.town.restoremode.widgets.media.MediaWidgetLayout;
import ru.big.town.restoremode.widgets.radio.RadioWidgetDashboard;
import ru.big.town.restoremode.widgets.radio.RadioWidgetLayout;
import ru.big.town.restoremode.widgets.suspension.SuspensionWidgetView;
import ru.big.town.restoremode.widgets.system.SystemWidgetDashboard;
import ru.big.town.restoremode.widgets.system.SystemWidgetLayout;

import java.lang.ref.WeakReference;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DashboardActivity extends AppCompatActivity {
    private String driveMode = "INDIVIDUAL";
    private String energy = "SREV";
    private String recycle = "LOW";
    private String customCommand = "";
    private int customCommandCount = 1;

    private SharedPreferences sharedPreferences;
    private SharedPreferences settingsPreferences;
    private DashboardAppearance appearance;
    private final SharedPreferences.OnSharedPreferenceChangeListener appearancePreferences =
            (preferences, key) -> {
                if (AppearanceSettings.TILE_SPACING.equals(key)) {
                    updateAppearanceSpacing();
                }
            };
    private boolean carPlayFeatureReceiverRegistered;
    private final BroadcastReceiver carPlayFeatureReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    carPlayWidgetGeneration++;
                    TileOrderStore.sync(
                            sharedPreferences,
                            settingsPreferences,
                            getPackageManager(),
                            dashboardProfile.isPassenger());
                    renderSplitTiles();
                }
            };
    private DashboardProfile dashboardProfile = DashboardProfile.driver();
    private int dashboardDisplayId;
    static final int MSG_RESULT = 4;
    static final int MSG_AUTO_LIGHT_ENABLE = 10;
    static final int MSG_AUTO_LIGHT_DISABLE = 11;
    static final int MSG_LEAVE_CAR = 20;
    static final int MSG_APPLY_PEDESTRIAN = 21;
    static final int MSG_WASH_MODE = 23;
    static final int MSG_SPLIT_LAUNCH_VD = 34; // single → physical WM-clamped task; pair → VD split
    static final int MSG_EMBEDDED_TRANSFER = 38; // перенос/обмен запущенными экземплярами виджетов
    static final int MSG_APPLY_SUSPENSION_MAINTENANCE = 37;
    static final int MSG_APPLY_FORCED_EV = 35; // форсированный электрорежим (arg1: 1=вкл)
    static final int REQUEST_CODE = 1;
    static final String ACTION_REQUEST_POWER_HOLD_STATUS =
            "ru.big.town.anative.REQUEST_POWER_HOLD_STATUS";
    static final String ACTION_POWER_HOLD_STATUS_UPDATE =
            "ru.big.town.anative.POWER_HOLD_STATUS_UPDATE";
    // Native уводит задачу приложения с виртуального дисплея виджета при полноэкранном запуске.
    static final String ACTION_EMBEDDED_TASK_LEFT = "ru.big.town.anative.EMBEDDED_TASK_LEFT";
    static final String EXTRA_EMBEDDED_TASK_PKG = "pkg";
    static final String EXTRA_EMBEDDED_TASK_CLOSED = "closed";
    private static final String BIND_SET_MODES_PERMISSION =
            "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";
    private static final int POWER_HOLD_UNKNOWN = 0;
    private static final int POWER_HOLD_INACTIVE = 1;
    private static final int POWER_HOLD_ACTIVATING = 2;
    private static final int POWER_HOLD_ACTIVE = 3;
    private static final int POWER_HOLD_FAILED = 4;
    private static final int POWER_HOLD_REQUESTED = 5;
    private static final int POWER_HOLD_ARMED = 6;
    private static final int POWER_HOLD_EXIT_LOW_BATTERY = 1;
    private static final int POWER_HOLD_EXIT_TIME_UP = 2;
    private static final int POWER_HOLD_REQUEST_ACCEPTED = 1;
    private static final int POWER_HOLD_REQUEST_NOT_IN_PARK = 2;
    private static final int POWER_HOLD_REQUEST_LOW_BATTERY = 3;
    private static final int POWER_HOLD_REQUEST_STATE_UNAVAILABLE = 4;
    private static final int POWER_HOLD_REQUEST_TRANSPORT_FAILURE = 5;
    private static final int POWER_HOLD_REQUEST_ARMED = 6;
    private static final int POWER_HOLD_REQUEST_ACTIVE = 7;
    private static final int POWER_HOLD_REQUEST_RESULT_UNKNOWN = 8;
    private Intent resultIntent = null;
    private Intent resultIntentStarButton = null;
    private SharedPreferences.Editor editor = null;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    static final String TAG = "$$$ MainActivityRestoreMode $$$";

    // -------- Виджет статистики поездки --------
    public static final String ACTION_TRIP_UPDATE = "ru.big.town.anative.TRIP_UPDATE";
    public static final String ACTION_REQUEST_TRIP_UPDATE =
            "ru.big.town.anative.REQUEST_TRIP_UPDATE";
    static final String ACTION_TRIP_STOP = TripProtocol.STOP;
    private static final DateTimeFormatter TRIP_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy, EEEE", Locale.forLanguageTag("ru"));
    private TextView tripDate, tripTimer, tripStatus;
    // Карточки главного экрана, скрываемые настройками раздела «Главный экран»
    private View tripCard,
            cardPowerHold,
            cardWashMode,
            cardAutoLight,
            cardPedestrian,
            cardForcedEv,
            cardSuspensionMaintenance;
    // Native-виджеты
    private View launchAppsWidget;
    private boolean tripActive = false, tripInDrive = false, tripWaitingForMovement = false;
    private View tripStop;
    private long tripStartWall;
    private TextView taskManagerCount;
    private long tripAccumMs = 0L, tripDriveStartElapsed = 0L;
    private String lastTripsJson = "[]"; // снимок лога для экрана истории

    // Тоггл-карточки на главном (автосвет / звук пешеходов): нейтральные, состояние — капсула-тег
    private TextView autoLightBadge, pedestrianBadge, forcedEvBadge, suspensionMaintenanceBadge;
    private TextView powerHoldBadge;
    private boolean autoLightOn, pedestrianOn, forcedEvOn, suspensionMaintenanceOn;

    // -------- Виджет «Прогрев батареи» --------
    static final String ACTION_BATTERY_HEAT_UPDATE = "ru.big.town.anative.BATTERY_HEAT_UPDATE";
    static final String ACTION_REQUEST_BATTERY_HEAT = "ru.big.town.anative.REQUEST_BATTERY_HEAT";
    static final String ACTION_BATTERY_HEAT_ACTIVATE = "ru.big.town.anative.BATTERY_HEAT_ACTIVATE";
    // Запуск приложения из плитки «Быстрый запуск» на выбранном физическом дисплее (0/1).
    static final String ACTION_OPEN_ON_DISPLAY = "ru.big.town.anative.OPEN_ON_DISPLAY";
    static final String ACTION_OPEN_PASSENGER_DASHBOARD =
            "ru.big.town.anative.OPEN_PASSENGER_DASHBOARD";
    private static final int BH_UNKNOWN = Integer.MIN_VALUE;
    private static final int BH_TEMP_INVALID = -9999;
    private static final int BH_PLATFORM_H97X = 1;
    private static final int BH_PLATFORM_H97C = 2;
    private static final int BH_PHASE_IDLE = 0;
    private static final int BH_PHASE_SENDING = 1;
    private static final int BH_PHASE_AWAITING_CONFIRMATION = 2;
    private static final int BH_PHASE_ACTIVE = 3;
    private static final int BH_PHASE_BLOCKED = 4;
    private static final int BH_PHASE_ENABLED = 5;
    private View cardBatteryHeat;
    private ImageView batteryHeatIcon;
    private TextView batteryHeatState, batteryHeatTemp, batteryHeatStatus, batteryHeatFail;
    private Button buttonBatteryHeat;
    // Палитра состояний прогрева (цвет = состояние термоменеджмента ВВБ)
    private static final int BH_COLOR_COLD =
            0xFF3D7FD0; // синий — на улице холодно, прогрев уместен
    private static final int BH_COLOR_HEATING = 0xFF35B06A; // зелёный — идёт прогрев
    private static final int BH_COLOR_NORMAL = 0xFF6B7280; // серый — норма / нет данных
    private static final int BH_COLOR_WARN = 0xFFD0A92F; // жёлтый — внимание (прогрев невозможен)
    private static final int BH_COLOR_FAULT = 0xFFD04A4A; // красный — неисправность ВВБ

    // Сплиты — прокручиваемая сетка плиток по пресетам из «Разделение экрана»
    private DashboardEditGrid splitTilesGrid;
    private Button finishEditing;
    private OnBackPressedCallback editBack;
    // Верхний инсет контента — тот же, что уходит в padding mainContent (нижний всегда 0).
    private int contentInsetTop;
    // «Полноэкранная сетка»: верхний ряд плиток растягивается до границы экрана.
    private boolean fullscreenGrid;
    // Настройка «сколько левых колонок верхнего ряда растягивается» и её границы.
    private static final int FULLSCREEN_GRID_COLUMNS_DEFAULT = 8;
    private static final int FULLSCREEN_GRID_COLUMNS_MAX = 12;
    // Собственные вертикальные отступы сетки из разметки — «Полноэкранная сетка» снимает верхний.
    private int gridPaddingTop;
    private int gridPaddingBottom;

    // -------- Автовыравнивание прокрутки сетки после остановки --------
    // Пауза покоя и длительность плавного выравнивания; настройка выбирает только режим.
    // «По целой странице» паузу не ждёт и стартует сразу после отпускания.
    private static final long GRID_SNAP_IDLE_MS = 100L;
    private static final long GRID_SNAP_DURATION_MS = 500L;
    private static final int GRID_COLUMNS = 12;
    private static final String PREF_GRID_SNAP_MODE = "gridSnapMode";
    private HorizontalScrollView contentScroll;
    // Шаг одной колонки в пикселях: считается в renderSplitTiles вместе с размерами плиток.
    private int tilePitchPx;
    private ValueAnimator gridSnapAnimator;
    private boolean gridSnapping;
    // Прокрутка в начале текущего жеста: режим «По целой странице» от неё отсчитывает переход.
    private int gridSnapAnchorPx = -1;
    // Ждёт ли паузу покоя уже начатая прокрутка и последняя применённая анимацией прокрутка.
    private boolean gridSnapPending;
    private int gridSnapAppliedPx = -1;
    private final Runnable gridSnapIdle = this::runGridSnap;
    // Both physical screens run in one process. A cross-screen exchange needs the other screen's
    // TextureView surface and running package, so live dashboards register themselves here.
    private static final List<WeakReference<DashboardActivity>> liveDashboards = new ArrayList<>();
    private final Map<String, TextureView> embeddedWidgetSurfaces = new HashMap<>();
    private final Map<String, Surface> embeddedWidgetOutputs = new HashMap<>();
    // Пакет и DPI запущенного в виджете приложения. Хранятся отдельно от настроек виджета,
    // потому что экземпляр можно перенести в другой виджет, а после обмена — обновляются.
    private final Map<String, String> embeddedWidgetPackages = new HashMap<>();
    private final Map<String, Integer> embeddedWidgetDpi = new HashMap<>();
    // Виджеты, экран которых снимается для переноса: их дисплей освобождать не нужно.
    private final Set<String> embeddedSuppressRelease = new HashSet<>();
    // View плиток app_widget по id записи: нужен, чтобы запустить приложение в первом виджете.
    private final Map<String, View> appWidgetTileViews = new HashMap<>();
    private long carPlayWidgetGeneration;
    private final Messenger carPlayWidgetClient = new Messenger(new IncomingHandler());
    // Replies from Native must return to this screen only; GlobalVars holds the last dashboard,
    // which is wrong when both physical screens are open.
    private final Messenger clientMessenger = new Messenger(new IncomingHandler());

    // -------- Drag-and-drop для переупорядочивания плиток --------
    private TileDragController tileDragController;
    private final java.util.List<VehicleWidgetView> energyWidgetViews = new java.util.ArrayList<>();
    private Bundle energyWidgetState = new Bundle();
    private final SystemWidgetDashboard systemWidgets =
            new SystemWidgetDashboard(this, this::showSnack);
    private final MediaWidgetDashboard mediaWidgets =
            new MediaWidgetDashboard(this, this::showSnack);
    private boolean tripTimerReceived;
    private final Messenger energyWidgetClient =
            new Messenger(
                    new Handler(
                            android.os.Looper.getMainLooper(),
                            msg -> {
                                if (msg.what != ru.big.town.common.EnergyWidgetProtocol.STATE) {
                                    return false;
                                }
                                Bundle data = msg.getData();
                                if (data.getInt(ru.big.town.common.EnergyWidgetProtocol.SCHEMA)
                                        != ru.big.town.common.EnergyWidgetProtocol.VERSION) {
                                    return true;
                                }
                                energyWidgetState = new Bundle(data);
                                for (VehicleWidgetView view : energyWidgetViews) {
                                    view.update(energyWidgetState);
                                }
                                return true;
                            }));

    private void watchEnergyWidgets() {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            return;
        }
        boolean show = false;
        for (String id : VehicleWidgets.IDS) {
            show |= sharedPreferences.getBoolean("show_" + id, false);
        }
        try {
            EnergyWidgetPreferences.sync(GlobalVars.serviceMessenger, settingsPreferences);
            Message msg =
                    Message.obtain(
                            null,
                            show && suspensionScreenResumed
                                    ? ru.big.town.common.EnergyWidgetProtocol.WATCH
                                    : ru.big.town.common.EnergyWidgetProtocol.UNWATCH);
            msg.replyTo = energyWidgetClient;
            GlobalVars.serviceMessenger.send(msg);
        } catch (RemoteException e) {
            Log.w(TAG, "Energy widgets service unavailable", e);
        }
    }

    private final RadioWidgetDashboard radioWidget = new RadioWidgetDashboard(this);

    private final CabinTemperatureWidgetDashboard cabinTemperatureWidget =
            new CabinTemperatureWidgetDashboard(this);
    private final BrightnessWidgetDashboard brightnessWidget = new BrightnessWidgetDashboard(this);
    private final RecirculationWidgetDashboard recirculationWidget =
            new RecirculationWidgetDashboard(this);
    private final FuelFlapWidgetDashboard fuelFlapWidget = new FuelFlapWidgetDashboard(this);
    private final HeadlightWidgetDashboard headlightWidget = new HeadlightWidgetDashboard(this);

    private void watchHeadlightWidget() {
        headlightWidget.watch(
                suspensionScreenResumed
                        && sharedPreferences.getBoolean(HeadlightWidgetLayout.VISIBLE, false));
    }

    private SuspensionWidgetView suspensionWidgetView;
    private Bundle suspensionState = new Bundle();
    private boolean suspensionScreenResumed;
    // True only around the render triggered by onResume: launches belong to opening the screen,
    // not to every later relayout or drag reorder.
    private boolean dashboardOpening;
    private final Set<String> pendingAutoStartWidgets = new HashSet<>();
    private final Messenger suspensionClient =
            new Messenger(
                    new Handler(
                            android.os.Looper.getMainLooper(),
                            msg -> {
                                if (msg.what != ru.big.town.common.SuspensionWidgetProtocol.STATE) {
                                    return false;
                                }
                                suspensionState = new Bundle(msg.getData());
                                if (suspensionWidgetView != null) {
                                    suspensionWidgetView.update(suspensionState);
                                }
                                return true;
                            }));
    private final Runnable suspensionUnavailable =
            () -> {
                if (suspensionScreenResumed
                        && !suspensionState.containsKey(
                                ru.big.town.common.SuspensionWidgetProtocol.HEIGHT)) {
                    suspensionState.putString(
                            ru.big.town.common.SuspensionWidgetProtocol.MESSAGE,
                            "Нужна связь с обновлённым Native");
                    if (suspensionWidgetView != null) {
                        suspensionWidgetView.update(suspensionState);
                    }
                }
            };

    private void sendSuspensionMessage(int what, int level) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            return;
        }
        try {
            Message msg = Message.obtain(null, what, level, 0);
            msg.replyTo = suspensionClient;
            GlobalVars.serviceMessenger.send(msg);
        } catch (RemoteException e) {
            Log.w(TAG, "Suspension service unavailable", e);
        }
    }

    private void watchSuspension() {
        watchHeadlightWidget();
        recirculationWidget.watch(
                suspensionScreenResumed
                        && sharedPreferences.getBoolean(RecirculationWidgetLayout.VISIBLE, false));
        fuelFlapWidget.watch(
                suspensionScreenResumed
                        && sharedPreferences.getBoolean(FuelFlapWidgetLayout.VISIBLE, false));
        cabinTemperatureWidget.watch(
                suspensionScreenResumed
                        && sharedPreferences.getBoolean(
                                CabinTemperatureWidgetLayout.VISIBLE, false));
        brightnessWidget.watch(
                suspensionScreenResumed
                        && sharedPreferences.getBoolean(BrightnessWidgetLayout.VISIBLE, false));
        radioWidget.watch(
                suspensionScreenResumed
                        && sharedPreferences.getBoolean(RadioWidgetLayout.VISIBLE, false));
        uiHandler.removeCallbacks(suspensionUnavailable);
        boolean show =
                suspensionScreenResumed
                        && sharedPreferences.getBoolean("showSuspensionWidget", false);
        sendSuspensionMessage(
                show
                        ? ru.big.town.common.SuspensionWidgetProtocol.WATCH
                        : ru.big.town.common.SuspensionWidgetProtocol.UNWATCH,
                0);
        if (show) {
            uiHandler.postDelayed(suspensionUnavailable, 5000);
        }
    }

    private final BroadcastReceiver tripReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    tripTimerReceived = true;
                    tripActive = intent.getBooleanExtra("tripActive", false);
                    tripInDrive = intent.getBooleanExtra("inDrive", false);
                    tripWaitingForMovement =
                            intent.getBooleanExtra(TripProtocol.WAITING_FOR_MOVEMENT, false);
                    tripAccumMs = intent.getLongExtra("accumMs", 0L);
                    tripDriveStartElapsed = intent.getLongExtra("driveStartElapsed", 0L);
                    tripStartWall = intent.getLongExtra("tripStartWall", 0L);
                    String tj = intent.getStringExtra("tripsJson");
                    if (tj != null) {
                        lastTripsJson = tj;
                    }
                    updateTripTimer();
                }
            };

    private final BroadcastReceiver batteryHeatReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    renderBatteryHeat(intent);
                }
            };

    private final BroadcastReceiver powerHoldStatusReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    renderPowerHoldStatus(intent);
                }
            };

    private final BroadcastReceiver embeddedLeftReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String packageName = intent.getStringExtra(EXTRA_EMBEDDED_TASK_PKG);
                    String widgetId = intent.getStringExtra("widgetId");
                    if (widgetId != null) {
                        AppWidgetSessionStore.forgetLaunch(
                                sharedPreferences, currentWidgetSessionId(), widgetId);
                        if (packageName != null
                                && packageName.equals(embeddedWidgetPackages.get(widgetId))) {
                            detachEmbeddedWidget(widgetId, appWidgetTileViews.get(widgetId));
                        }
                        return;
                    }
                    if (intent.getBooleanExtra(EXTRA_EMBEDDED_TASK_CLOSED, false)) {
                        AppWidgetSessionStore.forgetPackage(
                                sharedPreferences, currentWidgetSessionId(), packageName);
                    }
                    releaseEmbeddedWidgetsOf(packageName);
                }
            };

    /**
     * Приложение открыли в полный экран, а его задача уехала с дисплея виджета: снимаем такие
     * виджеты, иначе они показывали бы чёрный квадрат, который сам не восстановится.
     */
    private void releaseEmbeddedWidgetsOf(String pkg) {
        if (pkg == null) {
            return;
        }
        for (String widgetId : new ArrayList<>(embeddedWidgetSurfaces.keySet())) {
            View tile = appWidgetTileViews.get(widgetId);
            // Сравниваем с запущенным пакетом, а не с настройкой: после переноса или обмена
            // экземпляров они могут различаться.
            if (tile != null && pkg.equals(embeddedWidgetPackages.get(widgetId))) {
                releaseEmbeddedWidget(widgetId, tile);
            }
        }
    }

    private void detachCarPlayWidgetsForWindow() {
        for (String widgetId : new ArrayList<>(embeddedWidgetPackages.keySet())) {
            if (!CarPlayTarget.isCarPlay(embeddedWidgetPackages.get(widgetId))) continue;
            sendEmbeddedDetach(widgetId);
            // Remove visible callbacks now; Native keeps the parked VD until task removal.
            detachEmbeddedWidget(widgetId, appWidgetTileViews.get(widgetId));
        }
    }

    private void renderPowerHoldStatus(Intent intent) {
        if (powerHoldBadge == null || intent == null) {
            return;
        }
        int status = intent.getIntExtra("status", POWER_HOLD_UNKNOWN);
        int exitReason = intent.getIntExtra("exitReason", 0);
        int requestOutcome = intent.getIntExtra("requestOutcome", 0);
        switch (status) {
            case POWER_HOLD_INACTIVE:
                if (exitReason == POWER_HOLD_EXIT_LOW_BATTERY) {
                    powerHoldBadge.setText(R.string.power_hold_status_exit_low_battery);
                } else if (exitReason == POWER_HOLD_EXIT_TIME_UP) {
                    powerHoldBadge.setText(R.string.power_hold_status_exit_time_up);
                } else {
                    powerHoldBadge.setText(R.string.power_hold_status_inactive);
                }
                powerHoldBadge.setBackgroundResource(R.drawable.pill_inactive);
                break;
            case POWER_HOLD_ACTIVATING:
                powerHoldBadge.setText(R.string.power_hold_status_activating);
                powerHoldBadge.setBackgroundResource(R.drawable.pill_pending);
                break;
            case POWER_HOLD_ACTIVE:
                powerHoldBadge.setText(R.string.power_hold_status_active);
                powerHoldBadge.setBackgroundResource(R.drawable.pill_active);
                break;
            case POWER_HOLD_REQUESTED:
                powerHoldBadge.setText(R.string.power_hold_status_requested);
                powerHoldBadge.setBackgroundResource(R.drawable.pill_pending);
                break;
            case POWER_HOLD_ARMED:
                powerHoldBadge.setText(R.string.power_hold_status_armed);
                powerHoldBadge.setBackgroundResource(R.drawable.pill_pending);
                break;
            case POWER_HOLD_FAILED:
                powerHoldBadge.setText(R.string.power_hold_status_failed);
                powerHoldBadge.setBackgroundResource(R.drawable.pill_error);
                break;
            case POWER_HOLD_UNKNOWN:
            default:
                powerHoldBadge.setText(R.string.power_hold_status_unknown);
                powerHoldBadge.setBackgroundResource(R.drawable.pill_inactive);
                break;
        }
        showPowerHoldRequestOutcome(requestOutcome);
    }

    private void showPowerHoldRequestOutcome(int outcome) {
        switch (outcome) {
            case POWER_HOLD_REQUEST_ACCEPTED:
                showSnack(getString(R.string.power_hold_request_accepted));
                break;
            case POWER_HOLD_REQUEST_ARMED:
                showSnack(getString(R.string.power_hold_request_armed));
                break;
            case POWER_HOLD_REQUEST_ACTIVE:
                showSnack(getString(R.string.power_hold_request_active));
                break;
            case POWER_HOLD_REQUEST_RESULT_UNKNOWN:
                showSnack(getString(R.string.power_hold_request_result_unknown));
                break;
            case POWER_HOLD_REQUEST_NOT_IN_PARK:
                showSnack(getString(R.string.power_hold_request_not_in_park));
                break;
            case POWER_HOLD_REQUEST_LOW_BATTERY:
                showSnack(getString(R.string.power_hold_request_low_battery));
                break;
            case POWER_HOLD_REQUEST_STATE_UNAVAILABLE:
                showSnack(getString(R.string.power_hold_request_state_unavailable));
                break;
            case POWER_HOLD_REQUEST_TRANSPORT_FAILURE:
                showSnack(getString(R.string.power_hold_request_transport_failure));
                break;
            default:
                break;
        }
    }

    // Синхронизация карточек, когда Force EV или звук пешеходов переключены кнопкой руля.
    private final BroadcastReceiver settingSyncReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String key = intent.getStringExtra("key");
                    if (key == null || !intent.hasExtra("value")) {
                        return;
                    }
                    boolean value = intent.getBooleanExtra("value", false);
                    editor.putBoolean(key, value).apply();
                    if ("forcedEv".equals(key)) {
                        forcedEvOn = value;
                    } else if ("autoLight".equals(key)) {
                        autoLightOn = value;
                    } else if ("suspensionMaintenance".equals(key)) {
                        suspensionMaintenanceOn = value;
                    } else if ("disablePedestrianSound".equals(key)) {
                        pedestrianOn = !value;
                    } else {
                        return;
                    }
                    updateToggleVisuals();
                }
            };

    private final Runnable tripTick =
            new Runnable() {
                @Override
                public void run() {
                    updateTripTimer();
                    uiHandler.postDelayed(this, 1000);
                }
            };

    private void updateTripTimer() {
        if (tripDate != null) {
            String date =
                    (tripStartWall > 0
                                    ? java.time.Instant.ofEpochMilli(tripStartWall)
                                            .atZone(java.time.ZoneId.systemDefault())
                                            .toLocalDate()
                                    : LocalDate.now())
                            .format(TRIP_DATE_FORMAT);
            if (!date.contentEquals(tripDate.getText())) {
                tripDate.setText(date);
            }
        }
        long ms = tripAccumMs;
        if (tripActive && tripInDrive) {
            ms += SystemClock.elapsedRealtime() - tripDriveStartElapsed;
        }
        for (VehicleWidgetView view : energyWidgetViews) {
            if (view instanceof TripWidgetView) {
                ((TripWidgetView) view).timer(tripTimerReceived ? Math.max(0, ms) : -1);
            }
            view.invalidate(); // expire a silent/stalled Native connection even without messages
        }
        if (tripTimer != null) {
            tripTimer.setText(fmtDuration(ms));
        }
        if (tripStop != null) {
            tripStop.setEnabled(tripTimerReceived && tripActive);
        }
        if (tripStatus != null) {
            tripStatus.setText(
                    !tripActive
                            ? (tripWaitingForMovement
                                    ? "остановлена · ожидание движения"
                                    : "нет активной поездки")
                            : (tripInDrive ? "в пути" : "на паузе (не Drive)"));
        }
    }

    /** Полный формат таймера: H:MM:SS. */
    private static String fmtDuration(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    /** Показ снимка статуса прогрева батареи в виджете (данные из BatteryHeatService). */
    private void renderBatteryHeat(Intent intent) {
        if (batteryHeatTemp == null) {
            return;
        }
        int temp = intent.getIntExtra("ambientTemp", BH_TEMP_INVALID);
        int status = intent.getIntExtra("controlStatus", BH_UNKNOWN);
        int switchState = intent.getIntExtra("switchState", BH_UNKNOWN);
        int preheat = intent.getIntExtra("preheatSet", BH_UNKNOWN);
        int bms = intent.getIntExtra("bmsState", BH_UNKNOWN);
        int autoCtl = intent.getIntExtra("autoCtrl", BH_UNKNOWN);
        int fail = intent.getIntExtra("failReason", BH_UNKNOWN);
        int platform = intent.getIntExtra("vehiclePlatform", 0);
        int phase = intent.getIntExtra("activationPhase", BH_PHASE_IDLE);

        int threshold = intent.getIntExtra("tempThreshold", 10);
        boolean tempValid = temp != BH_TEMP_INVALID && temp != BH_UNKNOWN;

        batteryHeatTemp.setText(tempValid ? "за бортом: " + temp + " °C" : "за бортом: —");

        String preheatTxt = (bms == 9) ? "идёт" : bhControlState(platform, preheat, switchState);
        batteryHeatStatus.setText(
                "Нагрев: "
                        + bhHeating(status)
                        + "   ·   Pre-heat: "
                        + preheatTxt
                        + "   ·   Автоподогрев: "
                        + bhOnOff(autoCtl));

        String failTxt = bhFail(fail);
        if (failTxt != null) {
            batteryHeatFail.setText("Не удалось запустить прогрев: " + failTxt);
            batteryHeatFail.setVisibility(View.VISIBLE);
        } else {
            batteryHeatFail.setVisibility(View.GONE);
        }

        if (buttonBatteryHeat != null) {
            boolean enabled = phase == BH_PHASE_IDLE;
            buttonBatteryHeat.setEnabled(enabled);
            buttonBatteryHeat.setAlpha(enabled ? 1.0f : 0.55f);
            if (phase == BH_PHASE_SENDING) {
                buttonBatteryHeat.setText("Отправка…");
            } else if (phase == BH_PHASE_AWAITING_CONFIRMATION) {
                buttonBatteryHeat.setText("Ожидаем ответ…");
            } else if (phase == BH_PHASE_ACTIVE) {
                buttonBatteryHeat.setText("Прогрев активен");
            } else if (phase == BH_PHASE_ENABLED) {
                buttonBatteryHeat.setText("Контроль включён");
            } else if (phase == BH_PHASE_BLOCKED) {
                buttonBatteryHeat.setText("Сейчас недоступно");
            } else {
                buttonBatteryHeat.setText("Запустить прогрев");
            }
        }

        applyBatteryHeatIndicator(status, bms, fail, temp, tempValid, threshold, phase);
    }

    /**
     * Графический индикатор состояния прогрева: тон иконки + цветная «пилюля» с подписью. Числовой
     * температуры батареи голова не отдаёт, поэтому цвет отражает состояние термоменеджмента ВВБ и
     * уличный холод (приоритет — сверху вниз): красный — неисправность ВВБ (BMS_STATE=FAULT);
     * зелёный — идёт прогрев; жёлтый — прогрев невозможен (зарядка / низкий заряд / ВВ выкл /
     * температура вне диапазона); синий — на улице холодно (ниже порога), прогрев уместен; серый —
     * норма / нет данных.
     */
    private void applyBatteryHeatIndicator(
            int status, int bms, int fail, int temp, boolean tempValid, int threshold, int phase) {
        int color;
        String label;
        boolean anyData =
                status != BH_UNKNOWN || bms != BH_UNKNOWN || fail != BH_UNKNOWN || tempValid;
        if (bms == 8) { // BMS_STATE_FAULT
            color = BH_COLOR_FAULT;
            label = "Неисправность";
        } else if (status == 1 || bms == 9 || phase == BH_PHASE_ACTIVE) {
            color = BH_COLOR_HEATING;
            label = "Прогрев";
        } else if (fail >= 1 && fail <= 4) { // прогрев невозможен
            color = BH_COLOR_WARN;
            label = "Внимание";
        } else if (phase == BH_PHASE_SENDING || phase == BH_PHASE_AWAITING_CONFIRMATION) {
            color = BH_COLOR_COLD;
            label = "Ожидание";
        } else if (phase == BH_PHASE_ENABLED) {
            color = BH_COLOR_HEATING;
            label = "Контроль включён";
        } else if (tempValid && temp < threshold) { // на улице холодно
            color = BH_COLOR_COLD;
            label = "Холодно";
        } else {
            color = BH_COLOR_NORMAL;
            label = anyData ? "Норма" : "Нет данных";
        }

        if (batteryHeatIcon != null) {
            batteryHeatIcon.setColorFilter(color);
        }
        if (batteryHeatState != null) {
            batteryHeatState.setText(label);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color);
            bg.setCornerRadius(getResources().getDisplayMetrics().density * 14f);
            batteryHeatState.setBackground(bg);
        }
    }

    private static String bhHeating(int v) {
        switch (v) {
            case 0:
                return "нет";
            case 1:
                return "идёт";
            case 2:
                return "инициализация";
            default:
                return "—";
        }
    }

    private static String bhOnOff(int v) {
        switch (v) {
            case 1:
                return "вкл";
            case 2:
                return "выкл";
            default:
                return "—";
        }
    }

    private static String bhControlState(int platform, int h97xState, int h97cSwitch) {
        if (platform == BH_PLATFORM_H97X) {
            if (h97xState == 1) {
                return "вкл";
            }
            if (h97xState == 0) {
                return "выкл";
            }
            return "—";
        }
        if (platform == BH_PLATFORM_H97C) {
            return bhOnOff(h97cSwitch);
        }
        if (h97xState == 1) {
            return "вкл";
        }
        if (h97xState == 0) {
            return "выкл";
        }
        return bhOnOff(h97cSwitch);
    }

    /** Причина отказа прогрева (FAIL_STATE): null — отказа нет / нет данных. */
    private static String bhFail(int v) {
        switch (v) {
            case 1:
                return "идёт зарядка";
            case 2:
                return "высоковольтная сеть выключена";
            case 3:
                return "низкий заряд батареи";
            case 4:
                return "температура вне допустимого диапазона";
            default:
                return null; // 0 = нет отказа, прочее/UNKNOWN — не показываем
        }
    }

    /** Прогрев батареи в один клик — шлём в BatteryHeatService (тот дёргает CAN-команду). */
    public void onButtonBatteryHeat(View v) {
        Intent i = new Intent(ACTION_BATTERY_HEAT_ACTIVATE);
        i.setPackage("ru.big.town.anative");
        sendBroadcast(i);
        showSnack("Запрос отправлен, ожидаем подтверждение автомобиля…");
        Log.i(TAG, "BATTERY_HEAT_ACTIVATE отправлен");
    }

    /** Открыть отдельный экран истории поездок (снимок лога передаём в интенте). */
    public void onButtonTripHistory(View v) {
        Intent i = new Intent(this, TripHistoryActivity.class);
        i.putExtra("tripsJson", lastTripsJson);
        startActivity(i);
    }

    /** Ручное завершение текущей поездки с сохранением её истории и маршрута. */
    public void onButtonTripStop(View v) {
        new MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                .setTitle("Остановить поездку")
                .setMessage(
                        "Завершить текущую поездку и остановить таймер и запись маршрута? При"
                                + " включённой истории поездка будет сохранена.")
                .setPositiveButton(
                        "Стоп",
                        (d, w) -> {
                            Intent i = new Intent(ACTION_TRIP_STOP).setPackage(TripProtocol.NATIVE);
                            sendBroadcast(i, TripProtocol.PERMISSION);
                            Log.i(TAG, "TRIP_STOP отправлен");
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // Handling result
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK) {
            Log.i(
                    "onActivityResult",
                    String.format(
                            "requestCode - %d resultCode - %d data %s",
                            requestCode, resultCode, data.toString()));

            customCommand = data.getStringExtra("customCommand");
            customCommandCount = data.getIntExtra("customCommandCount", 1);

            Log.i(
                    "onActivityResult",
                    String.format("customCommand=%s count=%d", customCommand, customCommandCount));

            editor.putString("customCommand", customCommand);
            editor.putInt("customCommandCount", customCommandCount);
            editor.apply();
        }
    }

    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case ru.big.town.common.QuickControlProtocol.RESULT:
                    String error = msg.getData().getString("error", "");
                    if (!error.isEmpty()) showSnack(error);
                    break;
                case CarPlayWidgetProtocol.MESSAGE:
                    restoreCarPlayWidgets(msg.getData());
                    break;
                case MSG_RESULT:
                    Log.i(TAG, "handleMessage() MSG_RESULT");
                    break;
                case ru.big.town.common.TaskManagerProtocol.LIST:
                    {
                        // Ответ Native на запрос плитки «Диспетчер задач»: число в центре плитки.
                        java.util.ArrayList<String> packages =
                                msg.getData() == null
                                        ? null
                                        : msg.getData()
                                                .getStringArrayList(
                                                        ru.big.town.common.TaskManagerProtocol
                                                                .PACKAGES);
                        int count = packages == null ? 0 : packages.size();
                        Log.i(TAG, "handleMessage() TASK_LIST count=" + count);
                        if (taskManagerCount != null) {
                            taskManagerCount.setText(String.valueOf(count));
                        }
                        break;
                    }
                default:
                    Log.i(TAG, "handleMessage() default");
                    super.handleMessage(msg);
            }
        }
    }

    private boolean bindingRequested = false;
    private boolean connectionReported = false;
    private boolean destroyed = false;
    private static final long BIND_RETRY_MS = 5_000L;
    private final Runnable messengerRebindRunnable = this::bindToMessengerService;

    private ServiceConnection connection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    if (destroyed) {
                        return;
                    }
                    uiHandler.removeCallbacks(messengerRebindRunnable);
                    bindingRequested = true;
                    Log.i(TAG, "onServiceConnected()");
                    if (!connectionReported) {
                        connectionReported = true;
                        GlobalVars.clientConnected(new Messenger(service));
                        watchSuspension();
                        watchEnergyWidgets();
                        requestTaskCount(); // плитке «Диспетчер задач» нужен свежий счётчик
                        requestCarPlayWidgets();
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    clearReportedConnection();
                    suspensionState = new Bundle();
                    energyWidgetState = new Bundle();
                    tripTimerReceived = false;
                    for (VehicleWidgetView view : energyWidgetViews) {
                        view.update(energyWidgetState);
                    }
                    if (suspensionWidgetView != null) {
                        suspensionWidgetView.update(suspensionState);
                    }
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    restartMessengerBinding("binding died");
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    restartMessengerBinding("null binding");
                }
            };

    protected Intent nativeServiceIntent() {
        return new Intent()
                .setComponent(
                        new ComponentName(
                                "ru.big.town.anative", "ru.big.town.anative.SetModesService"));
    }

    private void bindToMessengerService() {
        if (destroyed || bindingRequested) {
            return;
        }
        uiHandler.removeCallbacks(messengerRebindRunnable);
        Log.i(TAG, "bindToMessengerService() begin");

        Intent intent = nativeServiceIntent();
        try {
            bindingRequested = bindService(intent, connection, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bindToMessengerService() end, requested=" + bindingRequested);
            if (!bindingRequested) {
                scheduleMessengerRebind();
            }
        } catch (RuntimeException e) {
            bindingRequested = false;
            Log.w(TAG, "bindToMessengerService() failed: " + e.getMessage());
            scheduleMessengerRebind();
        }
    }

    private void clearReportedConnection() {
        if (!connectionReported) {
            return;
        }
        connectionReported = false;
        GlobalVars.clientDisconnected();
        systemWidgets.disconnected();
        recirculationWidget.disconnected();
        fuelFlapWidget.disconnected();
        headlightWidget.disconnected();
        brightnessWidget.disconnected();
        cabinTemperatureWidget.disconnected();
    }

    private void restartMessengerBinding(String reason) {
        Log.w(TAG, "SetModesService " + reason + " — replacing binding");
        releaseMessengerBinding(reason);
        scheduleMessengerRebind();
    }

    private void scheduleMessengerRebind() {
        if (destroyed) {
            return;
        }
        uiHandler.removeCallbacks(messengerRebindRunnable);
        uiHandler.postDelayed(messengerRebindRunnable, BIND_RETRY_MS);
    }

    private void releaseMessengerBinding(String reason) {
        uiHandler.removeCallbacks(messengerRebindRunnable);
        clearReportedConnection();
        if (bindingRequested) {
            try {
                unbindService(connection);
            } catch (RuntimeException e) {
                Log.w(TAG, reason + ": unbindService failed: " + e.getMessage());
            }
        }
        bindingRequested = false;
    }

    public boolean sendMessageToService(int message) {
        return sendMessageToService(message, 0);
    }

    public boolean sendMessageToService(int message, int arg1) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            return false;
        }

        try {
            Message msg = Message.obtain(null, message, arg1, 0);
            msg.replyTo = clientMessenger;
            GlobalVars.serviceMessenger.send(msg);
            return true;
        } catch (RemoteException e) {
            e.printStackTrace();
            return false;
        }
    }

    /** Тоггл-карточки автосвета/звука пешеходов: находим бейджи + начальное состояние из prefs. */
    private void initToggleCards() {
        refreshToggles();
    }

    /** Клик по карточке «Автосвет» — переключаем и применяем немедленно. */
    public void onCardAutoLight(View v) {
        autoLightOn = !autoLightOn;
        editor.putBoolean("autoLight", autoLightOn).apply();
        sendMessageToService(autoLightOn ? MSG_AUTO_LIGHT_ENABLE : MSG_AUTO_LIGHT_DISABLE);
        updateToggleVisuals();
        Log.i(TAG, "card autoLight=" + autoLightOn);
    }

    /** Клик по карточке «Звук пешеходов» (вкл = звук есть). */
    public void onCardPedestrian(View v) {
        pedestrianOn = !pedestrianOn;
        boolean disabled = !pedestrianOn; // pref: true = заглушить
        editor.putBoolean("disablePedestrianSound", disabled).apply();
        sendMessageToService(MSG_APPLY_PEDESTRIAN, disabled ? 1 : 0);
        updateToggleVisuals();
        Log.i(TAG, "card pedestrianSound on=" + pedestrianOn);
    }

    /** Клик по карточке «Forced EV» (вкл = удерживаем электротягу). */
    public void onCardSuspensionMaintenance(View v) {
        suspensionMaintenanceOn = !settingsPreferences.getBoolean("suspensionMaintenance", false);
        editor.putBoolean("suspensionMaintenance", suspensionMaintenanceOn).apply();
        sendMessageToService(MSG_APPLY_SUSPENSION_MAINTENANCE, suspensionMaintenanceOn ? 1 : 0);
        updateToggleVisuals();
    }

    public void onCardForcedEv(View v) {
        forcedEvOn = !forcedEvOn;
        editor.putBoolean("forcedEv", forcedEvOn).apply();
        sendMessageToService(MSG_APPLY_FORCED_EV, forcedEvOn ? 1 : 0);
        updateToggleVisuals();
        Log.i(TAG, "card forcedEv on=" + forcedEvOn);
    }

    /** Перечитать состояние из prefs (напр. после «Дополнительно») и обновить вид. */
    private void refreshToggles() {
        autoLightOn = settingsPreferences.getBoolean("autoLight", false);
        pedestrianOn = !settingsPreferences.getBoolean("disablePedestrianSound", false);
        forcedEvOn = settingsPreferences.getBoolean("forcedEv", false);
        suspensionMaintenanceOn = settingsPreferences.getBoolean("suspensionMaintenance", false);
        updateToggleVisuals();
    }

    /** Состояние карточки — капсула-тег: голубая «активно» / серая «не активно». */
    private void updateToggleVisuals() {
        applyBadge(autoLightBadge, autoLightOn);
        applyBadge(pedestrianBadge, pedestrianOn);
        applyBadge(forcedEvBadge, forcedEvOn);
        applyBadge(suspensionMaintenanceBadge, suspensionMaintenanceOn);
    }

    private void applyBadge(TextView badge, boolean on) {
        if (badge == null) {
            return;
        }
        badge.setText(on ? "активно" : "не активно");
        badge.setBackgroundResource(on ? R.drawable.pill_active : R.drawable.pill_inactive);
    }

    private void updateAppearanceSpacing() {
        runOnUiThread(
                () -> {
                    if (suspensionScreenResumed && splitTilesGrid != null) {
                        renderSplitTiles();
                    }
                });
    }

    /** Passenger entry point can select its layout independently of display probing. */
    protected DashboardProfile resolveDashboardProfile(int displayId) {
        return DashboardProfile.forDisplay(displayId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        dashboardDisplayId = DashboardProfile.displayId(this);
        dashboardProfile = resolveDashboardProfile(dashboardDisplayId);
        settingsPreferences = dashboardProfile.settingsPreferences(this);
        settingsPreferences.registerOnSharedPreferenceChangeListener(appearancePreferences);
        sharedPreferences = dashboardProfile.layoutPreferences(this);
        registerLiveDashboard();

        bindToMessengerService();

        EdgeToEdge.enable(this);
        getWindow()
                .setFlags(
                        WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);
        appearance = new DashboardAppearance(findViewById(R.id.main), settingsPreferences);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // Свой док удалён — используется родной док головы (висит поверх слева ~145dp, в insets не
        // приходит).
        // Контент отступаем вправо от него + под статус-бар.
        final View mainContent = findViewById(R.id.mainContent);
        final int nativeDock = Math.round(getResources().getDisplayMetrics().density * 145f);
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.main),
                (v, insets) -> {
                    Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    int top = sb.top;
                    if (top == 0) { // на голове статус-бар не сообщает высоту в insets — берём
                        // системный status_bar_height
                        int id =
                                getResources()
                                        .getIdentifier("status_bar_height", "dimen", "android");
                        if (id > 0) {
                            top = getResources().getDimensionPixelSize(id);
                        }
                    }
                    boolean fullscreen = isFullscreenGridEnabled();
                    // «Полноэкранная сетка» снимает верхний отступ целиком: верхний ряд плиток
                    // растягивается на его высоту (см. applyTileVerticalMetrics).
                    mainContent.setPadding(nativeDock + sb.left, fullscreen ? 0 : top, sb.right, 0);
                    // Плитки считают высоту от реально доступного места, поэтому после уточнения
                    // инсетов сетку нужно перерисовать.
                    if (top != contentInsetTop || fullscreen != fullscreenGrid) {
                        contentInsetTop = top;
                        fullscreenGrid = fullscreen;
                        if (splitTilesGrid != null) {
                            splitTilesGrid.post(this::renderSplitTiles);
                        }
                    }
                    return insets;
                });

        GlobalVars.sharedPreferences = settingsPreferences;

        splitTilesGrid = findViewById(R.id.splitTilesGrid);
        configureDesktopEditor();
        gridPaddingTop = splitTilesGrid.getPaddingTop();
        gridPaddingBottom = splitTilesGrid.getPaddingBottom();

        // Автовыравнивание сетки: отсчёт покоя после прокрутки и отмена по касанию.
        contentScroll = findViewById(R.id.contentScroll);
        if (contentScroll != null) {
            contentScroll.setOnScrollChangeListener(
                    (v, scrollX, scrollY, oldScrollX, oldScrollY) ->
                            onGridScrolled(scrollX, oldScrollX));
            // Тап обрабатывает плитка, поэтому сюда доходят движения после начала прокрутки;
            // любое из них означает, что пользователь ведёт сетку сам.
            contentScroll.setOnTouchListener(
                    (v, event) -> {
                        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            // «По целой странице» — сразу после отпускания, без паузы покоя.
                            v.post(this::snapPageOnRelease);
                        } else {
                            stopGridSnapAnimation();
                        }
                        return false;
                    });
        }

        editor = settingsPreferences.edit();
        GlobalVars.editor = editor;

        initToggleCards();
        initIntent();
        GlobalVars.clientMessenger = clientMessenger;
    }

    public void initIntent() {
        if (resultIntent == null) {
            resultIntent = new Intent(this, AdvanceActivity.class);
        }

        resultIntent.putExtra("customCommand", customCommand);
        resultIntent.putExtra("customCommandCount", customCommandCount);
        resultIntent.putExtra(
                AdvanceActivity.EXTRA_SECTION,
                dashboardProfile.isPassenger()
                        ? SettingsSection.PASSENGER.identifier
                        : SettingsSection.MAIN.identifier);
    }

    public void initIntentStarButton() {
        if (resultIntentStarButton == null) {
            resultIntentStarButton = new Intent(this, AdvanceActivityStarButton.class);
        }
    }

    private void getModes() {

        Cursor cursor =
                getContentResolver()
                        .query(
                                Uri.parse(
                                        "content://ru.big.town.restoremode.restoremodecontentprovider/"),
                                null,
                                null,
                                null,
                                null);
        if (cursor.getCount() != 0) {
            cursor.moveToFirst();
            driveMode = cursor.getString(0);
            energy = cursor.getString(1);
            recycle = cursor.getString(2);
            customCommand = cursor.getString(3);
            customCommandCount = cursor.getInt(4);
            Log.i(
                    "$$$ getModes() $$$",
                    "Query Result:"
                            + "\ndriveMode: "
                            + driveMode
                            + "\nenergy: "
                            + energy
                            + "\nrecycle: "
                            + recycle);
        }
        cursor.close();
    }

    public void onButtonClickClose(View v) {
        finish();
    }

    public void onButtonLeaveCar(View v) {
        MaterialAlertDialogBuilder dialog =
                new MaterialAlertDialogBuilder(this, R.style.DarkDialog);
        View options =
                android.view.LayoutInflater.from(dialog.getContext())
                        .inflate(R.layout.dialog_power_hold, null);
        android.widget.CompoundButton engineExtender =
                options.findViewById(R.id.powerHoldEngineExtender);
        dialog.setTitle(R.string.power_hold_title)
                .setMessage(R.string.power_hold_confirmation)
                .setView(options)
                .setPositiveButton(
                        R.string.power_hold_activate,
                        (d, w) -> {
                            boolean ok =
                                    sendMessageToService(
                                            MSG_LEAVE_CAR, engineExtender.isChecked() ? 1 : 0);
                            if (!ok) {
                                showSnack(getString(R.string.service_not_ready));
                            }
                            Log.i(TAG, "onButtonLeaveCar sent=" + ok);
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void sendQuickControl(String action) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            showSnack(getString(R.string.service_not_ready));
            return;
        }
        Message request = Message.obtain(null, ru.big.town.common.QuickControlProtocol.EXECUTE);
        request.getData().putString("action", action);
        request.replyTo = clientMessenger;
        try {
            GlobalVars.serviceMessenger.send(request);
        } catch (android.os.RemoteException exception) {
            showSnack(getString(R.string.service_not_ready));
        }
    }

    public void onCardWashMode(View v) {
        new MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                .setTitle(R.string.wash_mode_title)
                .setMessage(R.string.wash_mode_confirmation)
                .setPositiveButton(
                        R.string.wash_mode_activate,
                        (d, w) -> {
                            boolean ok = sendMessageToService(MSG_WASH_MODE);
                            if (!ok) {
                                showSnack(getString(R.string.service_not_ready));
                            }
                            Log.i(TAG, "onCardWashMode sent=" + ok);
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Snackbar вместо Toast — в Android Automotive системные тосты приложений не показываются. */
    private void showSnack(String text) {
        Snackbar.make(findViewById(R.id.main), text, Snackbar.LENGTH_LONG).show();
    }

    /** Открыть экран запущенных приложений (RunningAppsActivity из Native). */

    /**
     * Плитка «Быстрый запуск»: сетка приложений с вертикальным скролом, порядок — по подписи.
     * Колонок не больше, чем ширина плитки в ячейках smart-grid.
     */
    private void populateLaunchAppsWidget() {
        if (launchAppsWidget == null) {
            return;
        }
        ViewGroup grid = launchAppsWidget.findViewById(R.id.launchAppsGrid);
        if (grid == null) {
            return;
        }
        grid.removeAllViews();

        View.OnLongClickListener dragLauncher = view -> launchAppsWidget.performLongClick();
        grid.setOnLongClickListener(dragLauncher);
        View scroll = launchAppsWidget.findViewById(R.id.launchAppsScroll);
        if (scroll != null) {
            // ScrollView handles background touches without View's long-press detection.
            GestureDetector dragDetector =
                    new GestureDetector(
                            this,
                            new GestureDetector.SimpleOnGestureListener() {
                                @Override
                                public void onLongPress(MotionEvent event) {
                                    launchAppsWidget.performLongClick();
                                }
                            });
            scroll.setOnTouchListener(
                    (view, event) -> {
                        dragDetector.onTouchEvent(event);
                        return false;
                    });
        }

        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);

        // Пакет без дублей и сразу его подпись: по ней же сортируем.
        Map<String, String> labels = new HashMap<>();
        for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
            String pkg = info.activityInfo.packageName;
            if (labels.containsKey(pkg)) {
                continue;
            }
            if (!isLaunchAppsTileCandidate(pm, pkg)) {
                continue;
            }
            labels.put(pkg, info.loadLabel(pm).toString());
        }

        List<String> packages = new ArrayList<>(labels.keySet());
        packages.sort((a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));

        int columns =
                Math.max(
                        1,
                        Math.min(
                                LAUNCH_APPS_MAX_COLUMNS,
                                TileSizeStore.width(
                                        sharedPreferences,
                                        TileSizeStore.LAUNCH_APPS_WIDGET_ID,
                                        TileSizeStore.LAUNCH_APPS_DEFAULT_WIDTH)));
        if (grid instanceof GridLayout) {
            ((GridLayout) grid).setColumnCount(columns);
        }

        LayoutInflater inf = LayoutInflater.from(this);
        for (String pkg : packages) {
            View item = inf.inflate(R.layout.item_launch_app_grid, grid, false);
            ((TextView) item.findViewById(R.id.launchAppGridLabel)).setText(labels.get(pkg));
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                ((ImageView) item.findViewById(R.id.launchAppGridIcon))
                        .setImageDrawable(pm.getApplicationIcon(ai));
            } catch (Exception ignored) {
            }

            View shortcut = item.findViewById(R.id.launchAppGridShortcut);
            shortcut.setOnClickListener(v -> launchAppNormally(pkg));
            shortcut.setOnLongClickListener(
                    v -> {
                        showLaunchAppMenu(v, pkg);
                        return true;
                    });

            if (grid instanceof GridLayout) {
                int index = ((GridLayout) grid).getChildCount();
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = 0; // ширину колонки задаёт вес
                lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
                lp.columnSpec = GridLayout.spec(index % columns, 1, 1f);
                lp.rowSpec = GridLayout.spec(index / columns, 1);
                item.setLayoutParams(lp);
            }
            grid.addView(item);
        }
    }

    /**
     * Кандидат для плитки «Быстрый запуск»: стороннее приложение, без своих и служебных пакетов.
     */
    private boolean isLaunchAppsTileCandidate(PackageManager pm, String pkg) {
        if (pkg.equals(getPackageName())
                || pkg.startsWith("com.qinggan")
                || pkg.startsWith("com.android.car")) {
            return false;
        }
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
            return (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Контекстное меню иконки «Быстрого запуска»: куда открыть приложение. */
    private void showLaunchAppMenu(View anchor, String pkg) {
        LaunchAppMenu menu = new LaunchAppMenu(this, pkg);
        menu.addDestination(
                "Основной экран",
                R.drawable.settings_icon_fullscreen,
                () -> launchAppOnDisplay(pkg, 0));
        menu.addDestination(
                "Пассажирский экран",
                R.drawable.settings_icon_fullscreen,
                () -> launchAppOnDisplay(pkg, 1));
        List<AppWidgetStore.Entry> widgets = AppWidgetStore.load(sharedPreferences);
        if (widgets.isEmpty()) {
            menu.addDestination("Нет виджетов приложения", R.drawable.settings_icon_apps, null);
        } else {
            for (AppWidgetStore.Entry widget : widgets) {
                menu.addDestination(
                        "Виджет " + appWidgetTitle(widget),
                        R.drawable.settings_icon_apps,
                        () -> launchInsideAppWidget(pkg, widget.id));
            }
        }
        menu.show(anchor, appearance);
    }

    /** Подпись виджета в меню: обозначение и приложение — запущенное или выбранное в настройках. */
    private String appWidgetTitle(AppWidgetStore.Entry entry) {
        String designation = AppWidgetStore.designation(sharedPreferences, entry.id);
        String running = embeddedWidgetPackages.get(entry.id);
        String configured = running != null ? running : entry.selected().packageName;
        String label;
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            label = pm.getApplicationLabel(pm.getApplicationInfo(configured, 0)).toString();
        } catch (Exception e) {
            label = CarPlayTarget.isCarPlay(configured) ? CarPlayTarget.LABEL : configured;
        }
        return designation.isEmpty() ? label : designation + " · " + label;
    }

    /** Открыть приложение обычной задачей на выбранном дисплее: 0 — водитель, 1 — пассажир. */
    private void launchAppOnDisplay(String pkg, int displayId) {

        try {
            Intent intent =
                    new Intent(ACTION_OPEN_ON_DISPLAY)
                            .setClassName(
                                    "ru.big.town.anative",
                                    "ru.big.town.anative.SetModesReceiverDynamic")
                            .putExtra("pkg", pkg)
                            .putExtra("display", displayId);
            sendBroadcast(intent, BIND_SET_MODES_PERMISSION);
            Log.i(TAG, "launchAppOnDisplay " + pkg + " display=" + displayId);
        } catch (Exception e) {
            showSnack("Не удалось открыть приложение");
            Log.w(TAG, "launchAppOnDisplay " + pkg + ": " + e.getMessage());
        }
    }

    /** Открыть приложение внутри выбранного app_widget, не меняя его настройки. */
    private void launchInsideAppWidget(String pkg, String widgetId) {

        AppWidgetStore.Entry entry = AppWidgetStore.find(sharedPreferences, widgetId);
        if (entry == null) {
            showSnack("Виджет приложения не найден");
            return;
        }
        View widgetView = appWidgetTileViews.get(entry.id);
        if (widgetView == null) {
            showSnack("Виджет приложения не на экране");
            return;
        }
        // Native переиспользует embedded-дисплей по widgetId и без освобождения не перезапустит
        // приложение, поэтому сначала снимаем текущее.
        if (embeddedWidgetSurfaces.containsKey(entry.id)) {
            releaseEmbeddedWidget(entry.id, widgetView);
        }
        launchEmbeddedAppWidget(widgetView, entry, pkg, AppDpiStore.get(settingsPreferences, pkg));
    }

    /** Открыть системный экран настроек Android. */
    public void onCardAndroidSettings(View v) {
        try {
            Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть настройки Android", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "onCardAndroidSettings failed: " + e.getMessage());
        }
    }

    /** Открыть системный экран набора для сохранённого номера. */
    public void onDialNumber(View v) {
        sendDialNumber(sharedPreferences.getString("dialWidgetNumber", ""));
    }

    private void onDialNumber(DialWidgetStore.Entry entry) {
        sendDialNumber(entry.number);
    }

    private void sendDialNumber(String rawNumber) {
        String number = rawNumber == null ? "" : rawNumber.replaceAll("[^0-9]", "");
        if (number.length() < 4 || number.length() > 10) {
            showSnack("Сначала сохраните номер от 4 до 10 цифр в настройках");
            return;
        }
        if (number.length() == 10) {
            number = "8" + number;
        }
        try {
            Intent intent = new Intent("com.qinggan.broadcast.action.callfromcard");
            intent.putExtra("dial_number", number);
            getApplicationContext().sendBroadcast(intent);
        } catch (Exception e) {
            showSnack("Не удалось передать номер для вызова");
            Log.w(TAG, "onDialNumber failed: " + e.getMessage());
        }
    }

    public void onButtonClickAdvance(View v) {
        getModes();
        initIntent();
        Log.i(
                "$$$ Main onButtonClickAdvance $$$",
                String.format("%s %d", customCommand, customCommandCount));
        startActivityForResult(resultIntent, REQUEST_CODE);
    }

    public void onButtonClickAdvanceStarButton(View v) {
        getModes();
        initIntentStarButton();
        startActivity(resultIntentStarButton);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        TripLocationPermission.result(this, request);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (DashboardProfile.displayId(this) != dashboardDisplayId) {
            // A system-restored task can still arrive on a different physical display.
            recreate();
            return;
        }
        ru.big.town.restoremode.carplay.CarPlayDashboardLauncher.rememberDisplay(
                getWindowManager().getDefaultDisplay().getDisplayId());
        TripLocationPermission.atStartup(this);
        TripLocationPermission.refresh(this);
        TripLocationPermission.sync(this);
        suspensionScreenResumed = true;
        registerReceiver(
                carPlayFeatureReceiver,
                new IntentFilter(ru.big.town.restoremode.carplay.CarPlayFeature.ACTION_CHANGED),
                RECEIVER_NOT_EXPORTED);
        carPlayFeatureReceiverRegistered = true;
        registerReceiver(
                tripReceiver,
                new IntentFilter(ACTION_TRIP_UPDATE),
                BIND_SET_MODES_PERMISSION,
                null,
                RECEIVER_EXPORTED);
        Intent req = new Intent(ACTION_REQUEST_TRIP_UPDATE);
        req.setPackage("ru.big.town.anative");
        sendBroadcast(req);
        uiHandler.removeCallbacks(tripTick);
        uiHandler.post(tripTick);
        registerReceiver(
                batteryHeatReceiver,
                new IntentFilter(ACTION_BATTERY_HEAT_UPDATE),
                RECEIVER_EXPORTED);
        registerReceiver(
                settingSyncReceiver,
                new IntentFilter("ru.big.town.anative.SETTING_SYNCED"),
                BIND_SET_MODES_PERMISSION,
                null,
                RECEIVER_EXPORTED);
        registerReceiver(
                powerHoldStatusReceiver,
                new IntentFilter(ACTION_POWER_HOLD_STATUS_UPDATE),
                BIND_SET_MODES_PERMISSION,
                null,
                RECEIVER_EXPORTED);
        registerReceiver(
                embeddedLeftReceiver,
                new IntentFilter(ACTION_EMBEDDED_TASK_LEFT),
                BIND_SET_MODES_PERMISSION,
                null,
                RECEIVER_EXPORTED);
        Intent powerHoldRequest = new Intent(ACTION_REQUEST_POWER_HOLD_STATUS);
        powerHoldRequest.setPackage("ru.big.town.anative");
        sendBroadcast(powerHoldRequest, BIND_SET_MODES_PERMISSION);
        Intent bhReq = new Intent(ACTION_REQUEST_BATTERY_HEAT);
        bhReq.setPackage("ru.big.town.anative");
        sendBroadcast(bhReq);
        refreshToggles(); // подхватить изменения, сделанные в «Дополнительно»
        applyMainScreenVisibility();
        // «Полноэкранная сетка» могла быть переключена в «Дополнительно»: вернуть верхний
        // отступ контента в согласованное с настройкой состояние до перерисовки сетки.
        fullscreenGrid = isFullscreenGridEnabled();
        applyContentTopInset();
        // Синхронизировать список плиток перед рендером
        TileOrderStore.sync(
                sharedPreferences,
                settingsPreferences,
                getPackageManager(),
                dashboardProfile.isPassenger());
        dashboardOpening = true;
        try {
            renderSplitTiles();
        } finally {
            dashboardOpening = false;
        }
    }

    /**
     * Показывать ли виджет главного экрана: ключ и дефолт его тумблера из «Дополнительно» →
     * «Главный экран». Дефолты совпадают с тумблерами: Forced EV и «Быстрый запуск» выключены,
     * остальные карточки включены.
     */
    public void onVoiceCommand(View view) {
        if (settingsPreferences.getBoolean(VoiceCommands.ENABLED, false)) {
            startActivity(new Intent(this, VoiceActivity.class));
        } else {
            startActivityForResult(
                    new Intent(this, AdvanceActivity.class)
                            .putExtra(AdvanceActivity.EXTRA_SECTION, AdvanceActivity.SECTION_VOICE),
                    REQUEST_CODE);
        }
    }

    /** Карточка «Сценарии»: всплывающий список для запуска и включения/выключения. */
    public void onScenariosCard(View view) {
        ScenarioQuickList.show(
                this,
                settingsPreferences,
                this::runScenario,
                this::onScenarioToggled,
                this::openScenarioSettings);
    }

    /**
     * Сценарий включён/выключен из всплывающего списка: сохранить, опубликовать и перерисовать
     * плитки.
     */
    private void onScenarioToggled() {
        SplitConfigSync.pushScenarios(this, settingsPreferences);
        VoiceCommands.invalidate();
        TileOrderStore.sync(
                sharedPreferences,
                settingsPreferences,
                getPackageManager(),
                dashboardProfile.isPassenger());
        renderSplitTiles();
    }

    private void openScenarioSettings() {
        startActivityForResult(
                new Intent(this, AdvanceActivity.class)
                        .putExtra(AdvanceActivity.EXTRA_SECTION, AdvanceActivity.SECTION_SCENARIOS),
                REQUEST_CODE);
    }

    /** Плитка сценария: просим Native выполнить сценарий немедленно. */
    private void runScenario(ScenarioStore.Scenario scenario) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            showSnack("Сервис не готов");
            return;
        }
        try {
            Message message = Message.obtain(null, ru.big.town.common.ScenarioProtocol.MSG_RUN);
            Bundle data = new Bundle();
            data.putString(ru.big.town.common.ScenarioProtocol.EXTRA_ID, scenario.id);
            message.setData(data);
            GlobalVars.serviceMessenger.send(message);
            showSnack("Сценарий запущен: " + scenario.name);
        } catch (RemoteException e) {
            Log.w(TAG, "runScenario failed: " + e.getMessage());
            showSnack("Не удалось запустить сценарий");
        }
    }

    /** Плитка «Диспетчер задач»: открыть экран со списком запущенных приложений. */
    public void onTaskManagerTile(View view) {
        startActivity(new Intent(this, TaskManagerActivity.class));
    }

    /** Плитка 1x1: открыть сетку VoyahTune на пассажирском дисплее через Native. */
    public void onPassengerDashboardTile(View view) {
        try {
            Intent intent =
                    new Intent(ACTION_OPEN_PASSENGER_DASHBOARD)
                            .setClassName(
                                    "ru.big.town.anative",
                                    "ru.big.town.anative.SetModesReceiverDynamic");
            sendBroadcast(intent, BIND_SET_MODES_PERMISSION);
        } catch (RuntimeException error) {
            showSnack("Не удалось открыть пассажирский экран");
            Log.w(TAG, "onPassengerDashboardTile: " + error.getMessage());
        }
    }

    /**
     * Число запущенных сторонних приложений для плитки — Native отвечает TaskManagerProtocol.LIST.
     */
    private void requestTaskCount() {
        sendMessageToService(ru.big.town.common.TaskManagerProtocol.REQUEST);
    }

    private String visibilityKey(String widgetId) {
        if (ClockWidgetView.ID.equals(widgetId)
                || VehicleWidgets.isWidget(widgetId)
                || SystemWidgetLayout.isWidget(widgetId)
                || MediaWidgetLayout.isWidget(widgetId)) {
            return "show_" + widgetId;
        }
        switch (widgetId) {
            case RadioWidgetLayout.ID:
                return RadioWidgetLayout.VISIBLE;
            case RecirculationWidgetLayout.ID:
                return RecirculationWidgetLayout.VISIBLE;
            case FuelFlapWidgetLayout.ID:
                return FuelFlapWidgetLayout.VISIBLE;
            case CabinTemperatureWidgetLayout.ID:
                return CabinTemperatureWidgetLayout.VISIBLE;
            case BrightnessWidgetLayout.ID:
                return BrightnessWidgetLayout.VISIBLE;
            case HeadlightWidgetLayout.ID:
                return HeadlightWidgetLayout.VISIBLE;
            case "suspensionWidget":
                return "showSuspensionWidget";
            case "tripCard":
                return "showTripTimer";
            case "cardPowerHold":
            case "cardLeaveCar":
                return "showPowerHold";
            case "cardWashMode":
                return "showWashMode";
            case "cardRearFog":
                return "showRearFog";
            case "cardHotspot":
                return "showHotspot";
            case "cardAutoLight":
                return "showAutoLight";
            case "cardPedestrian":
                return "showPedestrian";
            case "cardSuspensionMaintenance":
                return "showSuspensionMaintenance";
            case "cardForcedEv":
                return "showForcedEv";
            case "cardVoiceCommand":
                return "showVoiceCommand";
            case "cardScenarios":
                return "showScenariosCard";
            case "cardBatteryHeat":
                return "showBatteryHeat";
            case "launchAppsWidget":
                return "showLaunchAppsWidget";
            case "taskManagerTile":
                return "showTaskManagerTile";
            case "passengerDashboardTile":
                return "showPassengerDashboardTile";
            case "cardAndroidSettings":
                return "showAndroidSettings";
            // The VoyahTune settings entry always remains accessible.
            default:
                return null;
        }
    }

    private boolean isWidgetVisible(String widgetId) {
        String key = visibilityKey(widgetId);
        if (key == null) {
            return true;
        }
        switch (widgetId) {
            case "tripCard":
            case "cardLeaveCar":
            case "cardWashMode":
            case "cardAutoLight":
            case "cardPedestrian":
            case "cardScenarios":
            case "cardBatteryHeat":
            case "taskManagerTile":
            case "cardAndroidSettings":
            case "cardPowerHold":
                return sharedPreferences.getBoolean(key, dashboardProfile.defaultVisible(true));
            default:
                return sharedPreferences.getBoolean(key, false);
        }
    }

    /** Скрыть/показать карточки главного экрана по настройкам раздела «Главный экран». */
    private void applyMainScreenVisibility() {
        setCardVisible(tripCard, "showTripTimer");
        setCardVisible(cardPowerHold, "showPowerHold");
        setCardVisible(cardWashMode, "showWashMode");
        setCardVisible(cardAutoLight, "showAutoLight");
        setCardVisible(cardPedestrian, "showPedestrian");
        // Forced EV по умолчанию СКРЫТ — в отличие от остальных карточек (у них дефолт true).
        if (cardSuspensionMaintenance != null) {
            cardSuspensionMaintenance.setVisibility(
                    sharedPreferences.getBoolean("showSuspensionMaintenance", false)
                            ? View.VISIBLE
                            : View.GONE);
        }
        if (cardForcedEv != null) {
            cardForcedEv.setVisibility(
                    sharedPreferences.getBoolean("showForcedEv", false) ? View.VISIBLE : View.GONE);
        }
        setCardVisible(cardBatteryHeat, "showBatteryHeat");
        // Native-виджеты — по умолчанию скрыты, включаются через настройки
        if (launchAppsWidget != null) {
            boolean show = sharedPreferences.getBoolean("showLaunchAppsWidget", false);
            launchAppsWidget.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                populateLaunchAppsWidget();
            }
        }
        applyTripHistoryVisibility(splitTilesGrid);
        for (VehicleWidgetView view : energyWidgetViews) {
            if (view instanceof TripWidgetView) {
                ((TripWidgetView) view)
                        .showTripHistory(settingsPreferences.getBoolean("saveTripHistory", true));
            }
        }
    }

    private void applyTripHistoryVisibility(View root) {
        if (root == null) {
            return;
        }
        boolean enabled = settingsPreferences.getBoolean("saveTripHistory", true);
        for (int id : new int[] {R.id.buttonTripHistory, R.id.buttonCurrentTripHistory}) {
            View button = root.findViewById(id);
            if (button != null) {
                button.setVisibility(enabled ? View.VISIBLE : View.GONE);
            }
        }
    }

    private void setCardVisible(View card, String key) {
        if (card == null) {
            return;
        }
        card.setVisibility(
                sharedPreferences.getBoolean(key, dashboardProfile.defaultVisible(true))
                        ? View.VISIBLE
                        : View.GONE);
    }

    /**
     * Максимум колонок в сетке плитки «Быстрый запуск»: дальше элементы становятся слишком узкими.
     */
    private static final int LAUNCH_APPS_MAX_COLUMNS = 4;

    /** Получить размеры элемента в ячейках smart-grid: {ширина, высота}. */
    private int[] getWidgetDimensions(String widgetId) {
        if (RecirculationWidgetLayout.ID.equals(widgetId)) {
            return new int[] {RecirculationWidgetLayout.WIDTH, RecirculationWidgetLayout.HEIGHT};
        }
        if (FuelFlapWidgetLayout.ID.equals(widgetId)) {
            return new int[] {FuelFlapWidgetLayout.WIDTH, FuelFlapWidgetLayout.HEIGHT};
        }
        if (CabinTemperatureWidgetLayout.ID.equals(widgetId)) {
            return new int[] {
                CabinTemperatureWidgetLayout.WIDTH, CabinTemperatureWidgetLayout.HEIGHT
            };
        }
        if (BrightnessWidgetLayout.ID.equals(widgetId)) {
            return new int[] {BrightnessWidgetLayout.WIDTH, BrightnessWidgetLayout.HEIGHT};
        }
        if (RadioWidgetLayout.ID.equals(widgetId)) {
            return TileSizeStore.dimensions(sharedPreferences, widgetId, 2, 2);
        }
        if (HeadlightWidgetLayout.ID.equals(widgetId)) {
            return TileSizeStore.dimensions(
                    sharedPreferences, widgetId, HeadlightWidgetLayout.DEFAULT_WIDTH, 1);
        }
        if (SystemWidgetLayout.isWidget(widgetId) || ClockWidgetView.ID.equals(widgetId)) {
            return TileSizeStore.dimensions(sharedPreferences, widgetId, 1, 1);
        }
        if (MediaWidgetLayout.isWidget(widgetId)) {
            return TileSizeStore.dimensions(
                    sharedPreferences,
                    widgetId,
                    MediaWidgetLayout.DEFAULT_WIDTH,
                    MediaWidgetLayout.DEFAULT_HEIGHT);
        }
        if (VehicleWidgets.isWidget(widgetId)) {
            int[] size = VehicleWidgets.size(widgetId);
            return TileSizeStore.dimensions(sharedPreferences, widgetId, size[0], size[1]);
        }
        if (TileSizeStore.SUSPENSION_WIDGET_ID.equals(widgetId)) {
            return TileSizeStore.dimensions(
                    sharedPreferences,
                    widgetId,
                    TileSizeStore.SUSPENSION_DEFAULT_WIDTH,
                    TileSizeStore.SUSPENSION_DEFAULT_HEIGHT);
        }
        if ("tripCard".equals(widgetId)) {
            return new int[] {3, 2};
        }
        if ("cardBatteryHeat".equals(widgetId)) {
            return new int[] {3, 2};
        }
        // Компактные карточки-иконки: одна ячейка.
        if ("cardSettings".equals(widgetId)
                || "cardAndroidSettings".equals(widgetId)
                || "cardVoiceCommand".equals(widgetId)
                || "cardScenarios".equals(widgetId)
                || "cardRearFog".equals(widgetId)
                || "cardHotspot".equals(widgetId)) {
            return new int[] {1, 1};
        }
        // Плитка «Диспетчер задач»: 1x1, в центре число запущенных приложений.
        if ("taskManagerTile".equals(widgetId)) {
            return new int[] {1, 1};
        }
        if ("passengerDashboardTile".equals(widgetId)) {
            return new int[] {1, 1};
        }
        // Плитка «Быстрый запуск»: по умолчанию 2x3, размер задаётся в «Дополнительно».
        if (TileSizeStore.LAUNCH_APPS_WIDGET_ID.equals(widgetId)) {
            return TileSizeStore.dimensions(
                    sharedPreferences,
                    widgetId,
                    TileSizeStore.LAUNCH_APPS_DEFAULT_WIDTH,
                    TileSizeStore.LAUNCH_APPS_DEFAULT_HEIGHT);
        }
        return new int[] {2, 1};
    }

    /**
     * Рисует все плитки (сплиты + приложения) в едином смешанном порядке из TileOrderStore, 12 в
     * ряд.
     */
    private void renderSplitTiles() {
        if (splitTilesGrid == null) {
            return;
        }
        // Перестроение меняет размеры плиток и шаг колонки — начатое выравнивание больше не верно.
        cancelGridSnap();
        // «Полноэкранная сетка» снимает и верхний отступ самой сетки: растянутый ряд должен
        // начинаться от границы экрана, а отрицательный отступ GridLayout не переносит.
        splitTilesGrid.setPadding(
                splitTilesGrid.getPaddingLeft(),
                fullscreenGrid ? 0 : gridPaddingTop,
                splitTilesGrid.getPaddingRight(),
                gridPaddingBottom);
        if (tileDragController != null) {
            tileDragController.cancel();
        }
        detachEmbeddedSurfaces();
        splitTilesGrid.removeAllViews();
        suspensionWidgetView = null;
        energyWidgetViews.clear();
        systemWidgets.resetViews();
        mediaWidgets.resetViews();
        recirculationWidget.resetView();
        fuelFlapWidget.resetView();
        headlightWidget.resetView();
        brightnessWidget.resetView();
        cabinTemperatureWidget.resetView();
        radioWidget.resetView();
        watchSuspension();
        watchEnergyWidgets();
        appWidgetTileViews.clear();
        String widgetSessionId = AppWidgetSessionStore.sessionId(this, settingsPreferences);
        boolean firstOpening =
                dashboardOpening
                        && AppWidgetSessionStore.isFirstOpening(sharedPreferences, widgetSessionId);
        if (firstOpening) {
            pendingAutoStartWidgets.clear();
        }
        PackageManager pm = getPackageManager();
        LayoutInflater inf = LayoutInflater.from(this);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int nativeDock = Math.round(metrics.density * 145f);
        int availableW = metrics.widthPixels - nativeDock - Math.round(metrics.density * 4f);
        // Высота ряда считается от того же места, что реально получает сетка: высота экрана
        // минус верхний инсет и собственные вертикальные отступы сетки.
        int availableH = 720 - contentInsetTop - splitTilesGrid.getPaddingTop() - 0;
        int spacingDp = AppearanceSettings.spacingDp(settingsPreferences);
        int m = Math.round(spacingDp * metrics.density);
        int tileW = (availableW / 12) - 2 * m;
        int tileH = (availableH / 5) - 2 * m;
        // Шаг колонки: ширина плитки плюс её отступы слева и справа.
        tilePitchPx = tileW + 2 * m;
        splitTilesGrid.beginLayout(tilePitchPx, tileH + 2 * m);
        final int rows = 5;
        // Высота сетки не делится на число рядов нацело. Остаток (до 4px) раздаём по
        // одному пикселю верхним рядам, иначе под нижним рядом остаётся пустая полоса.
        final int rowRemainder = availableH - (availableH / rows) * rows;
        // «Полноэкранная сетка»: верхний отступ контента снят, поэтому верхний ряд
        // растягивается на его высоту — до самой границы экрана.
        final int topInset = fullscreenGrid ? contentInsetTop : 0;
        // Сколько левых колонок верхнего ряда растягивается до границы экрана.
        final int stretchedColumns = fullscreenGridColumns();
        tileDragController =
                new TileDragController(
                        splitTilesGrid,
                        (column, row, width, height) -> {
                            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                            params.width = tileW * width + (width - 1) * 2 * m;
                            params.columnSpec = GridLayout.spec(column, width);
                            params.rowSpec = GridLayout.spec(row, height);
                            applyTileVerticalMetrics(
                                    params,
                                    m,
                                    tileH,
                                    new int[] {column, row},
                                    height,
                                    rowRemainder,
                                    topInset,
                                    stretchedColumns);
                            return params;
                        },
                        (from, before) -> {
                            insertTileAt(from, before);
                            renderSplitTiles();
                        });

        // Загружаем единый список всех плиток в нужном порядке
        List<TileOrderStore.Tile> tiles = TileOrderStore.load(sharedPreferences);

        // Создаём карту сплитов по id для быстрого доступа
        Map<String, SplitStore.Preset> splitMap = new HashMap<>();
        List<SplitStore.Preset> splits = SplitStore.load(settingsPreferences);
        for (SplitStore.Preset ps : splits) {
            if (ps.ready()) {
                splitMap.put(ps.id, ps);
            }
        }

        List<boolean[]> occupied = new ArrayList<>();
        for (int pos = 0; pos < tiles.size(); pos++) {
            TileOrderStore.Tile tile = tiles.get(pos);
            final int tilePos = pos;

            if (TileOrderStore.Tile.TYPE_LINK.equals(tile.type)) {
                LinkShortcutStore.Entry entry = LinkShortcutStore.find(sharedPreferences, tile.id);
                if (entry == null) {
                    continue;
                }
                View linkView = inf.inflate(R.layout.tile_app_shortcut, splitTilesGrid, false);
                ((ImageView) linkView.findViewById(R.id.tileIco))
                        .setImageResource(R.drawable.ic_link_shortcut);
                ((TextView) linkView.findViewById(R.id.tileTitle)).setText(entry.name);
                linkView.setOnClickListener(view -> LinkShortcutLauncher.open(this, entry));
                int[] gridPosition = TileGridPacking.place(occupied, rows, 1, 1);
                GridLayout.LayoutParams linkLayout = new GridLayout.LayoutParams();
                linkLayout.width = tileW;
                linkLayout.columnSpec = GridLayout.spec(gridPosition[0], 1);
                linkLayout.rowSpec = GridLayout.spec(gridPosition[1]);
                applyTileVerticalMetrics(
                        linkLayout,
                        m,
                        tileH,
                        gridPosition,
                        1,
                        rowRemainder,
                        topInset,
                        stretchedColumns);
                linkView.setLayoutParams(linkLayout);
                splitTilesGrid.addView(linkView);
                bindEditableTile(linkView, tile, tilePos, 1, 1);
                continue;
            }

            if (TileOrderStore.Tile.TYPE_DIAL.equals(tile.type)) {
                DialWidgetStore.Entry dialEntry = null;
                for (DialWidgetStore.Entry candidate : DialWidgetStore.load(sharedPreferences)) {
                    if (candidate.id.equals(tile.id)) {
                        dialEntry = candidate;
                        break;
                    }
                }
                if (dialEntry == null) {
                    continue;
                }
                final DialWidgetStore.Entry finalDialEntry = dialEntry;
                View dialView = inf.inflate(R.layout.tile_dial, splitTilesGrid, false);
                ((TextView) dialView.findViewById(R.id.dialTileName))
                        .setText(
                                finalDialEntry.name.isEmpty()
                                        ? "Набрать номер"
                                        : finalDialEntry.name);
                ((TextView) dialView.findViewById(R.id.dialTileNumber))
                        .setText(
                                finalDialEntry.number.isEmpty()
                                        ? "Номер не задан"
                                        : finalDialEntry.number);
                dialView.setOnClickListener(v -> onDialNumber(finalDialEntry));
                int[] gridPosition = TileGridPacking.place(occupied, rows, 2, 1);
                GridLayout.LayoutParams dialLp = new GridLayout.LayoutParams();
                dialLp.width = tileW * 2 + m * 2;
                dialLp.columnSpec = GridLayout.spec(gridPosition[0], 2);
                dialLp.rowSpec = GridLayout.spec(gridPosition[1]);
                applyTileVerticalMetrics(
                        dialLp,
                        m,
                        tileH,
                        gridPosition,
                        1,
                        rowRemainder,
                        topInset,
                        stretchedColumns);
                dialView.setLayoutParams(dialLp);
                splitTilesGrid.addView(dialView);
                bindEditableTile(dialView, tile, tilePos, 2, 1);
                continue;
            }

            if (TileOrderStore.Tile.TYPE_SCENARIO.equals(tile.type)) {
                ScenarioStore.Scenario scenario = ScenarioStore.find(settingsPreferences, tile.id);
                if (scenario == null || !scenario.enabled) {
                    continue;
                }
                final ScenarioStore.Scenario finalScenario = scenario;
                View scenarioView = inf.inflate(R.layout.tile_scenario, splitTilesGrid, false);
                ((TextView) scenarioView.findViewById(R.id.scenarioTileName))
                        .setText(finalScenario.name.isEmpty() ? "Сценарий" : finalScenario.name);
                scenarioView.setOnClickListener(v -> runScenario(finalScenario));
                int[] gridPosition = TileGridPacking.place(occupied, rows, 2, 1);
                GridLayout.LayoutParams scenarioLp = new GridLayout.LayoutParams();
                scenarioLp.width = tileW * 2 + m * 2;
                scenarioLp.columnSpec = GridLayout.spec(gridPosition[0], 2);
                scenarioLp.rowSpec = GridLayout.spec(gridPosition[1]);
                applyTileVerticalMetrics(
                        scenarioLp,
                        m,
                        tileH,
                        gridPosition,
                        1,
                        rowRemainder,
                        topInset,
                        stretchedColumns);
                scenarioView.setLayoutParams(scenarioLp);
                splitTilesGrid.addView(scenarioView);
                bindEditableTile(scenarioView, tile, tilePos, 2, 1);
                continue;
            }

            if (TileOrderStore.Tile.TYPE_WIDGET.equals(tile.type)) {
                // ===== Это виджет =====
                View widgetView = null;

                if (SystemWidgetLayout.isWidget(tile.id) && !isWidgetVisible(tile.id)) {
                    continue;
                }
                switch (tile.id) {
                    case ClockWidgetView.ID:
                        widgetView = new ClockWidgetView(this);
                        break;
                    case SystemWidgetLayout.CPU:
                    case SystemWidgetLayout.RAM:
                    case SystemWidgetLayout.CLEAR:
                        widgetView = systemWidgets.create(tile.id, splitTilesGrid);
                        break;
                    case MediaWidgetLayout.MEDIA:
                        widgetView = mediaWidgets.create();
                        break;
                    case VehicleWidgets.ENERGY:
                    case VehicleWidgets.CONSUMPTION:
                    case VehicleWidgets.TRIP:
                    case VehicleWidgets.TIRES:
                    case VehicleWidgets.ODO:
                        VehicleWidgetView energyView =
                                VehicleWidgets.create(
                                        this,
                                        tile.id,
                                        CarAppearance.savedColor(settingsPreferences),
                                        getWidgetDimensions(tile.id)[0],
                                        getWidgetDimensions(tile.id)[1]);
                        energyView.update(energyWidgetState);
                        energyWidgetViews.add(energyView);
                        updateTripTimer();
                        if (VehicleWidgets.TRIP.equals(tile.id)) {
                            FrameLayout tripWidget =
                                    (FrameLayout)
                                            inf.inflate(
                                                    R.layout.tile_current_trip,
                                                    splitTilesGrid,
                                                    false);
                            tripWidget.addView(
                                    energyView,
                                    0,
                                    new FrameLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT));
                            ((TripWidgetView) energyView)
                                    .showTripHistory(
                                            settingsPreferences.getBoolean(
                                                    "saveTripHistory", true));
                            energyView.setOnLongClickListener(v -> tripWidget.performLongClick());
                            widgetView = tripWidget;
                        } else {
                            widgetView = energyView;
                        }
                        break;
                    case RadioWidgetLayout.ID:
                        int[] radioSize = getWidgetDimensions(tile.id);
                        widgetView = radioWidget.create(radioSize[0], radioSize[1]);
                        break;
                    case RecirculationWidgetLayout.ID:
                        widgetView = recirculationWidget.create();
                        break;
                    case FuelFlapWidgetLayout.ID:
                        widgetView = fuelFlapWidget.create();
                        break;
                    case CabinTemperatureWidgetLayout.ID:
                        widgetView = cabinTemperatureWidget.create();
                        break;
                    case BrightnessWidgetLayout.ID:
                        widgetView = brightnessWidget.create();
                        break;
                    case HeadlightWidgetLayout.ID:
                        widgetView =
                                headlightWidget.create(
                                        getWidgetDimensions(tile.id)[0],
                                        getWidgetDimensions(tile.id)[1]);
                        break;
                    case "suspensionWidget":
                        suspensionWidgetView =
                                new SuspensionWidgetView(
                                        this,
                                        level -> {
                                            if (!GlobalVars.isBound) {
                                                showSnack("Сервис не готов");
                                                return;
                                            }
                                            sendSuspensionMessage(
                                                    ru.big.town.common.SuspensionWidgetProtocol
                                                            .SELECT,
                                                    level);
                                        });
                        suspensionWidgetView.update(suspensionState);
                        widgetView = suspensionWidgetView;
                        break;
                    case "tripCard":
                        widgetView = inf.inflate(R.layout.tile_trip, splitTilesGrid, false);
                        break;
                    // Исторический id карточки Power Hold: встречается в сохранённом порядке
                    // плиток.
                    case "cardPowerHold":
                    case "cardLeaveCar":
                        widgetView = inf.inflate(R.layout.tile_power_hold, splitTilesGrid, false);
                        break;
                    case "cardWashMode":
                        widgetView = inf.inflate(R.layout.tile_wash_mode, splitTilesGrid, false);
                        break;
                    case "cardVoiceCommand":
                        widgetView =
                                inf.inflate(R.layout.tile_voice_command, splitTilesGrid, false);
                        break;
                    case "cardScenarios":
                        widgetView = inf.inflate(R.layout.tile_scenarios, splitTilesGrid, false);
                        break;
                    case "cardSettings":
                        widgetView = inf.inflate(R.layout.tile_settings, splitTilesGrid, false);
                        break;
                    case "cardAndroidSettings":
                        widgetView =
                                inf.inflate(R.layout.tile_android_settings, splitTilesGrid, false);
                        break;
                    case "cardRearFog":
                        widgetView = inf.inflate(R.layout.tile_rear_fog, splitTilesGrid, false);
                        widgetView.setOnClickListener(view -> sendQuickControl("rear_fog:toggle"));
                        break;
                    case "cardHotspot":
                        widgetView = inf.inflate(R.layout.tile_hotspot, splitTilesGrid, false);
                        widgetView.setOnClickListener(view -> sendQuickControl("hotspot:toggle"));
                        break;
                    case "cardAutoLight":
                        widgetView = inf.inflate(R.layout.tile_auto_light, splitTilesGrid, false);
                        break;
                    case "cardPedestrian":
                        widgetView = inf.inflate(R.layout.tile_pedestrian, splitTilesGrid, false);
                        break;
                    case "cardSuspensionMaintenance":
                        widgetView =
                                inf.inflate(
                                        R.layout.tile_suspension_maintenance,
                                        splitTilesGrid,
                                        false);
                        break;
                    case "cardForcedEv":
                        widgetView = inf.inflate(R.layout.tile_forced_ev, splitTilesGrid, false);
                        break;
                    case "cardBatteryHeat":
                        widgetView = inf.inflate(R.layout.tile_battery_heat, splitTilesGrid, false);
                        break;
                    case "launchAppsWidget":
                        widgetView = inf.inflate(R.layout.tile_launch_apps, splitTilesGrid, false);
                        break;
                    case "taskManagerTile":
                        widgetView = inf.inflate(R.layout.tile_task_manager, splitTilesGrid, false);
                        break;
                    case "passengerDashboardTile":
                        widgetView =
                                inf.inflate(
                                        R.layout.tile_passenger_dashboard, splitTilesGrid, false);
                        break;
                }
                if (widgetView == null) {
                    continue;
                }
                applyTripHistoryVisibility(widgetView);
                // Отключённые карточки не должны занимать место в smart-grid. Настройку читаем
                // именно здесь: сетка пересобирается на каждом рендере, поэтому «скрыть» уже
                // созданную вьюху бесполезно — на её место приходит новая, по умолчанию видимая.
                widgetView.setVisibility(isWidgetVisible(tile.id) ? View.VISIBLE : View.GONE);
                if (widgetView.getVisibility() != View.VISIBLE) {
                    continue;
                }

                if ("cardDialNumber".equals(tile.id)) {
                    continue;
                }

                // Re-bind dynamically inflated views based on ID
                if (tile.id.equals("tripCard")) {
                    tripDate = widgetView.findViewById(R.id.tripDate);
                    tripTimer = widgetView.findViewById(R.id.tripTimer);
                    tripStatus = widgetView.findViewById(R.id.tripStatus);
                    tripStop = widgetView.findViewById(R.id.buttonTripStop);
                    tripCard = widgetView;
                    updateTripTimer();
                } else if (tile.id.equals("cardPowerHold") || tile.id.equals("cardLeaveCar")) {
                    cardPowerHold = widgetView;
                    powerHoldBadge = widgetView.findViewById(R.id.powerHoldBadge);
                    refreshToggles();
                } else if (tile.id.equals("cardWashMode")) {
                    cardWashMode = widgetView;
                } else if (tile.id.equals("cardAutoLight")) {
                    cardAutoLight = widgetView;
                    autoLightBadge = widgetView.findViewById(R.id.autoLightBadge);
                    refreshToggles();
                } else if (tile.id.equals("cardPedestrian")) {
                    cardPedestrian = widgetView;
                    pedestrianBadge = widgetView.findViewById(R.id.pedestrianBadge);
                    refreshToggles();
                } else if (tile.id.equals("cardSuspensionMaintenance")) {
                    cardSuspensionMaintenance = widgetView;
                    suspensionMaintenanceBadge =
                            widgetView.findViewById(R.id.suspensionMaintenanceBadge);
                    refreshToggles();
                } else if (tile.id.equals("cardForcedEv")) {
                    cardForcedEv = widgetView;
                    forcedEvBadge = widgetView.findViewById(R.id.forcedEvBadge);
                    refreshToggles();
                } else if (tile.id.equals("cardBatteryHeat")) {
                    cardBatteryHeat = widgetView.findViewById(R.id.cardBatteryHeat);
                    batteryHeatIcon = widgetView.findViewById(R.id.batteryHeatIcon);
                    batteryHeatState = widgetView.findViewById(R.id.batteryHeatState);
                    batteryHeatTemp = widgetView.findViewById(R.id.batteryHeatTemp);
                    batteryHeatStatus = widgetView.findViewById(R.id.batteryHeatStatus);
                    batteryHeatFail = widgetView.findViewById(R.id.batteryHeatFail);
                    buttonBatteryHeat = widgetView.findViewById(R.id.buttonBatteryHeat);
                } else if (tile.id.equals("launchAppsWidget")) {
                    launchAppsWidget = widgetView.findViewById(R.id.launchAppsWidget);
                    populateLaunchAppsWidget();
                } else if (tile.id.equals("taskManagerTile")) {
                    taskManagerCount = widgetView.findViewById(R.id.taskManagerCount);
                    requestTaskCount();
                }


                // Установить размер виджета в сетке
                int[] dims = getWidgetDimensions(tile.id);
                int[] gridPosition = TileGridPacking.place(occupied, rows, dims[0], dims[1]);
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = tileW * dims[0] + (dims[0] - 1) * 2 * m;
                lp.columnSpec = GridLayout.spec(gridPosition[0], dims[0]);
                lp.rowSpec = GridLayout.spec(gridPosition[1], dims[1]);
                applyTileVerticalMetrics(
                        lp,
                        m,
                        tileH,
                        gridPosition,
                        dims[1],
                        rowRemainder,
                        topInset,
                        stretchedColumns);
                widgetView.setLayoutParams(lp);

                splitTilesGrid.addView(widgetView);
                bindEditableTile(widgetView, tile, tilePos, dims[0], dims[1]);
                continue; // пропустить остальную обработку для этого элемента
            }

            if (TileOrderStore.Tile.TYPE_APP_WIDGET.equals(tile.type)) {
                AppWidgetStore.Entry entry = AppWidgetStore.find(sharedPreferences, tile.id);
                if (entry == null) {
                    continue;
                }
                View appWidgetView = inf.inflate(R.layout.tile_embedded_app, splitTilesGrid, false);
                appWidgetTileViews.put(entry.id, appWidgetView);
                populateAppWidgetLauncher(appWidgetView, entry);

                int widgetWidth = AppWidgetStore.clampWidth(entry.width);
                int widgetHeight = AppWidgetStore.clampHeight(entry.height);
                int[] gridPosition =
                        TileGridPacking.place(occupied, rows, widgetWidth, widgetHeight);
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = tileW * widgetWidth + (widgetWidth - 1) * 2 * m;
                lp.columnSpec = GridLayout.spec(gridPosition[0], widgetWidth);
                lp.rowSpec = GridLayout.spec(gridPosition[1], widgetHeight);
                applyTileVerticalMetrics(
                        lp,
                        m,
                        tileH,
                        gridPosition,
                        widgetHeight,
                        rowRemainder,
                        topInset,
                        stretchedColumns);
                appWidgetView.setLayoutParams(lp);
                splitTilesGrid.addView(appWidgetView);
                bindEditableTile(appWidgetView, tile, tilePos, widgetWidth, widgetHeight);
                if (!CarPlayTarget.isCarPlay(entry.packageName)) {
                    AppWidgetSessionStore.Launch sessionLaunch =
                            AppWidgetSessionStore.findLaunch(
                                    sharedPreferences, widgetSessionId, entry.id);
                    if (firstOpening && entry.autoStart) {
                        scheduleEmbeddedLaunch(appWidgetView, entry, null, entry.autoStartDelay);
                    } else if (!pendingAutoStartWidgets.contains(entry.id)
                            && sessionLaunch != null) {
                        scheduleEmbeddedLaunch(appWidgetView, entry, sessionLaunch, 0);
                    }
                }
                continue;
            }

            if (TileOrderStore.Tile.TYPE_SPLIT.equals(tile.type)) {
                if (dashboardProfile.isPassenger()) {
                    continue;
                }
                // Это плитка сплита
                SplitStore.Preset preset = splitMap.get(tile.id);
                if (preset == null || !preset.ready()) {
                    continue;
                } // пропускаем если сплит удалён

                final SplitStore.Preset finalPreset = preset;

                View tileView = inf.inflate(R.layout.tile_split, splitTilesGrid, false);
                ImageView icoL = tileView.findViewById(R.id.tileIcoLeft);
                ImageView icoR = tileView.findViewById(R.id.tileIcoRight);
                TextView title = tileView.findViewById(R.id.tileTitle);
                TextView ratio = tileView.findViewById(R.id.tileRatio);
                TextView state = tileView.findViewById(R.id.tileState);

                try {
                    icoL.setImageDrawable(pm.getApplicationIcon(preset.l));
                } catch (Exception ignored) {
                }
                try {
                    icoR.setImageDrawable(pm.getApplicationIcon(preset.r));
                } catch (Exception ignored) {
                }
                title.setText(preset.ll + "  |  " + preset.rl);
                ratio.setText(SplitStore.RATIO_LABELS[Math.max(0, Math.min(4, preset.ratio))]);
                state.setText("");
                tileView.setOnClickListener(v -> onSplitTileClick(finalPreset));


                int[] gridPosition = TileGridPacking.place(occupied, rows, 2, 1);
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = tileW * 2 + m * 2;
                lp.columnSpec = GridLayout.spec(gridPosition[0], 2);
                lp.rowSpec = GridLayout.spec(gridPosition[1], 1);
                applyTileVerticalMetrics(
                        lp, m, tileH, gridPosition, 1, rowRemainder, topInset, stretchedColumns);
                tileView.setLayoutParams(lp);
                splitTilesGrid.addView(tileView);
                bindEditableTile(tileView, tile, tilePos, 2, 1);

            } else if (TileOrderStore.Tile.TYPE_APP.equals(tile.type)) {
                // Это плитка приложения
                String pkg = tile.id;

                // Проверяем, установлено ли приложение
                try {
                    pm.getApplicationInfo(pkg, 0);
                } catch (Exception e) {
                    // Приложение удалено — пропускаем и удаляем из списка
                    tiles.remove(pos);
                    TileOrderStore.save(sharedPreferences, tiles);
                    pos--;
                    continue;
                }

                View tileView = inf.inflate(R.layout.tile_app_shortcut, splitTilesGrid, false);
                ImageView ico = tileView.findViewById(R.id.tileIco);
                TextView title = tileView.findViewById(R.id.tileTitle);

                String name = pkg;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    name = pm.getApplicationLabel(ai).toString();
                    ico.setImageDrawable(pm.getApplicationIcon(ai));
                } catch (Exception ignored) {
                }
                title.setText(name);
                tileView.setOnClickListener(v -> onAppTileClick(pkg));


                int[] gridPosition = TileGridPacking.place(occupied, rows, 1, 1);
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = tileW;
                lp.columnSpec = GridLayout.spec(gridPosition[0], 1);
                lp.rowSpec = GridLayout.spec(gridPosition[1]);
                applyTileVerticalMetrics(
                        lp, m, tileH, gridPosition, 1, rowRemainder, topInset, stretchedColumns);
                tileView.setLayoutParams(lp);
                splitTilesGrid.addView(tileView);
                bindEditableTile(tileView, tile, tilePos, 1, 1);
            }
        }
        appearance.apply(splitTilesGrid);
        if (firstOpening) {
            AppWidgetSessionStore.markOpeningHandled(sharedPreferences, widgetSessionId);
        }
        systemWidgets.setResumed(suspensionScreenResumed);
        mediaWidgets.setActive(suspensionScreenResumed);
        requestCarPlayWidgets();
    }

    private String currentWidgetSessionId() {
        return AppWidgetSessionStore.sessionId(this, settingsPreferences);
    }

    /** Registers this dashboard so the other physical screen can exchange app instances. */
    private void registerLiveDashboard() {
        for (Iterator<WeakReference<DashboardActivity>> iterator = liveDashboards.iterator();
                iterator.hasNext(); ) {
            DashboardActivity activity = iterator.next().get();
            if (activity == null || activity.isDestroyed()) {
                iterator.remove();
            } else if (activity == this) {
                return;
            }
        }
        liveDashboards.add(new WeakReference<>(this));
    }

    private void unregisterLiveDashboard() {
        for (Iterator<WeakReference<DashboardActivity>> iterator = liveDashboards.iterator();
                iterator.hasNext(); ) {
            DashboardActivity activity = iterator.next().get();
            if (activity == null || activity == this) {
                iterator.remove();
            }
        }
    }

    /** The other physical screen when it is open in this process. */
    private DashboardActivity otherScreen() {
        for (WeakReference<DashboardActivity> reference : liveDashboards) {
            DashboardActivity activity = reference.get();
            if (activity == null
                    || activity == this
                    || activity.isFinishing()
                    || activity.isDestroyed()) {
                continue;
            }
            if (activity.dashboardProfile.isPassenger() != dashboardProfile.isPassenger()) {
                return activity;
            }
        }
        return null;
    }

    private String screenLabel() {
        return dashboardProfile.isPassenger() ? "пассажирский" : "водительский";
    }

    private void saveWidgetSessionLaunch(String widgetId, String packageName, int dpi) {
        AppWidgetSessionStore.saveLaunch(
                sharedPreferences, currentWidgetSessionId(), widgetId, packageName, dpi);
    }

    /** Open an embedded app in the tile on user action and remember it for this session. */
    private void launchEmbeddedAppWidget(
            View widgetView, AppWidgetStore.Entry entry, String packageName, int dpi) {
        if (!CarPlayTarget.isCarPlay(packageName)) {
            AppWidgetSessionStore.saveLaunch(
                    sharedPreferences, currentWidgetSessionId(), entry.id, packageName, dpi);
        }
        showEmbeddedAppWidget(widgetView, entry, packageName, dpi);
    }

    /**
     * Launch after the screen opens. Auto-start runs only on the first opening of the session; the
     * session launch restores an application that was started earlier.
     */
    private void scheduleEmbeddedLaunch(
            View widgetView,
            AppWidgetStore.Entry entry,
            AppWidgetSessionStore.Launch sessionLaunch,
            int delaySeconds) {
        AppWidgetStore.Profile profile = entry.selected();
        String packageName =
                sessionLaunch == null ? profile.packageName : sessionLaunch.packageName;
        int dpi = sessionLaunch == null ? profile.dpi : sessionLaunch.dpi;
        if (CarPlayTarget.isCarPlay(packageName)) {
            return;
        }
        try {
            getPackageManager().getApplicationInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            AppWidgetSessionStore.forgetLaunch(
                    sharedPreferences, currentWidgetSessionId(), entry.id);
            return;
        }
        AppWidgetSessionStore.saveLaunch(
                sharedPreferences, currentWidgetSessionId(), entry.id, packageName, dpi);
        if (sessionLaunch == null) {
            pendingAutoStartWidgets.add(entry.id);
        }
        uiHandler.postDelayed(
                () -> {
                    View current = appWidgetTileViews.get(entry.id);
                    if (sessionLaunch == null && !pendingAutoStartWidgets.remove(entry.id)) return;
                    if (!suspensionScreenResumed
                            || destroyed
                            || current == null
                            || (sessionLaunch != null && current != widgetView)
                            || embeddedWidgetSurfaces.containsKey(entry.id)) {
                        return;
                    }
                    // A layout edit may replace the tile during its configured startup delay.
                    AppWidgetStore.Entry configured =
                            AppWidgetStore.find(sharedPreferences, entry.id);
                    if (configured != null)
                        showEmbeddedAppWidget(current, configured, packageName, dpi);
                },
                Math.max(0, delaySeconds) * 1000L);
    }

    /**
     * Вертикальные метрики плитки: высота по номеру ряда плюс, при включённой «Полноэкранной
     * сетке», верхний отступ. Верхний отступ контента и сетки в этом режиме снят, поэтому плитки
     * верхнего ряда компенсируют его собственным отступом и остаются на месте, а заданные
     * настройкой первые {@code columns} колонок растягиваются до границы экрана, сохраняя нижнюю
     * кромку.
     */
    private static void applyTileVerticalMetrics(
            GridLayout.LayoutParams lp,
            int m,
            int tileH,
            int[] gridPosition,
            int span,
            int rowRemainder,
            int topInset,
            int columns) {
        int row = gridPosition[1];
        lp.height = tileHeight(tileH, m, row, span, rowRemainder);
        int topMargin = m;
        if (topInset > 0 && row == 0) {
            if (gridPosition[0] < columns) {
                // Снятый отступ возвращаем высотой, а не отступом: плитка доходит до границы.
                lp.height += m + topInset;
                topMargin = 0;
            } else {
                topMargin = m + topInset;
            }
        }
        lp.setMargins(m, topMargin, m, m);
    }

    /** Включена ли «Полноэкранная сетка» в настройках. */
    private boolean isFullscreenGridEnabled() {
        return sharedPreferences != null && sharedPreferences.getBoolean("fullscreenGrid", false);
    }

    /** Сколько левых колонок верхнего ряда растягивается до границы экрана (0…12). */
    private int fullscreenGridColumns() {
        if (sharedPreferences == null) {
            return FULLSCREEN_GRID_COLUMNS_DEFAULT;
        }
        return Math.max(
                0,
                Math.min(
                        FULLSCREEN_GRID_COLUMNS_MAX,
                        sharedPreferences.getInt(
                                "fullscreenGridColumns", FULLSCREEN_GRID_COLUMNS_DEFAULT)));
    }

    // -------- Автовыравнивание сетки после остановки прокрутки --------

    /** Пользователь прокрутил сетку: перезапускаем отсчёт покоя перед выравниванием. */
    private void onGridScrolled(int scrollX, int oldScrollX) {
        appearance.onViewportChanged();
        if (contentScroll == null || tilePitchPx <= 0) {
            return;
        }
        if (gridSnapping) {
            if (scrollX == gridSnapAppliedPx) {
                // Это шаг нашей же анимации.
                return;
            }
            // Пользователь перехватил прокрутку: анимация больше не нужна.
            cancelGridSnap();
        }
        if (splitTilesGrid.isEditing()
                || (tileDragController != null && tileDragController.isDragging())) {
            return;
        }
        if (gridSnapMode() == TileScrollSnap.Mode.OFF) {
            return;
        }
        if (!gridSnapPending) {
            // Первое событие новой прокрутки: запоминаем положение до неё.
            gridSnapAnchorPx = oldScrollX;
            gridSnapPending = true;
        }
        uiHandler.removeCallbacks(gridSnapIdle);
        uiHandler.postDelayed(gridSnapIdle, GRID_SNAP_IDLE_MS);
    }

    /** Прошла пауза покоя: плавно доводим сетку до выровненного положения. */
    private void runGridSnap() {
        gridSnapPending = false;
        if (contentScroll == null || splitTilesGrid == null || tilePitchPx <= 0) {
            return;
        }
        int from = contentScroll.getScrollX();
        int target = gridSnapTarget(from);
        if (target == from) {
            return;
        }
        gridSnapping = true;
        gridSnapAnimator = ValueAnimator.ofFloat(0f, 1f);
        gridSnapAnimator.setDuration(GRID_SNAP_DURATION_MS);
        gridSnapAnimator.setInterpolator(new DecelerateInterpolator());
        gridSnapAnimator.addUpdateListener(
                animation -> {
                    float fraction = (float) animation.getAnimatedValue();
                    gridSnapAppliedPx = Math.round(from + (target - from) * fraction);
                    contentScroll.scrollTo(gridSnapAppliedPx, 0);
                });
        gridSnapAnimator.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        gridSnapping = false;
                    }

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        gridSnapping = false;
                    }
                });
        gridSnapAnimator.start();
    }

    /** «По целой странице»: доводим страницу сразу после отпускания, не ожидая паузы покоя. */
    private void snapPageOnRelease() {
        if (splitTilesGrid.isEditing()
                || contentScroll == null
                || gridSnapMode() != TileScrollSnap.Mode.PAGE) {
            return;
        }
        // Жест завершён: пауза покоя не нужна, а инерцию гасим, чтобы анимация вела сетку сама.
        uiHandler.removeCallbacks(gridSnapIdle);
        gridSnapPending = false;
        contentScroll.fling(0);
        runGridSnap();
    }

    /** Куда довести прокрутку в выбранном режиме. */
    private int gridSnapTarget(int from) {
        int maxScroll = Math.max(0, splitTilesGrid.getWidth() - contentScroll.getWidth());
        TileScrollSnap.Mode mode = gridSnapMode();
        if (mode == TileScrollSnap.Mode.TILE) {
            return TileScrollSnap.tileLeftEdge(from, visibleTileLefts(), maxScroll);
        }
        return TileScrollSnap.target(
                from, gridSnapAnchorPx, tilePitchPx, GRID_COLUMNS, maxScroll, mode);
    }

    /**
     * Левые края видимых плиток в координатах сетки: границы для режима «По левому краю плитки».
     * {@code getLeft()} включает отступ карточки, поэтому выравнивается её реальный край, в том
     * числе у плиток шириной больше одной колонки.
     */
    private int[] visibleTileLefts() {
        int count = 0;
        for (int i = 0; i < splitTilesGrid.getChildCount(); i++) {
            if (isVisibleTile(splitTilesGrid.getChildAt(i))) {
                count++;
            }
        }
        int[] lefts = new int[count];
        int index = 0;
        for (int i = 0; i < splitTilesGrid.getChildCount(); i++) {
            View child = splitTilesGrid.getChildAt(i);
            if (isVisibleTile(child)) {
                lefts[index++] = child.getLeft();
            }
        }
        return lefts;
    }

    private static boolean isVisibleTile(View child) {
        return child.getVisibility() == View.VISIBLE && child.getWidth() > 0;
    }

    /** Отменяет отложенное и текущее выравнивание: перестроение сетки, перехват, пауза. */
    private void cancelGridSnap() {
        uiHandler.removeCallbacks(gridSnapIdle);
        stopGridSnapAnimation();
        gridSnapPending = false;
        gridSnapAppliedPx = -1;
    }

    /** Останавливает только анимацию выравнивания, не трогая отсчёт жеста. */
    private void stopGridSnapAnimation() {
        if (gridSnapAnimator != null) {
            gridSnapAnimator.cancel();
            gridSnapAnimator = null;
        }
        gridSnapping = false;
    }

    /** Режим автовыравнивания из настроек «Сетка главного экрана». */
    private TileScrollSnap.Mode gridSnapMode() {
        if (sharedPreferences == null) {
            return TileScrollSnap.DEFAULT_MODE;
        }
        return TileScrollSnap.fromKey(sharedPreferences.getString(PREF_GRID_SNAP_MODE, null));
    }

    /** Верхний отступ контента: «Полноэкранная сетка» снимает его целиком. */
    private void applyContentTopInset() {
        View mainContent = findViewById(R.id.mainContent);
        if (mainContent == null) {
            return;
        }
        mainContent.setPadding(
                mainContent.getPaddingLeft(),
                fullscreenGrid ? 0 : contentInsetTop,
                mainContent.getPaddingRight(),
                0);
    }

    /**
     * Высота плитки, занимающей {@code span} рядов начиная с ряда {@code row}. Остаток деления
     * высоты сетки на число рядов раздан верхним рядам по 1px, поэтому многострочная плитка
     * добавляет столько пикселей, сколько рядов она накрыла.
     */
    private static int tileHeight(int tileH, int m, int row, int span, int rowRemainder) {
        int extra = Math.max(0, Math.min(span, rowRemainder - row));
        return tileH * span + (span - 1) * 2 * m + extra;
    }

    /** Drop между карточками вставляет элемент в найденную позицию и сдвигает остальные. */
    private void insertTileAt(int fromPos, int insertionPos) {
        List<TileOrderStore.Tile> tiles = TileOrderStore.load(sharedPreferences);
        if (fromPos < 0 || fromPos >= tiles.size()) {
            return;
        }
        TileOrderStore.Tile moved = tiles.remove(fromPos);
        if (fromPos < insertionPos) {
            insertionPos--;
        }
        insertionPos = Math.max(0, Math.min(insertionPos, tiles.size()));
        tiles.add(insertionPos, moved);
        TileOrderStore.save(sharedPreferences, tiles);
    }

    private void configureDesktopEditor() {
        findViewById(R.id.mainContent)
                .setOnTouchListener((view, event) -> splitTilesGrid.onWallpaperMarginTouch(event));
        editBack =
                new OnBackPressedCallback(false) {
                    @Override
                    public void handleOnBackPressed() {
                        splitTilesGrid.setEditing(false);
                    }
                };
        getOnBackPressedDispatcher().addCallback(this, editBack);
        splitTilesGrid.setActions(
                new DashboardEditGrid.Actions() {
                    @Override
                    public void editingChanged(boolean editing) {
                        cancelGridSnap();
                        editBack.setEnabled(editing);
                        for (String id : embeddedWidgetSurfaces.keySet()) {
                            showWindowControls(appWidgetTileViews.get(id), editing);
                        }
                        FrameLayout root = findViewById(android.R.id.content);
                        if (editing) {
                            finishEditing =
                                    (Button)
                                            getLayoutInflater()
                                                    .inflate(
                                                            R.layout.dashboard_edit_done,
                                                            root,
                                                            false);
                            finishEditing.setOnClickListener(
                                    view -> splitTilesGrid.setEditing(false));
                            FrameLayout.LayoutParams params =
                                    (FrameLayout.LayoutParams) finishEditing.getLayoutParams();
                            params.topMargin += contentInsetTop;
                            root.addView(finishEditing, params);
                        } else if (finishEditing != null) {
                            if (tileDragController != null) tileDragController.cancel();
                            root.removeView(finishEditing);
                            finishEditing = null;
                        }
                    }

                    @Override
                    public void move(View tile) {
                        tileDragController.start(tile);
                    }

                    @Override
                    public void remove(TileOrderStore.Tile tile, View view) {
                        removeDesktopTile(tile, view);
                    }

                    @Override
                    public void resize(TileOrderStore.Tile tile, int columns, int rows) {
                        if (TileOrderStore.Tile.TYPE_APP_WIDGET.equals(tile.type)) {
                            AppWidgetStore.Entry entry =
                                    AppWidgetStore.find(sharedPreferences, tile.id);
                            if (entry == null) {
                                return;
                            }
                            entry.width = columns;
                            entry.height = rows;
                            AppWidgetStore.update(sharedPreferences, entry);
                            if (entry.profiles.size() == 1
                                    && CarPlayTarget.isCarPlay(entry.packageName)) {
                                TileSizeStore.setSize(
                                        sharedPreferences,
                                        "carPlayWindow:" + entry.id,
                                        columns,
                                        rows);
                            }
                        } else {
                            TileSizeStore.setSize(sharedPreferences, tile.id, columns, rows);
                        }
                        animateDesktopLayout();
                    }
                });
    }

    private void bindEditableTile(
            View view, TileOrderStore.Tile tile, int position, int columns, int rows) {
        tileDragController.add(view, position, columns, rows);
        DashboardEditGrid.Sizes sizes = null;
        if (TileOrderStore.Tile.TYPE_WIDGET.equals(tile.type)
                && TileSizeStore.isResizable(tile.id)) {
            sizes = (width, height) -> TileSizeStore.supportsSize(tile.id, width, height);
        } else if (TileOrderStore.Tile.TYPE_APP_WIDGET.equals(tile.type)) {
            AppWidgetStore.Entry entry = AppWidgetStore.find(sharedPreferences, tile.id);
            if (entry != null) {
                boolean dedicatedCarPlay =
                        entry.profiles.size() == 1 && CarPlayTarget.isCarPlay(entry.packageName);
                if (!dedicatedCarPlay
                        || !CarPlayTarget.opensActivity(entry.packageName, columns, rows)) {
                    sizes =
                            (width, height) ->
                                    !dedicatedCarPlay
                                            || !CarPlayTarget.opensActivity(
                                                    entry.packageName, width, height);
                }
            }
        }
        splitTilesGrid.register(view, tile, columns, rows, sizes);
    }

    private void animateDesktopLayout() {
        ChangeBounds transition = new ChangeBounds();
        transition.setDuration(220);
        for (int index = 0; index < splitTilesGrid.getChildCount(); index++) {
            String name = splitTilesGrid.getChildAt(index).getTransitionName();
            if (name != null) {
                transition.addTarget(name);
            }
        }
        TransitionManager.beginDelayedTransition(splitTilesGrid, transition);
        renderSplitTiles();
    }

    private void removeDesktopTile(TileOrderStore.Tile tile, View view) {
        switch (tile.type) {
            case TileOrderStore.Tile.TYPE_WIDGET:
                String key = visibilityKey(tile.id);
                if (key == null) {
                    return;
                }
                sharedPreferences.edit().putBoolean(key, false).apply();
                break;
            case TileOrderStore.Tile.TYPE_APP_WIDGET:
                pendingAutoStartWidgets.remove(tile.id);
                AppWidgetSessionStore.forgetLaunch(
                        sharedPreferences, currentWidgetSessionId(), tile.id);
                AppWidgetStore.remove(sharedPreferences, tile.id);
                releaseEmbeddedWidget(tile.id, view);
                TileSizeStore.clear(sharedPreferences, "carPlayWindow:" + tile.id);
                break;
            case TileOrderStore.Tile.TYPE_APP:
                List<String> apps = AppShortcutStore.load(sharedPreferences);
                apps.remove(tile.id);
                AppShortcutStore.save(sharedPreferences, apps);
                break;
            case TileOrderStore.Tile.TYPE_DIAL:
                List<DialWidgetStore.Entry> dials = DialWidgetStore.load(sharedPreferences);
                dials.removeIf(entry -> tile.id.equals(entry.id));
                if (dials.isEmpty())
                    sharedPreferences
                            .edit()
                            .remove("dialWidgetNumber")
                            .remove("dialWidgetContactName")
                            .apply();
                DialWidgetStore.save(sharedPreferences, dials);
                break;
            case TileOrderStore.Tile.TYPE_LINK:
                LinkShortcutStore.remove(sharedPreferences, tile.id);
                break;
            case TileOrderStore.Tile.TYPE_SPLIT:
                List<SplitStore.Preset> presets = SplitStore.load(settingsPreferences);
                for (SplitStore.Preset preset : presets) {
                    if (tile.id.equals(preset.id)) {
                        preset.showOnHome = false;
                    }
                }
                SplitStore.save(settingsPreferences, presets);
                SplitConfigSync.pushAll(this, settingsPreferences);
                break;
            case TileOrderStore.Tile.TYPE_SCENARIO:
                TileOrderStore.setScenarioHidden(sharedPreferences, tile.id, true);
                break;
            default:
                return;
        }
        TileOrderStore.sync(
                sharedPreferences,
                settingsPreferences,
                getPackageManager(),
                dashboardProfile.isPassenger());
        animateDesktopLayout();
    }

    /**
     * Клик по плитке-ярлыку приложения: Открыть обычной задачей, которую системный hook ужмёт в
     * окно.
     */
    private void onAppTileClick(String pkg) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            showSnack("Сервис не готов");
            return;
        }
        openAppOnDashboardDisplay(pkg);
    }

    /** Native already supports both physical displays through OPEN_ON_DISPLAY. */
    private void openAppOnDashboardDisplay(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        if (dashboardDisplayId == 0) {
            sendAppWindow(pkg);
        } else {
            launchAppOnDisplay(pkg, dashboardDisplayId);
        }
    }

    private void populateAppWidgetLauncher(View widgetView, AppWidgetStore.Entry entry) {
        ViewGroup list = widgetView.findViewById(R.id.appWidgetList);
        if (list == null) {
            return;
        }
        list.removeAllViews();

        PackageManager pm = getPackageManager();
        LayoutInflater inf = LayoutInflater.from(this);
        entry.ensureProfiles();
        // Child launchers consume holds before the tile root.
        View.OnLongClickListener dragLauncher =
                v -> {
                    splitTilesGrid.setEditing(true);
                    return true;
                };
        widgetView.setOnLongClickListener(dragLauncher);
        list.setOnLongClickListener(dragLauncher);

        if (CarPlayWidgetPlaceholder.show(
                widgetView, entry, () -> showEmbeddedAppWidget(widgetView, entry), dragLauncher)) {
            appearance.styleTile(widgetView);
            return;
        }

        for (int i = 0; i < entry.profiles.size(); i++) {
            final int index = i;
            AppWidgetStore.Profile profile = entry.profiles.get(i);
            View item = inf.inflate(R.layout.item_app_widget_launcher_item, list, false);
            ImageView ico = item.findViewById(R.id.launcherItemIcon);
            TextView label = item.findViewById(R.id.launcherItemLabel);

            String name = profile.packageName;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(profile.packageName, 0);
                name = pm.getApplicationLabel(ai).toString();
                ico.setImageDrawable(pm.getApplicationIcon(ai));
            } catch (Exception ignored) {
            }

            if (CarPlayTarget.isCarPlay(profile.packageName)) {
                name = CarPlayTarget.LABEL;
                ico.setImageResource(R.drawable.carplay_home_logo);
            }
            label.setText(name);
            item.setOnClickListener(
                    v -> {
                        entry.selectedProfile = index;
                        AppWidgetStore.update(sharedPreferences, entry);
                        launchEmbeddedAppWidget(
                                widgetView, entry, profile.packageName, profile.dpi);
                    });
            item.setOnLongClickListener(dragLauncher);
            list.addView(item);
        }

        View scroll = widgetView.findViewById(R.id.appWidgetLauncherScroll);
        if (scroll != null) {
            scroll.setVisibility(View.VISIBLE);
            scroll.setOnLongClickListener(dragLauncher);
        }

        View controls = widgetView.findViewById(R.id.appWidgetOverlayControls);
        if (controls != null) {
            controls.setVisibility(View.GONE);
        }

        View dragHandleLauncher = widgetView.findViewById(R.id.appWidgetDragHandleLauncher);
        if (dragHandleLauncher != null) {
            dragHandleLauncher.setOnLongClickListener(
                    view -> {
                        if (splitTilesGrid.isEditing()) {
                            return tileDragController.start(widgetView);
                        }
                        return dragLauncher.onLongClick(view);
                    });
        }
        View launcherControls = widgetView.findViewById(R.id.appWidgetLauncherControls);
        if (launcherControls != null) {
            launcherControls.setVisibility(View.VISIBLE);
            launcherControls.bringToFront();
        }
        View swapLauncher = widgetView.findViewById(R.id.appWidgetSwapLauncher);
        if (swapLauncher != null) {
            swapLauncher.setOnLongClickListener(dragLauncher);
            final String widgetId = entry.id;
            swapLauncher.setOnClickListener(v -> showSwapAppWidgetMenu(swapLauncher, widgetId));
        }

        refreshAppWidgetBadge(widgetView, entry.id);
        appearance.styleTile(widgetView);
    }

    /** Показать обозначение виджета (A, B, C…) в углу карточки. */
    private void refreshAppWidgetBadge(View widgetView, String widgetId) {
        if (widgetView == null) {
            return;
        }
        TextView badge = widgetView.findViewById(R.id.appWidgetBadge);
        if (badge == null) {
            return;
        }
        String designation = AppWidgetStore.designation(sharedPreferences, widgetId);
        if (designation.isEmpty()) {
            badge.setVisibility(View.GONE);
        } else {
            badge.setText(designation);
            badge.setVisibility(View.VISIBLE);
        }
    }

    /** Badge is only shown together with the controls that operate the embedded tile. */
    private void showWindowControls(View widget, boolean visible) {
        if (widget == null) return;
        View controls = widget.findViewById(R.id.appWidgetOverlayControls);
        if (controls != null) {
            controls.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) controls.bringToFront();
        }
        setAppWidgetBadgeVisible(widget, visible);
    }

    private void setAppWidgetBadgeVisible(View widgetView, boolean visible) {
        if (widgetView == null) {
            return;
        }
        TextView badge = widgetView.findViewById(R.id.appWidgetBadge);
        if (badge == null) {
            return;
        }
        boolean hasDesignation = badge.getText() != null && badge.getText().length() > 0;
        badge.setVisibility(visible && hasDesignation ? View.VISIBLE : View.GONE);
    }

    /** Переключить карточку в режим embedded VirtualDisplay (пакет из настроек виджета). */
    private void showEmbeddedAppWidget(View widgetView, AppWidgetStore.Entry entry) {
        AppWidgetStore.Profile profile = entry.selected();
        showEmbeddedAppWidget(widgetView, entry, profile.packageName, profile.dpi);
    }

    /** Переключить карточку в режим embedded VirtualDisplay для явно заданного пакета. */
    private void showEmbeddedAppWidget(
            View widgetView, AppWidgetStore.Entry entry, String packageName, int profileDpi) {
        showEmbeddedAppWidget(widgetView, entry, packageName, profileDpi, null);
    }

    /**
     * Встроить приложение в виджет. Если {@code moveFromWidgetId} задан, Native переносит в этот
     * виджет уже работающий экземпляр с исходного виджета, сохраняя состояние приложения.
     */
    private void showEmbeddedAppWidget(
            View widgetView,
            AppWidgetStore.Entry entry,
            String packageName,
            int profileDpi,
            String moveFromWidgetId) {
        showEmbeddedAppWidget(widgetView, entry, packageName, profileDpi, moveFromWidgetId, false);
    }

    private void showEmbeddedAppWidget(
            View widgetView,
            AppWidgetStore.Entry entry,
            String packageName,
            int profileDpi,
            String moveFromWidgetId,
            boolean retainedCarPlay) {
        if (CarPlayTarget.isCarPlay(packageName)
                && !ru.big.town.restoremode.carplay.CarPlayFeature.isEnabled(this)) {
            return;
        }
        if (CarPlayTarget.opensActivity(packageName, entry.width, entry.height)) {
            if (GlobalVars.isBound && GlobalVars.serviceMessenger != null) {
                openAppOnDashboardDisplay(packageName);
            } else {
                launchAppNormally(packageName);
            }
            return;
        }
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            showSnack("Сервис не готов");
            return;
        }

        if (CarPlayTarget.isCarPlay(packageName) && !retainedCarPlay) {
            carPlayWidgetGeneration++;
            releaseEmbeddedWidgetsOf(packageName);
        }
        widgetView.findViewById(R.id.carPlayWidgetPlaceholder).setVisibility(View.GONE);
        final String widgetId = entry.id;
        // Запущенный пакет и DPI виджета: их читают и переподключение поверхности, и кнопки,
        // поэтому после обмена экземплярами достаточно обновить эти карты.
        embeddedWidgetPackages.put(widgetId, packageName);
        embeddedWidgetDpi.put(widgetId, profileDpi);
        embeddedSuppressRelease.remove(widgetId);

        View scroll = widgetView.findViewById(R.id.appWidgetLauncherScroll);
        if (scroll != null) {
            scroll.setVisibility(View.GONE);
        }

        View launcherControls = widgetView.findViewById(R.id.appWidgetLauncherControls);
        if (launcherControls != null) {
            launcherControls.setVisibility(View.GONE);
        }

        View controls = widgetView.findViewById(R.id.appWidgetOverlayControls);
        if (controls != null) {
            controls.setVisibility(View.GONE);
            View closeBtn = widgetView.findViewById(R.id.appWidgetClose);
            if (closeBtn != null) {
                closeBtn.setVisibility(View.VISIBLE);
                closeBtn.setOnClickListener(
                        v -> {
                            AppWidgetSessionStore.forgetLaunch(
                                    sharedPreferences, currentWidgetSessionId(), widgetId);
                            releaseEmbeddedWidget(widgetId, widgetView);
                        });
            }
            View dragBtn = widgetView.findViewById(R.id.appWidgetDragHandle);
            if (dragBtn != null) {
                dragBtn.setVisibility(View.VISIBLE);
                dragBtn.setOnLongClickListener(
                        v -> {
                            if (splitTilesGrid.isEditing()) tileDragController.start(widgetView);
                            else splitTilesGrid.setEditing(true);
                            return true;
                        });
            }

            View expandBtn = widgetView.findViewById(R.id.appWidgetExpand);
            if (expandBtn != null) {
                expandBtn.setVisibility(View.VISIBLE);
                expandBtn.setOnClickListener(
                        v -> {
                            // Развернуть текущее приложение на весь экран (simpleLaunch)
                            String running = embeddedWidgetPackages.get(widgetId);
                            if (running == null) {
                                return;
                            }
                            // Native retires CarPlay tasks before releasing their VD.
                            if (!CarPlayTarget.isCarPlay(running))
                                releaseEmbeddedWidget(widgetId, widgetView);
                            openAppOnDashboardDisplay(running);
                        });
            }

            View swapBtn = widgetView.findViewById(R.id.appWidgetSwap);
            if (swapBtn != null) {
                swapBtn.setVisibility(View.VISIBLE);
                swapBtn.setOnClickListener(v -> showSwapAppWidgetMenu(swapBtn, widgetId));
            }

            View dpiPlus = widgetView.findViewById(R.id.appWidgetDpiPlus);
            if (dpiPlus != null) {
                dpiPlus.setOnClickListener(v -> changeAppWidgetDpi(widgetId, 1));
            }
            View dpiMinus = widgetView.findViewById(R.id.appWidgetDpiMinus);
            if (dpiMinus != null) {
                dpiMinus.setOnClickListener(v -> changeAppWidgetDpi(widgetId, -1));
            }
        }

        ViewGroup container = widgetView.findViewById(R.id.appWidgetRoot);
        if (container == null) {
            container = (ViewGroup) widgetView;
        }

        final boolean carPlayWindow = CarPlayTarget.isCarPlay(packageName);
        if (carPlayWindow) {
            // Clip the tile background as well as the projection's rounded corners.
            container.setOutlineProvider(
                    new ViewOutlineProvider() {
                        @Override
                        public void getOutline(View view, Outline outline) {
                            float radius =
                                    getResources()
                                            .getDimension(R.dimen.carplay_widget_corner_radius);
                            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                        }
                    });
            container.setClipToOutline(true);
        }

        // Перенос выполняется один раз — на первом же подключённом surface.
        final String[] pendingMoveFrom = {moveFromWidgetId};

        TextureView textureView = new TextureView(this);
        textureView.setOpaque(true);
        textureView.setClipToOutline(true);
        textureView.setOutlineProvider(
                new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        float radius =
                                carPlayWindow
                                        ? getResources()
                                                .getDimension(R.dimen.carplay_widget_corner_radius)
                                        : 18f * getResources().getDisplayMetrics().density;
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                    }
                });
        container.addView(
                textureView,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        embeddedWidgetSurfaces.put(widgetId, textureView);
        showWindowControls(widgetView, splitTilesGrid.isEditing());
        textureView.setOnLongClickListener(
                view -> {
                    splitTilesGrid.setEditing(true);
                    return true;
                });
        GestureDetector longClickDetector =
                new GestureDetector(
                        this,
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public void onLongPress(MotionEvent event) {
                                textureView.performLongClick();
                            }
                        });

        textureView.setSurfaceTextureListener(
                new TextureView.SurfaceTextureListener() {
                    @Override
                    public void onSurfaceTextureAvailable(
                            SurfaceTexture texture, int width, int height) {
                        if (!suspensionScreenResumed
                                || embeddedWidgetSurfaces.get(widgetId) != textureView) return;
                        texture.setDefaultBufferSize(width, height);
                        Surface output = new Surface(texture);
                        embeddedWidgetOutputs.put(widgetId, output);
                        String from = pendingMoveFrom[0];
                        if (from != null) {
                            pendingMoveFrom[0] = null;
                            sendEmbeddedMove(
                                    from,
                                    widgetId,
                                    embeddedWidgetPackages.get(widgetId),
                                    output,
                                    width,
                                    height);
                        } else {
                            sendEmbeddedSurface(
                                    widgetId,
                                    embeddedWidgetPackages.get(widgetId),
                                    runningDpi(widgetId),
                                    output,
                                    width,
                                    height);
                        }
                    }

                    @Override
                    public void onSurfaceTextureSizeChanged(
                            SurfaceTexture texture, int width, int height) {
                        if (!suspensionScreenResumed
                                || embeddedWidgetSurfaces.get(widgetId) != textureView) return;
                        texture.setDefaultBufferSize(width, height);
                        Surface output = embeddedWidgetOutputs.get(widgetId);
                        if (output != null) {
                            // Пакет берём из карты: после обмена экземплярами он мог смениться.
                            sendEmbeddedSurface(
                                    widgetId,
                                    embeddedWidgetPackages.get(widgetId),
                                    runningDpi(widgetId),
                                    output,
                                    width,
                                    height);
                        }
                    }

                    @Override
                    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                        if (embeddedWidgetSurfaces.get(widgetId) != textureView) return true;
                        embeddedWidgetSurfaces.remove(widgetId);
                        Surface output = embeddedWidgetOutputs.remove(widgetId);
                        if (output != null) {
                            output.release();
                        }
                        embeddedWidgetPackages.remove(widgetId);
                        embeddedWidgetDpi.remove(widgetId);
                        // При переносе дисплей уже переехал в другой виджет — освобождать его не
                        // нужно.
                        if (!embeddedSuppressRelease.remove(widgetId)) {
                            sendEmbeddedDetach(widgetId);
                        }
                        return true;
                    }

                    @Override
                    public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
                });
        View.OnTouchListener embeddedTouchListener =
                (v, event) -> {
                    longClickDetector.onTouchEvent(event);

                    // Проверяем, попал ли touch в область кнопок управления
                    if (event.getActionMasked() != MotionEvent.ACTION_CANCEL
                            && controls != null
                            && controls.getVisibility() == View.VISIBLE) {
                        int[] location = new int[2];
                        controls.getLocationOnScreen(location);
                        int touchX = (int) event.getRawX();
                        int touchY = (int) event.getRawY();
                        if (touchX >= location[0]
                                && touchX < location[0] + controls.getWidth()
                                && touchY >= location[1]
                                && touchY < location[1] + controls.getHeight()) {
                            // Touch попал в область кнопок - пропускаем дальше
                            return false;
                        }
                    }

                    boolean gestureFinished =
                            event.getActionMasked() == MotionEvent.ACTION_UP
                                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL;
                    v.getParent().requestDisallowInterceptTouchEvent(!gestureFinished);
                    sendEmbeddedTouch(widgetId, event);
                    return true;
                };
        textureView.setOnTouchListener(
                new ru.big.town.restoremode.carplay.CarPlayDashboardScrollTouchListener(
                        contentScroll,
                        () -> CarPlayTarget.isCarPlay(embeddedWidgetPackages.get(widgetId)),
                        embeddedTouchListener,
                        this::stopGridSnapAnimation,
                        () -> contentScroll.post(this::snapPageOnRelease)));

        if (controls != null) {
            controls.bringToFront();
        }
    }

    /** DPI запущенного в виджете приложения (0 — «Авто»). */
    private int runningDpi(String widgetId) {
        Integer dpi = embeddedWidgetDpi.get(widgetId);
        return dpi == null ? 0 : dpi;
    }

    /** Изменить DPI запущенного в виджете приложения на соседнее значение из списка. */
    private void changeAppWidgetDpi(String widgetId, int direction) {
        String runningPackage = embeddedWidgetPackages.get(widgetId);
        if (runningPackage == null || runningPackage.isEmpty()) {
            return;
        }
        AppWidgetStore.Entry entry = AppWidgetStore.find(sharedPreferences, widgetId);
        if (entry == null) {
            return;
        }
        int current = AppWidgetStore.normalizeDpi(runningDpi(widgetId));
        int index = 0;
        for (int i = 0; i < AppWidgetStore.DPI_VALUES.length; i++) {
            if (AppWidgetStore.DPI_VALUES[i] == current) {
                index = i;
                break;
            }
        }
        int next = Math.max(0, Math.min(AppWidgetStore.DPI_VALUES.length - 1, index + direction));
        if (next == index) {
            return;
        }
        int value = AppWidgetStore.DPI_VALUES[next];

        // A swap/move keeps widget configuration intact. Update only this package's profile;
        // a temporarily hosted app uses the per-app setting when launched here again.
        if (entry.setPackageDpi(runningPackage, value)) {
            AppWidgetStore.update(sharedPreferences, entry);
        } else {
            AppDpiStore.set(settingsPreferences, runningPackage, value);
        }

        // Переподключаем поверхность: Native пересчитывает плотность дисплея по новому DPI.
        embeddedWidgetDpi.put(widgetId, value);
        Surface output = embeddedWidgetOutputs.get(widgetId);
        if (output != null) {
            sendEmbeddedSurface(
                    widgetId,
                    runningPackage,
                    value,
                    output,
                    embeddedPixelWidth(widgetId),
                    embeddedPixelHeight(widgetId));
        }
        showSnack(value == 0 ? "DPI: Авто" : "DPI: " + value);
    }

    private void releaseEmbeddedWidget(String widgetId, View widgetView) {
        carPlayWidgetGeneration++;
        removeEmbeddedSurface(widgetId, widgetView);
        sendEmbeddedRelease(widgetId);
    }

    /**
     * Снять экран виджета, не освобождая дисплей в Native: экземпляр переносится в другой виджет.
     */
    private void detachEmbeddedWidget(String widgetId, View widgetView) {
        embeddedSuppressRelease.add(widgetId);
        removeEmbeddedSurface(widgetId, widgetView);
    }

    /** Убрать TextureView виджета и вернуть его лончер; очистить карты запущенного состояния. */
    private void removeEmbeddedSurface(String widgetId, View widgetView) {
        embeddedWidgetSurfaces.remove(widgetId);
        Surface output = embeddedWidgetOutputs.remove(widgetId);
        if (output != null) {
            output.release();
        }
        embeddedWidgetPackages.remove(widgetId);
        embeddedWidgetDpi.remove(widgetId);

        if (widgetView == null) {
            return;
        }
        ViewGroup container = widgetView.findViewById(R.id.appWidgetRoot);
        if (container == null) {
            container = (ViewGroup) widgetView;
        }

        container.setClipToOutline(false);
        container.setOutlineProvider(ViewOutlineProvider.BACKGROUND);

        // Удалить TextureView (он обычно последний добавленный)
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            View child = container.getChildAt(i);
            if (child instanceof TextureView) {
                container.removeViewAt(i);
            }
        }

        View controls = widgetView.findViewById(R.id.appWidgetOverlayControls);
        if (controls != null) {
            controls.setVisibility(View.GONE);
        }

        View expandBtn = widgetView.findViewById(R.id.appWidgetExpand);
        if (expandBtn != null) {
            expandBtn.setVisibility(View.GONE);
        }

        View closeBtn = widgetView.findViewById(R.id.appWidgetClose);
        if (closeBtn != null) {
            closeBtn.setVisibility(View.GONE);
        }

        // Возвращаем лончер
        AppWidgetStore.Entry entry = AppWidgetStore.find(sharedPreferences, widgetId);
        if (entry != null) {
            populateAppWidgetLauncher(widgetView, entry);
        }
    }

    /** Candidate for the exchange menu: a widget of this screen or of the other one. */
    private static final class SwapTarget {
        final DashboardActivity screen;
        final String widgetId;
        final String title;

        SwapTarget(DashboardActivity screen, String widgetId, String title) {
            this.screen = screen;
            this.widgetId = widgetId;
            this.title = title;
        }
    }

    /**
     * Кнопка «Обмен»: при одном виджете на своём экране цель очевидна, при нескольких или при
     * виджетах второго экрана — выбор во всплывающем меню. Обмен и перенос выполняются только для
     * запущенных экземпляров приложений.
     */
    private void showSwapAppWidgetMenu(View anchor, String widgetId) {
        List<SwapTarget> targets = swapTargets(widgetId);
        if (targets.isEmpty()) {
            showSnack("Нет другого виджета для обмена");
            return;
        }
        if (targets.size() == 1 && targets.get(0).screen == this) {
            onSwapAppWidgetChosen(widgetId, targets.get(0));
            return;
        }
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        for (int i = 0; i < targets.size(); i++) {
            menu.getMenu().add(0, i, i, "Обменять с " + targets.get(i).title);
        }
        menu.setOnMenuItemClickListener(
                item -> {
                    int index = item.getItemId();
                    if (index >= 0 && index < targets.size()) {
                        onSwapAppWidgetChosen(widgetId, targets.get(index));
                        return true;
                    }
                    return false;
                });
        menu.show();
    }

    /** Виджеты своего экрана плюс виджеты второго экрана, если он открыт. */
    private List<SwapTarget> swapTargets(String widgetId) {
        List<SwapTarget> targets = new ArrayList<>();
        for (AppWidgetStore.Entry entry : AppWidgetStore.load(sharedPreferences)) {
            if (!entry.id.equals(widgetId)) {
                targets.add(new SwapTarget(this, entry.id, appWidgetTitle(entry)));
            }
        }
        DashboardActivity other = otherScreen();
        if (other != null) {
            String suffix = " (" + other.screenLabel() + " экран)";
            for (AppWidgetStore.Entry entry : AppWidgetStore.load(other.sharedPreferences)) {
                // CarPlay widgets are not created on the passenger screen.
                if (CarPlayTarget.isCarPlay(entry.packageName)) {
                    continue;
                }
                targets.add(new SwapTarget(other, entry.id, other.appWidgetTitle(entry) + suffix));
            }
        }
        return targets;
    }

    /**
     * Обмен или перенос запущенных экземпляров: - оба виджета работают → экземпляры меняются
     * местами; - работает только текущий → предлагаем отправить его в другой виджет; - работает
     * только другой → переносим его экземпляр в текущий виджет.
     */
    private void onSwapAppWidgetChosen(String currentId, SwapTarget target) {
        if (target == null || currentId == null || currentId.equals(target.widgetId)) {
            return;
        }
        DashboardActivity screen = target.screen == null ? this : target.screen;
        boolean currentRunning = embeddedWidgetSurfaces.containsKey(currentId);
        if (screen == this) {
            boolean targetRunning = embeddedWidgetSurfaces.containsKey(target.widgetId);
            if (currentRunning && targetRunning) {
                swapAppWidgetInstances(currentId, target.widgetId);
            } else if (currentRunning) {
                confirmSendAppWidgetInstance(currentId, target);
            } else if (targetRunning) {
                moveAppWidgetInstance(target.widgetId, currentId);
            } else {
                showSnack("В этом виджете нет запущенного приложения");
            }
            return;
        }
        if (!checkOtherScreenReady(screen)) {
            return;
        }
        String runningPackage = embeddedWidgetPackages.get(currentId);
        if (runningPackage != null && CarPlayTarget.isCarPlay(runningPackage)) {
            showSnack("CarPlay нельзя перенести на " + screen.screenLabel() + " экран");
            return;
        }
        boolean targetRunning = screen.embeddedWidgetSurfaces.containsKey(target.widgetId);
        if (currentRunning && targetRunning) {
            swapAppWidgetInstancesAcrossScreen(screen, currentId, target.widgetId);
        } else if (currentRunning) {
            confirmSendAppWidgetInstance(currentId, target);
        } else if (targetRunning) {
            moveAppWidgetInstanceAcrossScreen(screen, target.widgetId, this, currentId);
        } else {
            showSnack("В этом виджете нет запущенного приложения");
        }
    }

    /** Второй экран должен быть открыт: его плитки создают поверхности для обмена. */
    private boolean checkOtherScreenReady(DashboardActivity screen) {
        if (screen != null
                && !screen.isFinishing()
                && !screen.isDestroyed()
                && screen.suspensionScreenResumed) {
            return true;
        }
        showSnack(
                "Откройте "
                        + (screen == null ? "второй" : screen.screenLabel())
                        + " экран, чтобы обменяться приложениями");
        return false;
    }

    /** Перед отправкой запущенного приложения в другой виджет спрашиваем подтверждение. */
    private void confirmSendAppWidgetInstance(String fromWidgetId, SwapTarget target) {
        DashboardActivity screen = target.screen == null ? this : target.screen;
        String designation = AppWidgetStore.designation(screen.sharedPreferences, target.widgetId);
        String title = designation.isEmpty() ? "другой виджет" : "виджет " + designation;
        if (screen != this) {
            title += " на " + screen.screenLabel() + " экране";
        }
        new android.app.AlertDialog.Builder(this)
                .setMessage("Отправить запущенное приложение в " + title + "?")
                .setPositiveButton(
                        "Отправить",
                        (dialog, which) -> {
                            if (screen == this) {
                                moveAppWidgetInstance(fromWidgetId, target.widgetId);
                            } else {
                                moveAppWidgetInstanceAcrossScreen(
                                        this, fromWidgetId, screen, target.widgetId);
                            }
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /**
     * Обмен экземплярами между экранами: поверхности принадлежат двум активностям, поэтому обе
     * передаются одним сообщением Native, а карты и сеансы обновляются на обоих экранах.
     */
    private void swapAppWidgetInstancesAcrossScreen(
            DashboardActivity other, String idA, String idB) {
        String packageA = embeddedWidgetPackages.get(idA);
        String packageB = other.embeddedWidgetPackages.get(idB);
        Surface surfaceA = embeddedWidgetOutputs.get(idA);
        Surface surfaceB = other.embeddedWidgetOutputs.get(idB);
        if (packageA == null
                || packageB == null
                || surfaceA == null
                || surfaceB == null
                || !surfaceA.isValid()
                || !surfaceB.isValid()) {
            showSnack("Для обмена нужны запущенные приложения в обоих виджетах");
            return;
        }
        if (CarPlayTarget.isCarPlay(packageA) || CarPlayTarget.isCarPlay(packageB)) {
            showSnack("CarPlay нельзя обменять между экранами");
            return;
        }
        int dpiA = runningDpi(idA);
        int dpiB = other.runningDpi(idB);
        sendEmbeddedSwap(
                idA,
                idB,
                surfaceA,
                surfaceB,
                embeddedPixelWidth(idA),
                embeddedPixelHeight(idA),
                dpiA,
                other.embeddedPixelWidth(idB),
                other.embeddedPixelHeight(idB),
                dpiB);
        embeddedWidgetPackages.put(idA, packageB);
        embeddedWidgetDpi.put(idA, dpiB);
        other.embeddedWidgetPackages.put(idB, packageA);
        other.embeddedWidgetDpi.put(idB, dpiA);
        saveWidgetSessionLaunch(idA, packageB, dpiB);
        other.saveWidgetSessionLaunch(idB, packageA, dpiA);
        showSnack("Экраны обменялись запущенными приложениями");
    }

    /**
     * Перенос между экранами: поверхность создаёт принимающий экран, Native переносит тот же
     * VirtualDisplay, исходный экран только снимает поверхность без освобождения дисплея.
     */
    private void moveAppWidgetInstanceAcrossScreen(
            DashboardActivity source, String fromId, DashboardActivity destination, String toId) {
        String packageName = source.embeddedWidgetPackages.get(fromId);
        View fromView = source.appWidgetTileViews.get(fromId);
        View toView = destination.appWidgetTileViews.get(toId);
        AppWidgetStore.Entry toEntry = AppWidgetStore.find(destination.sharedPreferences, toId);
        if (packageName == null || fromView == null || toView == null || toEntry == null) {
            showSnack("Не удалось перенести приложение");
            return;
        }
        int dpi = source.runningDpi(fromId);
        // Сначала встраиваем целевой виджет: поверхность появится асинхронно, и только затем
        // Native перенесёт на неё работающий дисплей.
        destination.showEmbeddedAppWidget(toView, toEntry, packageName, dpi, fromId);
        if (!destination.embeddedWidgetSurfaces.containsKey(toId)) {
            destination.removeEmbeddedSurface(toId, toView);
            showSnack("Не удалось перенести приложение на " + destination.screenLabel() + " экран");
            return;
        }
        destination.saveWidgetSessionLaunch(toId, packageName, dpi);
        AppWidgetSessionStore.forgetLaunch(
                source.sharedPreferences, source.currentWidgetSessionId(), fromId);
        source.detachEmbeddedWidget(fromId, fromView);
        showSnack("Приложение перенесено на " + destination.screenLabel() + " экран");
    }

    /** Меняем дисплеи местами: приложения продолжают работать, состояние сохраняется. */
    private void swapAppWidgetInstances(String idA, String idB) {
        String packageA = embeddedWidgetPackages.get(idA);
        String packageB = embeddedWidgetPackages.get(idB);
        if (packageA == null
                || packageB == null
                || !embeddedWidgetSurfaces.containsKey(idA)
                || !embeddedWidgetSurfaces.containsKey(idB)) {
            showSnack("Для обмена нужны запущенные приложения в обоих виджетах");
            return;
        }
        sendEmbeddedSwap(idA, idB);
        embeddedWidgetPackages.put(idA, packageB);
        embeddedWidgetPackages.put(idB, packageA);
        int dpiA = runningDpi(idA);
        embeddedWidgetDpi.put(idA, runningDpi(idB));
        embeddedWidgetDpi.put(idB, dpiA);
        AppWidgetSessionStore.saveLaunch(
                sharedPreferences,
                currentWidgetSessionId(),
                idA,
                embeddedWidgetPackages.get(idA),
                runningDpi(idA));
        AppWidgetSessionStore.saveLaunch(
                sharedPreferences,
                currentWidgetSessionId(),
                idB,
                embeddedWidgetPackages.get(idB),
                runningDpi(idB));
        showSnack("Виджеты обменялись запущенными приложениями");
    }

    /** Перенести работающий экземпляр в другой виджет с сохранением состояния приложения. */
    private void moveAppWidgetInstance(String fromWidgetId, String toWidgetId) {
        String packageName = embeddedWidgetPackages.get(fromWidgetId);
        View fromView = appWidgetTileViews.get(fromWidgetId);
        View toView = appWidgetTileViews.get(toWidgetId);
        AppWidgetStore.Entry toEntry = AppWidgetStore.find(sharedPreferences, toWidgetId);
        if (packageName == null || fromView == null || toView == null || toEntry == null) {
            showSnack("Не удалось перенести приложение");
            return;
        }
        int dpi = runningDpi(fromWidgetId);
        // Сначала встраиваем целевой виджет и просим Native перенести туда работающий дисплей,
        // и только затем снимаем исходный — без освобождения, иначе Native закроет приложение.
        showEmbeddedAppWidget(toView, toEntry, packageName, dpi, fromWidgetId);
        if (!embeddedWidgetSurfaces.containsKey(toWidgetId)) {
            return;
        }
        AppWidgetSessionStore.saveLaunch(
                sharedPreferences, currentWidgetSessionId(), toWidgetId, packageName, dpi);
        AppWidgetSessionStore.forgetLaunch(
                sharedPreferences, currentWidgetSessionId(), fromWidgetId);
        detachEmbeddedWidget(fromWidgetId, fromView);
        String designation = AppWidgetStore.designation(sharedPreferences, toWidgetId);
        showSnack(
                designation.isEmpty()
                        ? "Приложение перенесено"
                        : "Приложение перенесено в виджет " + designation);
    }

    private void sendEmbeddedSurface(
            String widgetId, String pkg, int dpi, Surface surface, int width, int height) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null || surface == null) {
            return;
        }
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        try {
            Message message = Message.obtain(null, MSG_SPLIT_LAUNCH_VD, 1, 0);
            Bundle data = new Bundle();
            data.putString("left", pkg);
            data.putString("widgetId", widgetId);
            data.putBoolean("embeddedSurface", true);
            data.putParcelable("surface", surface);
            data.putInt("width", width);
            data.putInt("height", height);
            data.putInt("leftDpi", AppWidgetStore.normalizeDpi(dpi));
            message.setData(data);
            message.replyTo = clientMessenger;
            GlobalVars.serviceMessenger.send(message);
            Log.i(
                    TAG,
                    "sendEmbeddedSurface widget="
                            + widgetId
                            + " pkg="
                            + pkg
                            + " size="
                            + width
                            + "x"
                            + height);
        } catch (RemoteException e) {
            Log.w(TAG, "sendEmbeddedSurface failed: " + e.getMessage());
        }
    }

    /**
     * Перенести уже работающий экземпляр приложения из одного виджета в другой, сохранив состояние:
     * Native переносит тот же VirtualDisplay на новый ключ и поверхность.
     */
    private void sendEmbeddedMove(
            String fromWidgetId,
            String toWidgetId,
            String pkg,
            Surface surface,
            int width,
            int height) {
        if (!GlobalVars.isBound
                || GlobalVars.serviceMessenger == null
                || surface == null
                || pkg == null
                || pkg.isEmpty()) {
            return;
        }
        try {
            Message message = Message.obtain(null, MSG_EMBEDDED_TRANSFER, 1, 0);
            Bundle data = new Bundle();
            data.putBoolean("embeddedMove", true);
            data.putString("fromWidgetId", fromWidgetId);
            data.putString("widgetId", toWidgetId);
            data.putString("package", pkg);
            data.putParcelable("surface", surface);
            data.putInt("width", width);
            data.putInt("height", height);
            data.putInt("dpi", AppWidgetStore.normalizeDpi(runningDpi(toWidgetId)));
            message.setData(data);
            message.replyTo = clientMessenger;
            GlobalVars.serviceMessenger.send(message);
            Log.i(TAG, "sendEmbeddedMove " + fromWidgetId + " -> " + toWidgetId + " pkg=" + pkg);
        } catch (RemoteException e) {
            Log.w(TAG, "sendEmbeddedMove failed: " + e.getMessage());
        }
    }

    /** Поменять местами работающие экземпляры двух виджетов; состояние приложений сохраняется. */
    private void sendEmbeddedSwap(String idA, String idB) {
        sendEmbeddedSwap(
                idA,
                idB,
                embeddedWidgetOutputs.get(idA),
                embeddedWidgetOutputs.get(idB),
                embeddedPixelWidth(idA),
                embeddedPixelHeight(idA),
                runningDpi(idA),
                embeddedPixelWidth(idB),
                embeddedPixelHeight(idB),
                runningDpi(idB));
    }

    /**
     * Обмен экземплярами между экранами: поверхности принадлежат разным активностям, поэтому обе
     * передаются в одном сообщении Native.
     */
    private void sendEmbeddedSwap(
            String idA,
            String idB,
            Surface surfaceA,
            Surface surfaceB,
            int widthA,
            int heightA,
            int dpiA,
            int widthB,
            int heightB,
            int dpiB) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            return;
        }
        if (surfaceA == null || surfaceB == null) {
            return;
        }
        try {
            Message message = Message.obtain(null, MSG_EMBEDDED_TRANSFER, 1, 0);
            Bundle data = new Bundle();
            data.putBoolean("embeddedSwap", true);
            data.putString("widgetId", idA);
            data.putString("widgetId2", idB);
            data.putParcelable("surface", surfaceA);
            data.putParcelable("surface2", surfaceB);
            data.putInt("width", widthA);
            data.putInt("height", heightA);
            data.putInt("dpi", dpiA);
            data.putInt("width2", widthB);
            data.putInt("height2", heightB);
            data.putInt("dpi2", dpiB);
            message.setData(data);
            message.replyTo = clientMessenger;
            GlobalVars.serviceMessenger.send(message);
            Log.i(TAG, "sendEmbeddedSwap " + idA + " <-> " + idB);
        } catch (RemoteException e) {
            Log.w(TAG, "sendEmbeddedSwap failed: " + e.getMessage());
        }
    }

    private int embeddedPixelWidth(String widgetId) {
        TextureView view = embeddedWidgetSurfaces.get(widgetId);
        return view == null ? 0 : view.getWidth();
    }

    private int embeddedPixelHeight(String widgetId) {
        TextureView view = embeddedWidgetSurfaces.get(widgetId);
        return view == null ? 0 : view.getHeight();
    }

    private void sendEmbeddedTouch(String widgetId, MotionEvent event) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            return;
        }
        try {
            Message message = Message.obtain(null, MSG_SPLIT_LAUNCH_VD, 1, 0);
            Bundle data = new Bundle();
            data.putString("widgetId", widgetId);
            data.putBoolean("embeddedTouch", true);
            data.putParcelable("event", MotionEvent.obtain(event));
            message.setData(data);
            message.replyTo = clientMessenger;
            GlobalVars.serviceMessenger.send(message);
        } catch (RemoteException e) {
            Log.w(TAG, "sendEmbeddedTouch failed: " + e.getMessage());
        }
    }

    private void sendEmbeddedRelease(String widgetId) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            return;
        }
        try {
            Message message = Message.obtain(null, MSG_SPLIT_LAUNCH_VD, 1, 0);
            Bundle data = new Bundle();
            data.putString("widgetId", widgetId);
            data.putBoolean("embeddedRelease", true);
            message.setData(data);
            message.replyTo = clientMessenger;
            GlobalVars.serviceMessenger.send(message);
        } catch (RemoteException e) {
            Log.w(TAG, "sendEmbeddedRelease failed: " + e.getMessage());
        }
    }

    private void sendEmbeddedDetach(String widgetId) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) return;
        try {
            Message message = Message.obtain(null, CarPlayWidgetProtocol.MESSAGE);
            Bundle data = new Bundle();
            data.putString("widgetId", widgetId);
            data.putBoolean(CarPlayWidgetProtocol.DETACH, true);
            message.setData(data);
            GlobalVars.serviceMessenger.send(message);
        } catch (RemoteException error) {
            Log.w(TAG, "sendEmbeddedDetach failed: " + error.getMessage());
        }
    }

    private void detachEmbeddedSurfaces() {
        for (String widgetId : new ArrayList<>(embeddedWidgetSurfaces.keySet())) {
            sendEmbeddedDetach(widgetId);
        }
        embeddedWidgetSurfaces.clear();
        for (Surface output : embeddedWidgetOutputs.values()) output.release();
        embeddedWidgetOutputs.clear();
        embeddedWidgetPackages.clear();
        embeddedWidgetDpi.clear();
        embeddedSuppressRelease.clear();
    }

    private void requestCarPlayWidgets() {
        if (!suspensionScreenResumed
                || destroyed
                || !ru.big.town.restoremode.carplay.CarPlayFeature.isEnabled(this)
                || !GlobalVars.isBound
                || GlobalVars.serviceMessenger == null) return;
        try {
            Message message = Message.obtain(null, CarPlayWidgetProtocol.MESSAGE);
            Bundle data = new Bundle();
            data.putBoolean(CarPlayWidgetProtocol.QUERY, true);
            data.putLong(CarPlayWidgetProtocol.GENERATION, ++carPlayWidgetGeneration);
            message.setData(data);
            message.replyTo = carPlayWidgetClient;
            GlobalVars.serviceMessenger.send(message);
        } catch (RemoteException error) {
            Log.w(TAG, "requestCarPlayWidgets failed: " + error.getMessage());
        }
    }

    private void restoreCarPlayWidgets(Bundle data) {
        if (!suspensionScreenResumed
                || destroyed
                || !ru.big.town.restoremode.carplay.CarPlayFeature.isEnabled(this)
                || data == null
                || data.getLong(CarPlayWidgetProtocol.GENERATION) != carPlayWidgetGeneration)
            return;
        ArrayList<String> widgets = data.getStringArrayList(CarPlayWidgetProtocol.WIDGETS);
        Bundle dpi = data.getBundle(CarPlayWidgetProtocol.DPI);
        if (widgets == null) return;
        for (String id : widgets) {
            if (!dashboardProfile.ownsWidgetId(id)) {
                // Both physical screens query the same Native registry.
                continue;
            }
            AppWidgetStore.Entry entry = AppWidgetStore.find(sharedPreferences, id);
            View view = appWidgetTileViews.get(id);
            if (entry == null
                    || CarPlayTarget.opensActivity(CarPlayTarget.ID, entry.width, entry.height)) {
                sendEmbeddedRelease(
                        id); // The user deleted the widget or turned it into a shortcut.
            } else if (view != null && !embeddedWidgetSurfaces.containsKey(id)) {
                showEmbeddedAppWidget(
                        view,
                        entry,
                        CarPlayTarget.ID,
                        dpi != null ? dpi.getInt(id, entry.dpi) : entry.dpi,
                        null,
                        true);
                Log.i(TAG, "CarPlay widget restored widget=" + id);
            }
        }
        // Restore retained surfaces before considering a fresh launch.
        if (com.shilapi.xcertplay.VoyahCarPlayIntegration.isProjectionOnMainDisplay()) return;
        for (Map.Entry<String, View> tile : appWidgetTileViews.entrySet()) {
            AppWidgetStore.Entry entry = AppWidgetStore.find(sharedPreferences, tile.getKey());
            if (entry != null
                    && entry.autoStart
                    && CarPlayTarget.isCarPlay(entry.packageName)
                    && !CarPlayTarget.opensActivity(entry.packageName, entry.width, entry.height)
                    && tile.getValue().isShown()
                    && !embeddedWidgetSurfaces.containsKey(entry.id)) {
                showEmbeddedAppWidget(tile.getValue(), entry);
            }
        }
    }

    private void launchAppNormally(String packageName) {
        if (CarPlayTarget.isCarPlay(packageName)) carPlayWidgetGeneration++;
        launchAppOnDisplay(packageName, dashboardDisplayId);
    }

    /** Native трактует пустой right как отдельную physical task целевого пакета. */
    private void sendAppWindow(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        int dpi = AppDpiStore.get(settingsPreferences, pkg);
        try {
            if (CarPlayTarget.isCarPlay(pkg)) {
                carPlayWidgetGeneration++;
                detachCarPlayWidgetsForWindow();
            }
            Message m = Message.obtain(null, MSG_SPLIT_LAUNCH_VD, 1, 0);
            Bundle b = new Bundle();
            b.putString("left", pkg);
            b.putString("right", ""); // пусто = не VD, а physical task + системный frame clamp
            b.putInt("leftDpi", dpi); // hook использует зеркальный Settings.Global per-package DPI
            b.putInt("rightDpi", 0);
            if (CarPlayTarget.isCarPlay(pkg))
                b.putInt("displayId", getWindowManager().getDefaultDisplay().getDisplayId());
            m.setData(b);
            m.replyTo = clientMessenger;
            GlobalVars.serviceMessenger.send(m);
            Log.i(TAG, "sendAppWindow " + pkg);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /**
     * Клик по плитке сплита: открываем VD-хост (per-app DPI, живой ресайз, свап). Закрытие — в
     * самом хосте.
     */
    private void onSplitTileClick(SplitStore.Preset preset) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            showSnack("Сервис не готов");
            return;
        }
        sendSplitVd(preset);
    }

    /** Индекс пресета в сохранённом списке — по нему Native вернёт выставленную рукой пропорцию. */
    private int presetIndex(SplitStore.Preset preset) {
        List<SplitStore.Preset> all = SplitStore.load(settingsPreferences);
        for (int i = 0; i < all.size(); i++) {
            SplitStore.Preset p = all.get(i);
            if (p.l.equals(preset.l) && p.r.equals(preset.r) && p.ratio == preset.ratio) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Запуск сплита на VirtualDisplay: пакеты + соотношение + per-app DPI (из {@link AppDpiStore}).
     */
    private void sendSplitVd(SplitStore.Preset preset) {
        if (preset.l == null || preset.l.isEmpty() || preset.r == null || preset.r.isEmpty()) {
            return;
        }
        int lDpi = AppDpiStore.get(settingsPreferences, preset.l);
        int rDpi = AppDpiStore.get(settingsPreferences, preset.r);
        try {
            Message m = Message.obtain(null, MSG_SPLIT_LAUNCH_VD, preset.ratio, 0);
            Bundle b = new Bundle();
            b.putString("left", preset.l);
            b.putString("right", preset.r);
            b.putInt("leftDpi", lDpi);
            b.putInt("rightDpi", rDpi);
            // Изменяемая пропорция: доля левого окна + индекс пресета, чтобы Native вернул новое
            // значение обратно (SPLIT_RATIO_SAVE) и оно пережило перезапуск сплита.
            b.putBoolean("resizable", preset.resizable);
            b.putFloat("split", SplitStore.leftFraction(preset));
            b.putInt("presetIdx", presetIndex(preset));
            b.putString("presetId", preset.id);
            m.setData(b);
            m.replyTo = clientMessenger;
            GlobalVars.serviceMessenger.send(m);
            Log.i(
                    TAG,
                    "sendSplitVd left="
                            + preset.l
                            + " right="
                            + preset.r
                            + " ratio="
                            + preset.ratio
                            + " lDpi="
                            + lDpi
                            + " rDpi="
                            + rDpi);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        splitTilesGrid.setEditing(false);
        cancelGridSnap();
        dashboardOpening = false;
        suspensionScreenResumed = false;
        if (carPlayFeatureReceiverRegistered) {
            unregisterReceiver(carPlayFeatureReceiver);
            carPlayFeatureReceiverRegistered = false;
        }
        carPlayWidgetGeneration++;
        systemWidgets.setResumed(false);
        mediaWidgets.setActive(false);
        watchSuspension();
        watchEnergyWidgets();
        energyWidgetState = new Bundle();
        tripTimerReceived = false;
        suspensionState = new Bundle();
        detachEmbeddedSurfaces();
        try {
            unregisterReceiver(tripReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(batteryHeatReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(settingSyncReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(powerHoldStatusReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(embeddedLeftReceiver);
        } catch (Exception ignored) {
        }
        uiHandler.removeCallbacks(tripTick);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        unregisterLiveDashboard();
        settingsPreferences.unregisterOnSharedPreferenceChangeListener(appearancePreferences);
        appearance.close();
        systemWidgets.close();
        mediaWidgets.close();
        recirculationWidget.close();
        fuelFlapWidget.close();
        headlightWidget.close();
        brightnessWidget.close();
        cabinTemperatureWidget.close();
        radioWidget.close();
        if (tileDragController != null) {
            tileDragController.cancel();
        }
        cancelGridSnap();
        uiHandler.removeCallbacks(suspensionUnavailable);
        uiHandler.removeCallbacks(tripTick);
        releaseMessengerBinding("onDestroy");
        super.onDestroy();
    }
}
