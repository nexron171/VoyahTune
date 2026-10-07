package ru.big.town.restoremode;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.junit.*;
import org.junit.runner.RunWith;
import ru.big.town.common.*;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.allOf;
import static androidx.test.espresso.assertion.ViewAssertions.matches;

@RunWith(AndroidJUnit4.class)
public class NewFeaturesIntegrationTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Context app, test;
    private SharedPreferences prefs;
    private Messenger mock, realNative;
    private ServiceConnection connection, realConnection;
    private ActivityScenario<IntegrationMainActivity> dashboard;
    private ActivityScenario<IntegrationTaskManagerActivity> tasks;
    private AdvanceActivity settings;
    private final List<Message> received = new ArrayList<>();
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        synchronized(received) { received.add(Message.obtain(message)); received.notifyAll(); }
        return true;
    }));

    @Before public void setUp() throws Exception {
        assertTrue("Emulator only", Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"));
        app = instrumentation.getTargetContext(); test = instrumentation.getContext();
        prefs = app.getSharedPreferences("DrivePreferences", 0);
        prefs.edit().clear().putBoolean("show_energyWidget", true).putBoolean("voiceEnabled", false).commit();
        CountDownLatch ready = new CountDownLatch(1);
        connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) { mock = new Messenger(binder); ready.countDown(); }
            public void onServiceDisconnected(ComponentName name) { mock = null; }
        };
        assertTrue(test.bindService(new Intent().setClassName(test.getPackageName(), MockNativeService.class.getName()), connection, Context.BIND_AUTO_CREATE));
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        Bundle reset = new Bundle(); reset.putBoolean("resetTasks", true); control(mock, reset);
    }
    @After public void tearDown() {
        if(settings != null) instrumentation.runOnMainSync(settings::finish);
        if(tasks != null) tasks.close();
        if(dashboard != null) dashboard.close();
        if(realConnection != null) app.unbindService(realConnection);
        if(connection != null) test.unbindService(connection);
    }
    private Message await(Predicate<Message> predicate) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        synchronized(received) {
            while(true) {
                for(int i = 0; i < received.size(); i++) if(predicate.test(received.get(i))) return received.remove(i);
                long left = deadline - SystemClock.elapsedRealtime();
                if(left <= 0) throw new AssertionError("Missing IPC message: " + received);
                received.wait(left);
            }
        }
    }
    private Bundle request(int what) throws Exception {
        return await(m -> m.what == 9001 && m.getData().getInt("what", -1) == what).getData();
    }
    private Bundle operation(String op) throws Exception {
        return await(m -> m.what == 9001 && op.equals(m.getData().getString("op"))).getData();
    }
    private void control(Messenger service, Bundle data) throws Exception {
        Message message = Message.obtain(null, 9000); message.replyTo = replies; message.setData(data); service.send(message);
        await(m -> m.what == 9002);
    }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while(!condition.getAsBoolean()) { if(SystemClock.elapsedRealtime() > deadline) throw new AssertionError("UI timed out"); Thread.sleep(30); }
    }
    private View find(View root, Predicate<View> predicate) {
        if(predicate.test(root)) return root;
        if(root instanceof ViewGroup) for(int i = 0; i < ((ViewGroup)root).getChildCount(); i++) {
            View result = find(((ViewGroup)root).getChildAt(i), predicate); if(result != null) return result;
        }
        return null;
    }
    private View textView(String text) {
        View view = find(settings.findViewById(R.id.pageScenarios), v -> v instanceof TextView && text.contentEquals(((TextView)v).getText()));
        assertNotNull("Missing control: " + text, view); return view;
    }
    private View described(String label) {
        View view = find(settings.findViewById(R.id.pageScenarios), v -> label.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription()));
        assertNotNull("Missing control: " + label, view); return view;
    }
    private void clickText(String text) { instrumentation.runOnMainSync(() -> assertTrue(textView(text).performClick())); }
    private void clickDescription(String text) { instrumentation.runOnMainSync(() -> described(text).performClick()); }
    private void launchDashboard(boolean vehicle) throws Exception {
        dashboard = ActivityScenario.launch(new Intent(app, IntegrationMainActivity.class).putExtra("realNativeWithMockVehicle", vehicle));
        request(EnergyWidgetProtocol.WATCH);
    }
    private void openSettings() {
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(AdvanceActivity.class.getName(), null, false);
        dashboard.onActivity(a -> a.startActivity(new Intent(a, AdvanceActivity.class).putExtra(AdvanceActivity.EXTRA_SECTION, AdvanceActivity.SECTION_SCENARIOS)));
        settings = (AdvanceActivity)monitor.waitForActivityWithTimeout(5000);
        instrumentation.removeMonitor(monitor); assertNotNull(settings);
        instrumentation.waitForIdleSync();
    }
    private void connectRealNative() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        realConnection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) { realNative = new Messenger(binder); ready.countDown(); }
            public void onServiceDisconnected(ComponentName name) { realNative = null; }
        };
        assertTrue(app.bindService(new Intent().setClassName("ru.big.town.anative", "ru.big.town.anative.NativeContractService"), realConnection, Context.BIND_AUTO_CREATE));
        assertTrue(ready.await(5, TimeUnit.SECONDS)); Bundle reset = new Bundle(); reset.putBoolean("reset", true); control(realNative, reset);
    }
    private void launchTasks(boolean vehicle) throws Exception {
        tasks = ActivityScenario.launch(new Intent(app, IntegrationTaskManagerActivity.class).putExtra("realNativeWithMockVehicle", vehicle));
        request(TaskManagerProtocol.REQUEST); waitFor(() -> taskCount() > 0);
    }
    private int taskCount() { int[] count = {0}; tasks.onActivity(a -> count[0] = ((LinearLayout)a.findViewById(R.id.taskManagerCards)).getChildCount()); return count[0]; }
    private void taskClick(String pkg, int id) {
        tasks.onActivity(a -> { View card = a.findViewById(R.id.taskManagerCards).findViewWithTag(pkg); assertNotNull(card); assertTrue(card.findViewById(id).performClick()); });
    }
    private boolean taskPinned(String pkg) {
        boolean[] pinned = {false}; tasks.onActivity(a -> { View card = a.findViewById(R.id.taskManagerCards).findViewWithTag(pkg); pinned[0] = card != null && card.findViewById(R.id.taskCardPin).isSelected(); }); return pinned[0];
    }
    private void screenshot(String name) throws Exception {
        if(!"true".equals(InstrumentationRegistry.getArguments().getString("screenshots"))) return;
        instrumentation.waitForIdleSync(); Thread.sleep(650);
        File dir = new File(app.getFilesDir(), "integration-screenshots"); assertTrue(dir.isDirectory() || dir.mkdirs());
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try(FileOutputStream stream = new FileOutputStream(new File(dir, name + ".png"))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
        bitmap.recycle();
    }

    @Test public void scenarioEditorPersistsControlsDialogsAndSendsTestRequest() throws Exception {
        launchDashboard(false); openSettings();
        clickText("Добавить сценарий"); assertEquals(1, ScenarioStore.load(prefs).size());
        instrumentation.runOnMainSync(() -> ((EditText)described("Название сценария")).setText("Утро"));
        clickText("Сохранить"); assertEquals("Утро", ScenarioStore.load(prefs).get(0).name);
        clickDescription("Показывать плитку сценария на главном экране"); assertTrue(ScenarioStore.load(prefs).get(0).showTile);
        clickText("Селектор"); onView(withText("D — движение")).inRoot(isDialog()).perform(click());
        assertEquals("D", ScenarioStore.load(prefs).get(0).triggers.get(0).value);
        clickText("Заряд"); onView(withText("не менее (≥)")).inRoot(isDialog()).perform(click());
        onView(withText("Добавить")).inRoot(isDialog()).perform(click());
        assertEquals(50, ScenarioStore.load(prefs).get(0).conditions.get(0).value);
        clickText("Добавить паузу"); onView(withContentDescription("Пауза в секундах")).perform(replaceText("2"), closeSoftKeyboard());
        onView(withText("Добавить")).inRoot(isDialog()).perform(click());
        clickText("Добавить действие"); onView(allOf(withText(startsWith("Окна")), isClickable())).inRoot(isDialog()).perform(click());
        onView(allOf(withText(startsWith("Окна")), isClickable())).inRoot(isDialog()).perform(click());
        String title = VoiceCommands.load(app).stream().filter(c -> "windows:driver:open".equals(c.action)).findFirst().get().title;
        onView(withText(title)).inRoot(isDialog()).perform(click());
        assertEquals(2, ScenarioStore.load(prefs).get(0).steps.size());
        clickDescription("Поднять действие 2"); assertEquals("windows:driver:open", ScenarioStore.load(prefs).get(0).steps.get(0).value);
        clickText("Тест"); Bundle command = request(ScenarioProtocol.MSG_RUN);
        assertTrue(command.getBoolean(ScenarioProtocol.EXTRA_TEST)); assertEquals(ScenarioStore.load(prefs).get(0).id, command.getString(ScenarioProtocol.EXTRA_ID));
        synchronized(received) { received.removeIf(m -> m.what == 9001 && m.getData().getInt("what") == 1); }
        instrumentation.runOnMainSync(() -> settings.onButtonClickApply(null)); Thread.sleep(150);
        synchronized(received) { assertFalse(received.stream().anyMatch(m -> m.what == 9001 && m.getData().getInt("what") == 1)); }
        clickDescription("Удалить действие 2"); assertEquals(1, ScenarioStore.load(prefs).get(0).steps.size());
        clickDescription("Включить сценарий Утро"); assertFalse(ScenarioStore.load(prefs).get(0).enabled);
        clickDescription("Удалить сценарий Утро"); assertTrue(ScenarioStore.load(prefs).isEmpty());
    }

    @Test public void scenarioSettingsBroadcastReachesRealNativeAndVehicleResponds() throws Exception {
        connectRealNative(); launchDashboard(true); openSettings();
        ScenarioStore.Scenario scenario = ScenarioStore.create(prefs, "Проверка окон");
        ScenarioStore.Step step = new ScenarioStore.Step(); step.value = "windows:driver:close"; scenario.steps.add(step);
        ScenarioStore.update(prefs, scenario);
        instrumentation.runOnMainSync(() -> { SplitConfigSync.pushScenarios(settings, prefs); settings.findViewById(R.id.navScenarios).performClick(); });
        await(m -> m.what == 9001 && "scenarioConfig".equals(m.getData().getString("op")) && m.getData().getString("text", "").contains(scenario.id));
        clickText("Настроить"); clickText("Тест"); request(ScenarioProtocol.MSG_RUN);
        assertEquals("windows:driver:close", operation("scenarioAction").getString("text"));
        assertNotNull(operation("oemWrite").getString("field")); assertEquals(1, operation("scenarioActionResult").getInt("value"));
    }

    @Test public void quickScenarioLauncherUsesManualContractAndTogglesPersist() throws Exception {
        ScenarioStore.Scenario scenario = ScenarioStore.create(prefs, "Домой");
        launchDashboard(false); dashboard.onActivity(a -> a.onScenariosCard(null));
        onView(withText("Домой")).inRoot(isDialog()).perform(click());
        Bundle request = request(ScenarioProtocol.MSG_RUN); assertEquals(scenario.id, request.getString(ScenarioProtocol.EXTRA_ID)); assertFalse(request.getBoolean(ScenarioProtocol.EXTRA_TEST));
        dashboard.onActivity(a -> a.onScenariosCard(null));
        onView(withContentDescription("Включить сценарий")).inRoot(isDialog()).check(matches(isChecked())).perform(click()).check(matches(not(isChecked())));
        assertFalse(prefs.getString(ScenarioProtocol.KEY_SCENARIOS, ""), ScenarioStore.find(prefs, scenario.id).enabled);
        onView(withText("Закрыть")).inRoot(isDialog()).perform(click());
    }

    @Test public void taskManagerConsumesCallbacksAndSendsPinSwitchCloseAndCloseAll() throws Exception {
        launchTasks(false); assertEquals(6, taskCount());
        taskClick("mock.music", R.id.taskCardPin); Bundle pin = request(TaskManagerProtocol.PIN);
        assertEquals("mock.music", pin.getString(TaskManagerProtocol.PACKAGE)); assertTrue(pin.getBoolean(TaskManagerProtocol.PINNED));
        waitFor(() -> taskPinned("mock.music"));
        taskClick("mock.navigation", R.id.taskCardIcon); assertEquals("mock.navigation", request(TaskManagerProtocol.SWITCH).getString(TaskManagerProtocol.PACKAGE));
        taskClick("mock.navigation", R.id.taskCardClose); request(TaskManagerProtocol.CLOSE); waitFor(() -> taskCount() == 5);
        tasks.onActivity(a -> a.findViewById(R.id.taskCloseAll).performClick()); request(TaskManagerProtocol.CLOSE_ALL);
        waitFor(() -> tasks.getState() == androidx.lifecycle.Lifecycle.State.DESTROYED);
    }

    @Test public void taskManagerHandlesEmptyShortRepliesAndServiceReconnect() throws Exception {
        launchTasks(false);
        Bundle data = new Bundle(); data.putBoolean("shortTaskLabels", true); data.putBoolean("emitTasks", true); control(mock, data);
        waitFor(() -> { boolean[] ok = {false}; tasks.onActivity(a -> { View card = a.findViewById(R.id.taskManagerCards).findViewWithTag("mock.music"); ok[0] = card != null && "mock.music".contentEquals(((TextView)card.findViewById(R.id.taskCardLabel)).getText()); }); return ok[0]; });
        data = new Bundle(); data.putBoolean("emptyTasks", true); data.putBoolean("emitTasks", true); control(mock, data);
        waitFor(() -> taskCount() == 0);
        tasks.onActivity(a -> { assertEquals(View.VISIBLE, a.findViewById(R.id.taskManagerEmpty).getVisibility()); assertEquals("Нет запущенных приложений", ((TextView)a.findViewById(R.id.taskManagerEmpty)).getText().toString()); });
        Messenger old = mock; data = new Bundle(); data.putBoolean("die", true); control(mock, data);
        waitFor(() -> mock != null && !mock.getBinder().equals(old.getBinder())); control(mock, new Bundle());
        request(TaskManagerProtocol.REQUEST); waitFor(() -> taskCount() == 6);
    }

    @Test public void taskManagerShowsLoadingAndKeepsUnavailableStateAfterRecreation() throws Exception {
        Bundle data = new Bundle(); data.putBoolean("noTaskReply", true); control(mock, data);
        tasks = ActivityScenario.launch(new Intent(app, IntegrationTaskManagerActivity.class));
        request(TaskManagerProtocol.REQUEST);
        tasks.onActivity(a -> assertEquals("Загрузка…", ((TextView)a.findViewById(R.id.taskManagerEmpty)).getText().toString()));
        data.putBoolean("noTaskReply", false); data.putBoolean("emitTasks", true); control(mock, data);
        waitFor(() -> taskCount() == 6); tasks.close();
        tasks = ActivityScenario.launch(new Intent(app, IntegrationTaskManagerActivity.class).putExtra("unavailableNative", true));
        tasks.recreate();
        tasks.onActivity(a -> assertEquals("Сервис автомобиля недоступен", ((TextView)a.findViewById(R.id.taskManagerEmpty)).getText().toString()));
        screenshot("07-task-manager-unavailable");
    }

    @Test public void taskManagerRoundTripUsesRealNativeWithMockAndroidOperations() throws Exception {
        connectRealNative(); launchTasks(true); assertEquals(4, taskCount());
        taskClick("mock.music", R.id.taskCardPin); request(TaskManagerProtocol.PIN); waitFor(() -> taskPinned("mock.music"));
        taskClick("mock.navigation", R.id.taskCardIcon); assertEquals("mock.navigation", operation("taskLaunch").getString("text"));
        taskClick("mock.navigation", R.id.taskCardClose); operation("taskRelease"); operation("taskStop"); waitFor(() -> taskCount() == 3);
    }

    @Test public void newScreensKeepSettingsGeometryAndCaptureRepresentativeStates() throws Exception {
        ScenarioStore.Scenario morning = ScenarioStore.create(prefs, "Комфортное утро");
        morning.showTile = true;
        morning.triggers.add(new ScenarioStore.Trigger(ScenarioProtocol.TRIGGER_DOOR, ScenarioProtocol.DOOR_DRIVER, ScenarioProtocol.EDGE_OPEN));
        ScenarioStore.Condition cold = new ScenarioStore.Condition(); cold.type = ScenarioProtocol.CONDITION_TEMP_OUT; cold.value = 12; morning.conditions.add(cold);
        ScenarioStore.Step heat = new ScenarioStore.Step(); heat.value = "seat:driver:heat:2"; morning.steps.add(heat);
        ScenarioStore.Step pause = new ScenarioStore.Step(); pause.type = ScenarioProtocol.STEP_PAUSE; pause.seconds = 5; morning.steps.add(pause);
        ScenarioStore.Step appStep = new ScenarioStore.Step(); appStep.value = "drive:COMFORT"; morning.steps.add(appStep);
        ScenarioStore.update(prefs, morning);
        ScenarioStore.Scenario dark = ScenarioStore.create(prefs, "Вечерняя поездка");
        dark.triggers.add(new ScenarioStore.Trigger(ScenarioProtocol.TRIGGER_LIGHT, ScenarioProtocol.LIGHT_DARK, ""));
        ScenarioStore.Step lights = new ScenarioStore.Step(); lights.value = "headlights:on"; dark.steps.add(lights); ScenarioStore.update(prefs, dark);
        ScenarioStore.Scenario parked = ScenarioStore.create(prefs, "После парковки"); parked.enabled = false; ScenarioStore.update(prefs, parked);
        launchDashboard(false); openSettings();
        instrumentation.runOnMainSync(() -> {
            assertEquals("Сценарии", ((TextView)settings.findViewById(R.id.sectionTitle)).getText().toString());
            assertTrue(settings.findViewById(R.id.navScenarios).isSelected());
            assertEquals(Math.round(132 * app.getResources().getDisplayMetrics().density), settings.findViewById(R.id.scenariosSettingsContent).getPaddingTop());
        });
        screenshot("01-scenarios");
        instrumentation.runOnMainSync(() -> {
            View card = settings.findViewById(R.id.pageScenarios).findViewWithTag("scenario:" + morning.id);
            View expand = find(card, v -> v instanceof Button && "Настроить".contentEquals(((TextView)v).getText())); assertNotNull(expand); expand.performClick();
        });
        screenshot("02-scenario-editor");
        instrumentation.runOnMainSync(() -> ((ScrollView)settings.findViewById(R.id.pageScenarios)).smoothScrollTo(0, 520));
        Thread.sleep(350); screenshot("03-scenario-actions");
        instrumentation.runOnMainSync(() -> ((ScrollView)settings.findViewById(R.id.pageScenarios)).smoothScrollTo(0, 800));
        screenshot("03b-scenario-steps");
        clickText("Добавить действие"); screenshot("04-scenario-action-picker");
        onView(withText("Отмена")).inRoot(isDialog()).perform(click());
        instrumentation.runOnMainSync(settings::finish); settings = null;
        launchTasks(false);
        tasks.onActivity(a -> {
            LinearLayout cards = a.findViewById(R.id.taskManagerCards); assertEquals(6, cards.getChildCount());
            View last = cards.getChildAt(5); assertTrue(last.getRight() <= a.findViewById(R.id.taskManagerScroll).getWidth());
            assertTrue(cards.getChildAt(0).getHeight() > 250);
            View close = cards.getChildAt(0).findViewById(R.id.taskCardClose);
            android.graphics.Rect visible = new android.graphics.Rect(); assertTrue(close.getGlobalVisibleRect(visible));
            assertEquals("Close action must fit the viewport", close.getHeight(), visible.height());
            View root = a.findViewById(R.id.taskManagerRoot);
            assertTrue(visible.bottom <= root.getHeight() - root.getPaddingBottom());
        });
        screenshot("05-task-manager");
        Bundle empty = new Bundle(); empty.putBoolean("emptyTasks", true); empty.putBoolean("emitTasks", true); control(mock, empty); waitFor(() -> taskCount() == 0);
        screenshot("06-task-manager-empty");
    }
}
