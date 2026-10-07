package ru.big.town.restoremode;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Messenger;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import ru.big.town.common.ScenarioProtocol;
import ru.big.town.restoremode.apps.FullscreenAppStore;
import ru.big.town.restoremode.integration.GlobalVars;
import ru.big.town.restoremode.scenarios.ScenarioStore;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.state.SettingsSectionViewModel;
import ru.big.town.restoremode.settings.ui.layout.SettingsFlow;
import ru.big.town.restoremode.settings.ui.list.SettingsList;
import ru.big.town.restoremode.vehicle.steering.SteeringActionStore;
import ru.big.town.restoremode.widgets.apps.AppShortcutStore;
import ru.big.town.restoremode.widgets.dials.DialWidgetStore;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class LazySettingsIntegrationTest {
    private Context context;
    private SharedPreferences preferences;
    private ActivityScenario<AdvanceActivity> scenario;
    private AdvanceActivity activity;
    private Messenger applyReply;
    private final List<Integer> commands = new ArrayList<>();

    @Before
    public void setUp() {
        assertTrue(
                "Emulator only", Build.HARDWARE.contains("ranchu") || Build.MODEL.contains("sdk"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = context.getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
        preferences.edit().clear().putBoolean("voiceEnabled", false).commit();
        GlobalVars.isBound = true;
        GlobalVars.serviceMessenger =
                new Messenger(
                        new Handler(
                                Looper.getMainLooper(),
                                message -> {
                                    commands.add(message.what);
                                    if (message.what == 1) {
                                        applyReply = message.replyTo;
                                    }
                                    return true;
                                }));
    }

    @After
    public void tearDown() {
        if (scenario != null) {
            scenario.close();
        }
        GlobalVars.isBound = false;
        GlobalVars.serviceMessenger = null;
    }

    private void open(SettingsSection section) {
        scenario =
                ActivityScenario.launch(
                        new Intent(context, AdvanceActivity.class)
                                .putExtra(AdvanceActivity.EXTRA_SECTION, section.identifier));
        scenario.onActivity(current -> activity = current);
        idle();
    }

    private void idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void select(SettingsSection section) {
        scenario.onActivity(current -> current.findViewById(section.navigationId).performClick());
        idle();
    }

    private SettingsList list() {
        return activity.findViewById(R.id.settingsList);
    }

    private void scroll(String key) {
        SettingsTestNavigator.scrollTo(activity, key);
    }

    private static int countViews(View root) {
        int count = 1;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                count += countViews(group.getChildAt(index));
            }
        }
        return count;
    }

    @Test
    public void openingSettingsBuildsOnlyVisibleRowsEvenWithOneHundredDialCards() {
        List<DialWidgetStore.Entry> entries = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            DialWidgetStore.Entry entry = new DialWidgetStore.Entry();
            entry.name = "Контакт " + index;
            entry.number = "12345";
            entries.add(entry);
        }
        DialWidgetStore.save(preferences, entries);
        open(SettingsSection.MAIN);
        scenario.onActivity(
                current -> {
                    assertTrue(list().getAdapter().getItemCount() > 100);
                    assertTrue(
                            "Startup must not build the whole section", list().createdRows() < 20);
                    assertTrue(
                            "Only viewport rows may retain controls", list().getChildCount() < 15);
                    assertNull(current.findViewById(R.id.rawCanCodes));
                    assertNull(current.findViewById(R.id.drive_modes_group));
                    assertNull(current.findViewById(R.id.dialSettingName));
                    android.util.Log.i(
                            "LazySettingsTest",
                            "Startup rows="
                                    + list().createdRows()
                                    + " views="
                                    + countViews(list()));
                });
        scroll("dial:" + entries.get(0).id);
        scenario.onActivity(
                current ->
                        ((EditText) current.findViewById(R.id.dialSettingName))
                                .setText("Черновик"));
        scroll("dial:" + entries.get(99).id);
        scenario.onActivity(
                current -> {
                    assertTrue(list().getChildCount() < 15);
                    assertNotNull(
                            SettingsTestNavigator.find(
                                    list(),
                                    view ->
                                            view instanceof EditText
                                                    && "Контакт 99"
                                                            .contentEquals(
                                                                    ((EditText) view).getText())));
                });
        scroll("dial:" + entries.get(0).id);
        scenario.onActivity(
                current ->
                        assertEquals(
                                "Черновик",
                                ((EditText) current.findViewById(R.id.dialSettingName))
                                        .getText()
                                        .toString()));
        scenario.recreate();
        scenario.onActivity(current -> activity = current);
        scroll("dial:" + entries.get(0).id);
        scenario.onActivity(
                current ->
                        assertEquals(
                                "Черновик",
                                ((EditText) current.findViewById(R.id.dialSettingName))
                                        .getText()
                                        .toString()));
        assertEquals("Контакт 0", DialWidgetStore.load(preferences).get(0).name);
    }

    @Test
    public void appChipsStayCompactAndLongListsRemainVirtualized() {
        List<String> packages = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            packages.add("mock.app." + index);
        }
        AppShortcutStore.save(preferences, packages);
        FullscreenAppStore.save(preferences, packages);
        open(SettingsSection.MAIN);
        scroll("shortcuts:mock.app.0");
        scenario.onActivity(
                current -> {
                    SettingsFlow flow =
                            (SettingsFlow)
                                    SettingsTestNavigator.find(
                                            list(), view -> view instanceof SettingsFlow);
                    assertNotNull(flow);
                    assertEquals(3, flow.getChildCount());
                    assertEquals(flow.getChildAt(0).getTop(), flow.getChildAt(2).getTop());
                    assertTrue(list().getChildCount() < 20);
                    flow.getChildAt(0).findViewById(R.id.shortcutDelete).performClick();
                });
        idle();
        assertEquals(99, AppShortcutStore.load(preferences).size());
        assertFalse(AppShortcutStore.load(preferences).contains("mock.app.0"));
        scroll("shortcuts:mock.app.97");
        scenario.onActivity(
                current ->
                        assertNotNull(
                                SettingsTestNavigator.find(
                                        list(),
                                        view ->
                                                view instanceof TextView
                                                        && "mock.app.99"
                                                                .contentEquals(
                                                                        ((TextView) view)
                                                                                .getText()))));
        select(SettingsSection.APPS);
        scroll("fullscreen:mock.app.99");
        scenario.onActivity(
                current -> {
                    assertTrue(list().getChildCount() < 20);
                    View chipLabel =
                            SettingsTestNavigator.find(
                                    list(),
                                    view ->
                                            view instanceof TextView
                                                    && "mock.app.99"
                                                            .contentEquals(
                                                                    ((TextView) view).getText()));
                    assertNotNull(chipLabel);
                    ((View) chipLabel.getParent()).findViewById(R.id.shortcutDelete).performClick();
                });
        idle();
        assertEquals(99, FullscreenAppStore.load(preferences).size());
        assertFalse(FullscreenAppStore.load(preferences).contains("mock.app.99"));
    }

    @Test
    public void everySectionCanScrollAndReopenWithoutApplyingVehicleCommands() {
        open(SettingsSection.MAIN);
        for (SettingsSection section : SettingsSection.values()) {
            select(section);
            scenario.onActivity(
                    current -> {
                        assertEquals(
                                section.title,
                                ((TextView) current.findViewById(R.id.sectionTitle))
                                        .getText()
                                        .toString());
                        List<String> keys = list().rowKeys();
                        assertFalse(keys.isEmpty());
                        list().scrollToKey(keys.get(keys.size() - 1));
                    });
            idle();
        }
        select(SettingsSection.MAIN);
        scenario.onActivity(current -> assertTrue(commands.toString(), commands.isEmpty()));
    }

    @Test
    public void settingsAndCanDraftSurviveRecyclingAndSectionChanges() {
        open(SettingsSection.VEHICLE);
        scroll("layout:" + R.layout.settings_vehicle_drive_energy_modes);
        scenario.onActivity(
                current -> ((RadioButton) current.findViewById(R.id.SPORT)).performClick());
        assertEquals("SPORT", preferences.getString("driveMode", ""));
        scroll("layout:" + R.layout.settings_vehicle_energy_button_layout);
        scenario.onActivity(current -> current.findViewById(R.id.checkBox34).performClick());
        scroll("layout:" + R.layout.settings_vehicle_drive_energy_modes);
        scenario.onActivity(
                current ->
                        assertEquals(View.GONE, current.findViewById(R.id.SMART).getVisibility()));
        select(SettingsSection.CAN);
        scroll("layout:" + R.layout.settings_can_editor);
        scenario.onActivity(
                current ->
                        ((EditText) current.findViewById(R.id.rawCanCodes))
                                .setText("64088000000000000003"));
        select(SettingsSection.OTHER);
        select(SettingsSection.CAN);
        scroll("layout:" + R.layout.settings_can_editor);
        scenario.onActivity(
                current ->
                        assertEquals(
                                "64 08 80 00 00 00 00 00 00 03",
                                ((EditText) current.findViewById(R.id.rawCanCodes))
                                        .getText()
                                        .toString()));
        assertEquals("", preferences.getString("customCommand", ""));
    }

    @Test
    public void appProfilesAndSteeringActionsAreBoundedByTheirViewports() throws Exception {
        org.json.JSONArray profiles = new org.json.JSONArray();
        for (int index = 0; index < 80; index++) {
            profiles.put(
                    new org.json.JSONObject()
                            .put("package", "mock.profile." + index)
                            .put("dpi", 0));
        }
        org.json.JSONObject widget =
                new org.json.JSONObject()
                        .put("id", "many-profiles")
                        .put("package", context.getPackageName())
                        .put("apps", profiles);
        preferences
                .edit()
                .putString("appWidgets", new org.json.JSONArray().put(widget).toString())
                .commit();
        List<String> actions = new ArrayList<>();
        for (int index = 0; index < 80; index++) {
            actions.add("toggle_forced_ev");
        }
        SteeringActionStore.save(preferences, "steerStarShort", actions);
        open(SettingsSection.MAIN);
        scroll("widgets:many-profiles:profile:79");
        scenario.onActivity(
                current -> {
                    assertTrue(list().getChildCount() < 20);
                    assertNotNull(
                            SettingsTestNavigator.find(
                                    list(),
                                    view ->
                                            view instanceof TextView
                                                    && "mock.profile.79"
                                                            .contentEquals(
                                                                    ((TextView) view).getText())));
                });
        select(SettingsSection.STEERING);
        scroll("layout:" + R.layout.settings_steering_button_actions);
        scenario.onActivity(
                current -> {
                    SettingsList actionsList = current.findViewById(R.id.settingsSteeringActions);
                    assertEquals(82, actionsList.getAdapter().getItemCount());
                    assertTrue(actionsList.getChildCount() < 20);
                    assertTrue(actionsList.createdRows() < 20);
                    actionsList.scrollToKey("steerStar:action:79");
                });
        idle();
        scenario.onActivity(
                current -> {
                    SettingsList actionsList = current.findViewById(R.id.settingsSteeringActions);
                    assertNotNull(
                            SettingsTestNavigator.find(
                                    actionsList,
                                    view ->
                                            view.getId() == R.id.settingsSteerIndex
                                                    && "80"
                                                            .contentEquals(
                                                                    ((TextView) view).getText())));
                    current.findViewById(R.id.settingsPhoneTab).performClick();
                });
        idle();
        scenario.onActivity(
                current ->
                        assertEquals(
                                3,
                                ((SettingsList) current.findViewById(R.id.settingsSteeringActions))
                                        .getAdapter()
                                        .getItemCount()));
    }

    @Test
    public void expandedScenarioDoesNotConstructOffscreenStepsAndKeepsNameDraft() {
        ScenarioStore.Scenario automation = ScenarioStore.create(preferences, "Много шагов");
        for (int index = 0; index < ScenarioProtocol.MAX_STEPS; index++) {
            ScenarioStore.Step step = new ScenarioStore.Step();
            step.type = ScenarioProtocol.STEP_PAUSE;
            step.seconds = index + 1;
            automation.steps.add(step);
        }
        ScenarioStore.update(preferences, automation);
        preferences.edit().putString("scenarioExpandedId", automation.id).commit();
        open(SettingsSection.SCENARIOS);
        View name = SettingsTestNavigator.described(activity, "Название сценария");
        scenario.onActivity(current -> ((EditText) name).setText("Не сохранено"));
        scroll("scenario:" + automation.id + ":step:" + (ScenarioProtocol.MAX_STEPS - 1));
        scenario.onActivity(
                current -> {
                    assertTrue(list().getChildCount() < 15);
                    assertNull(
                            SettingsTestNavigator.find(
                                    list(),
                                    view ->
                                            "Название сценария"
                                                    .contentEquals(
                                                            view.getContentDescription() == null
                                                                    ? ""
                                                                    : view
                                                                            .getContentDescription())));
                });
        View restored = SettingsTestNavigator.described(activity, "Название сценария");
        scenario.onActivity(
                current ->
                        assertEquals("Не сохранено", ((EditText) restored).getText().toString()));
        assertEquals("Много шагов", ScenarioStore.find(preferences, automation.id).name);
    }

    @Test
    public void switchingDetachesViewsButKeepsSectionModelsDraftsAndScroll() {
        List<DialWidgetStore.Entry> entries = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            DialWidgetStore.Entry entry = new DialWidgetStore.Entry();
            entry.name = "Контакт " + index;
            entries.add(entry);
        }
        DialWidgetStore.save(preferences, entries);
        open(SettingsSection.MAIN);
        Fragment[] main = {null};
        SettingsSectionViewModel[] model = {null};
        SettingsList[] originalList = {null};
        scroll("dial:" + entries.get(20).id);
        scenario.onActivity(
                current -> {
                    main[0] =
                            current.getSupportFragmentManager().findFragmentByTag("settings:MAIN");
                    assertEquals(1, current.getSupportFragmentManager().getFragments().size());
                    model[0] = new ViewModelProvider(main[0]).get(SettingsSectionViewModel.class);
                    originalList[0] = list();
                    ((EditText) current.findViewById(R.id.dialSettingName))
                            .setText("Несохранённый контакт");
                });
        select(SettingsSection.OTHER);
        scenario.onActivity(
                current -> {
                    assertTrue(main[0].isDetached());
                    assertNull(main[0].getView());
                    assertNull(originalList[0].getAdapter());
                    assertNull(current.findViewById(R.id.dialSettingName));
                });
        scenario.recreate();
        scenario.onActivity(current -> activity = current);
        select(SettingsSection.MAIN);
        scenario.onActivity(
                current -> {
                    Fragment recreated =
                            current.getSupportFragmentManager().findFragmentByTag("settings:MAIN");
                    assertSame(
                            model[0],
                            new ViewModelProvider(recreated).get(SettingsSectionViewModel.class));
                    assertNotSame(originalList[0], list());
                    assertEquals(
                            "Несохранённый контакт",
                            ((EditText) current.findViewById(R.id.dialSettingName))
                                    .getText()
                                    .toString());
                });
        assertEquals("Контакт 20", DialWidgetStore.load(preferences).get(20).name);
    }

    @Test
    public void applyWaitsForOriginalNativeReplyAfterActivityRecreation() throws Exception {
        open(SettingsSection.VEHICLE);
        scenario.onActivity(
                current -> current.findViewById(R.id.buttonApplyAdvance).performClick());
        idle();
        assertNotNull(applyReply);
        scenario.recreate();
        scenario.onActivity(
                current -> {
                    activity = current;
                    assertFalse(current.findViewById(R.id.buttonApplyAdvance).isEnabled());
                });
        applyReply.send(android.os.Message.obtain(null, 4));
        idle();
        scenario.onActivity(
                current -> assertTrue(current.findViewById(R.id.buttonApplyAdvance).isEnabled()));
        assertEquals(1, java.util.Collections.frequency(commands, 1));
    }

    @Test
    public void savedStateRestoresDraftsIntoFreshViewModelInstances() {
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(
                        () -> {
                            SavedStateOwner first = new SavedStateOwner(null);
                            androidx.lifecycle.ViewModelProvider provider = first.provider(context);
                            SettingsSectionViewModel section =
                                    provider.get(SettingsSectionViewModel.class);
                            android.os.Bundle draft = new android.os.Bundle();
                            draft.putString("editor", "Не сохранено в настройках");
                            section.save(draft);
                            ru.big.town.restoremode.settings.sections.can.CanCommandsViewModel can =
                                    provider.get(
                                            ru.big.town.restoremode.settings.sections.can
                                                    .CanCommandsViewModel.class);
                            can.initialize("64 08 80 00 00 00 00 00 00 03", 3);
                            android.os.Bundle saved = new android.os.Bundle();
                            first.registry.performSave(saved);
                            first.lifecycle.handleLifecycleEvent(
                                    androidx.lifecycle.Lifecycle.Event.ON_DESTROY);
                            first.store.clear();
                            SavedStateOwner restored = new SavedStateOwner(saved);
                            SettingsSectionViewModel restoredSection =
                                    restored.provider(context).get(SettingsSectionViewModel.class);
                            assertNotSame(section, restoredSection);
                            assertEquals(
                                    "Не сохранено в настройках",
                                    restoredSection.state().getString("editor"));
                            ru.big.town.restoremode.settings.sections.can.CanCommandsViewModel
                                    restoredCan =
                                            restored.provider(context)
                                                    .get(
                                                            ru.big.town.restoremode.settings
                                                                    .sections.can
                                                                    .CanCommandsViewModel.class);
                            restoredCan.initialize("", 1);
                            assertEquals("64 08 80 00 00 00 00 00 00 03", restoredCan.text());
                            assertEquals(3, restoredCan.count());
                            restored.lifecycle.handleLifecycleEvent(
                                    androidx.lifecycle.Lifecycle.Event.ON_DESTROY);
                            restored.store.clear();
                        });
    }

    private static final class SavedStateOwner
            implements androidx.savedstate.SavedStateRegistryOwner,
                    androidx.lifecycle.ViewModelStoreOwner {
        final androidx.lifecycle.LifecycleRegistry lifecycle =
                new androidx.lifecycle.LifecycleRegistry(this);
        final androidx.lifecycle.ViewModelStore store = new androidx.lifecycle.ViewModelStore();
        final androidx.savedstate.SavedStateRegistryController registry =
                androidx.savedstate.SavedStateRegistryController.create(this);

        SavedStateOwner(android.os.Bundle state) {
            registry.performAttach();
            registry.performRestore(state);
            lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE);
        }

        androidx.lifecycle.ViewModelProvider provider(Context context) {
            return new androidx.lifecycle.ViewModelProvider(
                    this,
                    new androidx.lifecycle.SavedStateViewModelFactory(
                            (android.app.Application) context.getApplicationContext(), this, null));
        }

        @Override
        public androidx.lifecycle.Lifecycle getLifecycle() {
            return lifecycle;
        }

        @Override
        public androidx.lifecycle.ViewModelStore getViewModelStore() {
            return store;
        }

        @Override
        public androidx.savedstate.SavedStateRegistry getSavedStateRegistry() {
            return registry.getSavedStateRegistry();
        }
    }
}
