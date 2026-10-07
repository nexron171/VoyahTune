package ru.big.town.updater.ui;

import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.instanceOf;

@RunWith(AndroidJUnit4.class)
public class UpdaterIntegrationTest {
    private MockRootService root;
    private ActivityScenario<TestUpdaterActivity> screen;
    @Before public void setup() throws Exception { root = new MockRootService(); TestUpdaterActivity.service = root.client(); }
    @After public void cleanup() throws Exception {
        if (screen != null) screen.close();
        root.close(); TestUpdaterActivity.service = null;
    }
    private void launch(boolean initial) {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), TestUpdaterActivity.class)
                .putExtra(UpdaterActivity.OPEN_INITIAL_SCREEN, initial);
        screen = ActivityScenario.launch(intent);
    }
    private static void await(String message, BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime()+7000;
        do { if(condition.getAsBoolean())return; SystemClock.sleep(30); } while(SystemClock.elapsedRealtime()<deadline);
        fail(message);
    }
    private void title(String expected) {
        await("Title: "+expected, () -> {
            AtomicBoolean result=new AtomicBoolean();
            screen.onActivity(a -> result.set(((TextView)a.findViewById(R.id.ota_title)).getText().toString().equals(expected)));
            return result.get();
        });
    }
    private void command(String name) { await("Missing "+name, () -> root.count(name)>0); }

    @Test public void allDaemonPhasesAreRenderedThroughTheSocket() throws Exception {
        launch(false);
        String[][] phases = {
                {"idle","Обновления VoyahTune"}, {"checking","Ищем новый релиз"},
                {"downloading","Скачиваем VoyahTune"}, {"verifying","Проверяем скачанный релиз"},
                {"verified","Можно устанавливать"}, {"preparing","Готовимся к установке"},
                {"applying","Устанавливаем обновление"}, {"reboot-pending","Перезапускаем систему"},
                {"validating","Проверяем работу VoyahTune"}, {"committed","VoyahTune готов к работе"},
                {"repair-required","Не удалось завершить обновление"}
        };
        for(String[] phase:phases){
            root.phase(phase[0],!phase[0].equals("idle")); title(phase[1]);
            boolean busy = java.util.Arrays.asList("checking","downloading","verifying","preparing","applying","reboot-pending","validating").contains(phase[0]);
            screen.onActivity(a -> assertEquals(phase[0],!busy,a.findViewById(R.id.ota_primary).isEnabled()));
        }
        assertNull(root.failure);
    }
    @Test public void availableReleaseAndDownloadUseDaemonFacts() throws Exception {
        root.phase("idle",true);launch(false);title("Доступен релиз");
        onView(withId(R.id.ota_primary)).perform(click());command("download");title("Скачиваем VoyahTune");
        screen.onActivity(a -> assertEquals(25,((ProgressBar)a.findViewById(R.id.ota_progress)).getProgress()));
        root.phase("applying",true);title("Устанавливаем обновление");
        screen.onActivity(a -> assertEquals(37,((ProgressBar)a.findViewById(R.id.ota_progress)).getProgress()));
    }
    @Test public void applyRequiresConfirmationAndCannotBeSentTwice() throws Exception {
        root.phase("verified",true);launch(false);title("Можно устанавливать");
        onView(withId(R.id.ota_primary)).perform(click());assertEquals(0,root.count("apply"));
        onView(withText("Отмена")).perform(click());assertEquals(0,root.count("apply"));
        onView(withId(R.id.ota_primary)).perform(click());
        onView(withText("Установить и перезагрузить")).perform(click());command("apply");title("Готовимся к установке");
        screen.onActivity(a -> a.findViewById(R.id.ota_primary).performClick());
        assertEquals(1,root.count("apply"));
        screen.close();screen=null;assertEquals("preparing",root.phase());
    }
    @Test public void checkAndRepeatHaveDifferentWireArguments() throws Exception {
        launch(false);title("Обновления VoyahTune");onView(withId(R.id.ota_primary)).perform(click());command("check");
        assertFalse(root.last("check").getBoolean("same_version"));
        root.phase("idle",false);title("Обновления VoyahTune");
        onView(withId(R.id.ota_settings)).perform(click());command("get_settings");
        onView(withText("Проверить релиз для повторной установки")).perform(scrollTo(),click());
        await("Repeat request",()->root.count("check")==2);
        assertTrue(root.last("check").getBoolean("same_version"));
    }
    @Test public void settingsUseExternalApiWithoutStartingDownload() throws Exception {
        launch(false);title("Обновления VoyahTune");onView(withId(R.id.ota_settings)).perform(click());command("get_settings");
        onView(instanceOf(android.widget.EditText.class)).perform(replaceText("https://example.test/new.json"),closeSoftKeyboard());
        onView(withText("Сохранить")).perform(click());command("set_settings");
        assertEquals("https://example.test/new.json",root.last("set_settings").getString("url"));
        assertFalse(root.last("set_settings").getBoolean("dns_enabled"));
        assertEquals(0,root.count("download"));assertEquals(0,root.count("apply"));
    }
    @Test public void legacySettingsUseCatalogCommand() throws Exception {
        root.dns=false;launch(false);title("Обновления VoyahTune");onView(withId(R.id.ota_settings)).perform(click());
        onView(withText("Сохранить")).perform(click());command("set_catalog_url");assertEquals(0,root.count("get_settings"));
    }
    @Test public void finishCannotClearRepairLockInTheService() throws Exception {
        root.phase("repair-required",true);launch(false);title("Не удалось завершить обновление");
        onView(withId(R.id.ota_primary)).perform(click());command("finish");
        assertTrue(root.last("finish").getBoolean("reset_errors"));assertEquals("repair-required",root.phase());
        title("Обновления VoyahTune");
    }
    @Test public void legacyFinishHidesResultWithoutInventingServiceState() throws Exception {
        root.legacyFinish=true;root.phase("committed",false);launch(true);command("finish");title("Обновления VoyahTune");
        assertEquals("committed",root.phase());onView(withId(R.id.ota_primary)).perform(click());command("check");
    }
    @Test public void disconnectionAndReconnectionRestoreTheActualOperation() throws Exception {
        root.offline=true;launch(false);title("Служба обновления недоступна");
        root.phase("applying",true);root.offline=false;title("Устанавливаем обновление");
        assertEquals(0,root.count("apply"));
    }
    @Test public void openingDuringInstallDoesNotResetActiveOperation() throws Exception {
        root.phase("applying",true);launch(true);command("finish");title("Устанавливаем обновление");assertEquals("applying",root.phase());
    }
    @Test public void malformedRepliesAndWrongPeerAreRejected() throws Exception {
        JSONObject request=new JSONObject().put("command","status");
        for(String reply:new String[]{"not-json\n","{\"schema\":99,\"ok\":true}\n","{\"schema\":1,\"ok\":false,\"error\":\"denied\"}\n","{\"schema\":1,\"ok\":true}"}){
            root.wireOverride=reply;
            try{root.client().call(request);fail(reply);}catch(Exception expected){assertNotNull(expected);}
        }
        root.wireOverride=null;
        try{root.client(-1).call(request);fail("Untrusted peer accepted");}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("root"));}
    }
    @Test public void failedDownloadOffersFinishAndRetainsErrorDetails() throws Exception {
        root.phase("failed",false);launch(false);command("status");
        await("Failure text",()->{
            AtomicBoolean result=new AtomicBoolean();screen.onActivity(a->result.set(((TextView)a.findViewById(R.id.ota_detail)).getText().toString().contains("Ошибка проверки")));return result.get();
        });
        onView(withId(R.id.ota_primary)).perform(click());command("finish");assertEquals("idle",root.phase());
    }
}
