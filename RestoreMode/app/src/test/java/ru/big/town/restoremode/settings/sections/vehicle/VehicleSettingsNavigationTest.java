package ru.big.town.restoremode.settings.sections.vehicle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.graphics.Rect;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;
import androidx.fragment.app.FragmentActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.ui.list.SettingsList;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class VehicleSettingsNavigationTest {
    @Test
    public void categoriesPreserveSettingsAndRestoreTheOpenPage() {
        ActivityController<FragmentActivity> controller =
                Robolectric.buildActivity(FragmentActivity.class);
        controller.get().setTheme(R.style.Theme_RestoreMode_Settings);
        controller.setup();
        try {
            VehicleSettingsFragment fragment = new VehicleSettingsFragment();
            controller.get().getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, fragment).commitNow();
            SettingsList list = (SettingsList) fragment.requireView();
            layout(list);
            assertNull(list.findViewById(R.id.switchBatteryHeatAuto));
            open(list, "Комфорт и автоматизация");
            Switch heating = list.findViewById(R.id.switchBatteryHeatAuto);
            for (int row = 0; heating == null && row < list.getAdapter().getItemCount(); row++) {
                list.scrollToPosition(row);
                layout(list);
                heating = list.findViewById(R.id.switchBatteryHeatAuto);
            }
            assertNotNull(heating);
            heating.setChecked(true);
            assertTrue(controller.get().getSharedPreferences("DrivePreferences", 0)
                    .getBoolean("batteryHeatAuto", false));
            controller.get().getSupportFragmentManager().beginTransaction().detach(fragment).commitNow();
            controller.get().getSupportFragmentManager().beginTransaction().attach(fragment).commitNow();
            list = (SettingsList) fragment.requireView();
            layout(list);
            assertEquals("Комфорт и автоматизация", fragment.categoryTitle());
            assertTrue(fragment.navigateUp());
            layout(list);
            assertNull(fragment.categoryTitle());
            assertFalse(fragment.navigateUp());
            for (String title : new String[] {"Движение и энергия", "Свет и экраны",
                    "Звук и музыка", "Комфорт и автоматизация", "Обслуживание и штатный интерфейс"}) {
                open(list, title);
                for (int row = 0; row < list.getAdapter().getItemCount(); row++) {
                    list.scrollToPosition(row);
                    layout(list);
                }
                assertTrue(fragment.navigateUp());
                layout(list);
            }
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void turnSoundIsVisibleWithoutVehicleIdentificationAndApplyStaysDisabled() {
        ActivityController<FragmentActivity> controller =
                Robolectric.buildActivity(FragmentActivity.class);
        controller.get().setTheme(R.style.Theme_RestoreMode_Settings);
        controller.setup();
        try {
            VehicleSettingsFragment fragment = new VehicleSettingsFragment();
            controller.get().getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, fragment).commitNow();
            SettingsList list = (SettingsList) fragment.requireView();
            layout(list);
            open(list, "Звук и музыка");
            TextView apply = null;
            for (int row = 0; row < list.getAdapter().getItemCount(); row++) {
                list.scrollToPosition(row);
                layout(list);
                apply = findText(list, "Применить");
                if (apply != null) break;
            }
            assertNotNull(apply);
            assertFalse(apply.isEnabled());
            TextView listen = findText(list, "Прослушать");
            assertNotNull(listen);
            assertEquals(listen.getTop(), apply.getTop());
            assertTrue(apply.getLeft() > listen.getRight());
            assertTrue(apply.getWidth() < list.getWidth() / 2);
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    @Config(qualifiers = "w900dp-h720dp-mdpi")
    public void energySettingsUseTwoAlignedColumns() {
        ContextThemeWrapper context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.Theme_RestoreMode_Settings);
        ViewGroup row = (ViewGroup) LayoutInflater.from(context).inflate(
                R.layout.settings_vehicle_energy_button_layout, null);
        View fuelModesToggle = row.findViewById(R.id.checkBox34);
        View fuelModesCard = (View) fuelModesToggle.getParent();
        View driveEntryCard = row.findViewById(R.id.driveEntryRestoreRow);
        View smartSaveCard = row.findViewById(R.id.smartSaveRow);
        View hybridCard = row.findViewById(R.id.hybridEngineHoldRow);
        layoutEnergySettings(row);

        Rect fuelModes = boundsIn(row, fuelModesCard);
        Rect driveEntry = boundsIn(row, driveEntryCard);
        Rect smartSave = boundsIn(row, smartSaveCard);
        Rect hybrid = boundsIn(row, hybridCard);
        assertCardsShareRow(fuelModes, driveEntry);
        assertCardsShareRow(smartSave, hybrid);
        assertEquals(fuelModes.left, smartSave.left);
        assertEquals(driveEntry.right, hybrid.right);
        assertEquals(fuelModes.width(), smartSave.width());
        assertTrue(fuelModes.bottom < smartSave.top);
        assertEquals(boundsIn(row, fuelModesToggle).top,
                boundsIn(row, row.findViewById(R.id.switchDriveEntryRestore)).top);
        assertEquals(boundsIn(row, row.findViewById(R.id.switchSmartSave)).top,
                boundsIn(row, row.findViewById(R.id.switchHybridEngineHold)).top);

        driveEntryCard.setVisibility(View.GONE);
        smartSaveCard.setVisibility(View.GONE);
        hybridCard.setVisibility(View.GONE);
        layoutEnergySettings(row);
        assertEquals(row.getWidth(), fuelModesCard.getWidth());
        assertEquals(row.getHeight(), fuelModesCard.getHeight());
    }

    private static void layoutEnergySettings(View row) {
        row.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, 900, row.getMeasuredHeight());
    }

    private static void assertCardsShareRow(Rect left, Rect right) {
        assertEquals("Both cards must start in the same row", left.top, right.top);
        assertTrue(left.right < right.left);
        assertEquals(left.width(), right.width());
        assertEquals(left.bottom, right.bottom);
    }

    private static Rect boundsIn(ViewGroup row, View child) {
        Rect bounds = new Rect();
        child.getDrawingRect(bounds);
        row.offsetDescendantRectToMyCoords(child, bounds);
        return bounds;
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
                if (match != null) return match;
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
