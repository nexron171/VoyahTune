package ru.big.town.restoremode.settings.sections.main;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.ui.list.SettingsList;
import ru.big.town.restoremode.settings.shell.SettingsHeaderView;
import ru.big.town.restoremode.widgets.dials.DialWidgetStore;
import ru.big.town.restoremode.settings.sections.passenger.PassengerSettingsFragment;
import ru.big.town.restoremode.dashboard.DashboardProfile;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainSettingsNavigationTest {
    @Test
    public void categoryNavigationPreservesPreferencesAndRestoresOpenPage() {
        ActivityController<FragmentActivity> controller =
                Robolectric.buildActivity(FragmentActivity.class);
        controller.get().setTheme(R.style.Theme_RestoreMode_Settings);
        controller.setup();
        try {
            FragmentActivity activity = controller.get();
            MainSettingsFragment fragment = new MainSettingsFragment();
            activity.getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, fragment, "main").commitNow();
            SettingsList list = (SettingsList) fragment.requireView();
            layout(list);
            assertNull(list.findViewById(R.id.switchFullscreenGrid));
            open(list, "Сетка и размещение");
            Switch fullscreen = list.findViewById(R.id.switchFullscreenGrid);
            assertNotNull(fullscreen);
            fullscreen.setChecked(true);
            assertTrue(activity.getSharedPreferences("DrivePreferences", 0)
                    .getBoolean("fullscreenGrid", false));

            activity.getSupportFragmentManager().beginTransaction().detach(fragment).commitNow();
            activity.getSupportFragmentManager().beginTransaction().attach(fragment).commitNow();
            list = (SettingsList) fragment.requireView();
            layout(list);
            assertTrue(((Switch) list.findViewById(R.id.switchFullscreenGrid)).isChecked());
            assertTrue(fragment.navigateUp());
            layout(list);
            assertNull(list.findViewById(R.id.switchFullscreenGrid));
            assertFalse(fragment.navigateUp());
            open(list, "Сетка и размещение");
            assertTrue(((Switch) list.findViewById(R.id.switchFullscreenGrid)).isChecked());
            assertTrue(fragment.navigateUp());
            layout(list);
            open(list, "Автомобиль и поездки");
            assertNotNull(list.findViewById(R.id.energyBatteryCapacity));
            assertNull(list.findViewById(R.id.switchFullscreenGrid));
            assertTrue(fragment.navigateUp());
            layout(list);
            for (MainSettingsCategory category : MainSettingsCategory.values()) {
                open(list, category.title);
                boolean cabinTemperatureVisible = false;
                for (int row = 0; row < list.getAdapter().getItemCount(); row++) {
                    list.scrollToPosition(row);
                    layout(list);
                    cabinTemperatureVisible |= list.findViewById(R.id.switchShowCabinTemperature) != null;
                }
                if (category == MainSettingsCategory.INFORMATION) {
                    assertTrue(cabinTemperatureVisible);
                }
                assertTrue(fragment.navigateUp());
                layout(list);
            }
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void headerBackReturnsToCategoriesAndHidesOnRootPage() {
        ActivityController<FragmentActivity> controller =
                Robolectric.buildActivity(FragmentActivity.class);
        controller.get().setTheme(R.style.Theme_RestoreMode_Settings);
        controller.setup();
        try {
            MainSettingsFragment fragment = new MainSettingsFragment();
            controller.get().getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, fragment).commitNow();
            SettingsList list = (SettingsList) fragment.requireView();
            layout(list);
            open(list, "Сетка и размещение");
            SettingsHeaderView header = new SettingsHeaderView(controller.get(), null);
            header.setTitle(fragment.categoryTitle(), "Главный экран");
            header.onNavigateUp(fragment::navigateUp);
            assertEquals("Сетка и размещение",
                    ((TextView) header.findViewById(R.id.sectionTitle)).getText().toString());
            View back = header.findViewById(R.id.settingsCategoryBack);
            assertEquals(View.VISIBLE, back.getVisibility());
            assertTrue(back.performClick());
            assertNull(fragment.categoryTitle());
            layout(list);
            assertNull(list.findViewById(R.id.switchFullscreenGrid));
            header.onNavigateUp(null);
            assertEquals(View.GONE, back.getVisibility());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void pairedContactsSaveOnlyTheEditedContact() {
        ActivityController<FragmentActivity> controller =
                Robolectric.buildActivity(FragmentActivity.class);
        controller.get().setTheme(R.style.Theme_RestoreMode_Settings);
        controller.setup();
        try {
            List<DialWidgetStore.Entry> entries = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                DialWidgetStore.Entry entry = new DialWidgetStore.Entry();
                entry.name = "Контакт " + index;
                entry.number = "1234";
                entries.add(entry);
            }
            android.content.SharedPreferences preferences =
                    controller.get().getSharedPreferences("DrivePreferences", 0);
            DialWidgetStore.save(preferences, entries);
            MainSettingsFragment fragment = new MainSettingsFragment();
            controller.get().getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, fragment).commitNow();
            SettingsList list = (SettingsList) fragment.requireView();
            layout(list);
            open(list, "Ссылки и контакты");
            TextView name = null;
            for (int row = 0; row < list.getAdapter().getItemCount() && name == null; row++) {
                list.scrollToPosition(row);
                layout(list);
                name = findText(list, "Контакт 0");
            }
            assertNotNull(name);
            assertNotNull(findText(list, "Контакт 1"));
            name.setText("Новое имя");
            View contact = (View) name.getParent().getParent();
            assertTrue(contact.findViewById(R.id.dialSettingSave).performClick());
            List<DialWidgetStore.Entry> saved = DialWidgetStore.load(preferences);
            assertEquals("Новое имя", saved.get(0).name);
            assertEquals("Контакт 1", saved.get(1).name);
            assertEquals("Контакт 2", saved.get(2).name);
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void passengerCategoriesKeepIndependentLayoutAndGlobalAutoStart() {
        ActivityController<FragmentActivity> controller =
                Robolectric.buildActivity(FragmentActivity.class);
        controller.get().setTheme(R.style.Theme_RestoreMode_Settings);
        controller.setup();
        try {
            android.content.SharedPreferences driver =
                    DashboardProfile.driver().layoutPreferences(controller.get());
            android.content.SharedPreferences passenger =
                    DashboardProfile.passenger().layoutPreferences(controller.get());
            driver.edit().putBoolean("fullscreenGrid", false).apply();
            PassengerSettingsFragment fragment = new PassengerSettingsFragment();
            controller.get().getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, fragment).commitNow();
            SettingsList list = (SettingsList) fragment.requireView();
            layout(list);
            assertNull(list.findViewById(R.id.switchFullscreenGrid));
            open(list, "Сетка и размещение");
            ((Switch) list.findViewById(R.id.switchFullscreenGrid)).setChecked(true);
            assertTrue(passenger.getBoolean("fullscreenGrid", false));
            assertFalse(driver.getBoolean("fullscreenGrid", true));
            ((Switch) list.findViewById(R.id.switchPassengerAutoStart)).setChecked(true);
            assertTrue(driver.getBoolean("passengerAutoStart", false));
            assertTrue(fragment.navigateUp());
            layout(list);
            open(list, "Информационные виджеты");
            ((Switch) list.findViewById(R.id.switchShowClock)).setChecked(true);
            assertTrue(passenger.getBoolean("show_clockWidget", false));
            assertFalse(driver.getBoolean("show_clockWidget", false));
            assertTrue(fragment.navigateUp());
            layout(list);
            for (MainSettingsCategory category : MainSettingsCategory.values()) {
                open(list, category.title);
                boolean cabinTemperatureVisible = false;
                for (int row = 0; row < list.getAdapter().getItemCount(); row++) {
                    list.scrollToPosition(row);
                    layout(list);
                    cabinTemperatureVisible |= list.findViewById(R.id.switchShowCabinTemperature) != null;
                }
                if (category == MainSettingsCategory.INFORMATION) {
                    assertTrue(cabinTemperatureVisible);
                }
                assertTrue(fragment.navigateUp());
                layout(list);
            }
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private static void open(SettingsList list, String title) {
        for (int index = 0; index < list.getAdapter().getItemCount(); index++) {
            list.scrollToPosition(index);
            layout(list);
            TextView label = findText(list, title);
            if (label != null) {
                assertTrue(((View) label.getParent().getParent()).performClick());
                layout(list);
                return;
            }
        }
        throw new AssertionError("Category not found: " + title);
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView match = findText(group.getChildAt(index), text);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static void layout(View view) {
        shadowOf(Looper.getMainLooper()).idle();
        view.measure(View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1400, 720);
        shadowOf(Looper.getMainLooper()).idle();
    }
}
