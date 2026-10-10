package ru.big.town.restoremode.widgets.apps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.big.town.common.CarPlayTarget;

import java.util.ArrayList;
import java.util.List;

public class AppWidgetStoreTest {

    @Test
    public void runningPackageDpiDoesNotChangeTheSelectedProfileAfterSwap() {
        AppWidgetStore.Entry entry = new AppWidgetStore.Entry("one", "app.a", 2, 4, 160, false, 3);
        AppWidgetStore.addProfile(entry, "app.b", 240);
        assertTrue(entry.setPackageDpi("app.b", 260));
        assertEquals(0, entry.selectedProfile);
        assertEquals("app.a", entry.packageName);
        assertEquals(160, entry.dpi);
        assertEquals(160, entry.profiles.get(0).dpi);
        assertEquals(260, entry.profiles.get(1).dpi);
    }

    @Test
    public void temporaryMovedAppDoesNotReplaceTheWidgetsConfiguredApp() {
        AppWidgetStore.Entry entry = new AppWidgetStore.Entry("one", "app.a", 2, 4, 160, false, 3);
        assertFalse(entry.setPackageDpi("app.moved", 260));
        assertEquals(1, entry.profiles.size());
        assertEquals("app.a", entry.selected().packageName);
        assertEquals(160, entry.dpi);
        assertTrue(entry.setPackageDpi("app.a", 240));
        assertEquals(240, entry.dpi);
    }

    @Test
    public void designationFollowsCreationOrder() {
        assertEquals("A", AppWidgetStore.designation(0));
        assertEquals("B", AppWidgetStore.designation(1));
        assertEquals("D", AppWidgetStore.designation(3));
        assertEquals("T", AppWidgetStore.designation(19));
        assertEquals("", AppWidgetStore.designation(-1));
    }

    @Test
    public void designationFromListMatchesEntryId() {
        List<AppWidgetStore.Entry> entries = new ArrayList<>();
        entries.add(new AppWidgetStore.Entry("one", "com.example.one"));
        entries.add(new AppWidgetStore.Entry("two", "com.example.two"));
        assertEquals("A", AppWidgetStore.designation(entries, "one"));
        assertEquals("B", AppWidgetStore.designation(entries, "two"));
        assertEquals("", AppWidgetStore.designation(entries, "missing"));
        assertEquals("", AppWidgetStore.designation((List<AppWidgetStore.Entry>) null, "one"));
    }
    @Test
    public void carPlayDuplicatesKeepFirstIdentitySizeAndLaunchSettings() {
        AppWidgetStore.Entry first =
                new AppWidgetStore.Entry("first", CarPlayTarget.ID, 9, 5, 160, true, 7);
        List<AppWidgetStore.Entry> entries = new ArrayList<>();
        entries.add(first);
        entries.add(new AppWidgetStore.Entry("second", CarPlayTarget.ID));
        entries.add(new AppWidgetStore.Entry("third", CarPlayTarget.ID));

        assertTrue(AppWidgetStore.removeDuplicateCarPlayWidgets(entries));
        assertEquals(1, entries.size());
        assertEquals("first", entries.get(0).id);
        assertEquals(9, first.width);
        assertEquals(5, first.height);
        assertEquals(160, first.dpi);
        assertTrue(first.autoStart);
        assertEquals(7, first.autoStartDelay);
        assertFalse(AppWidgetStore.removeDuplicateCarPlayWidgets(entries));
    }

    @Test
    public void carPlayDeduplicationPreservesOrdinaryAndMixedWidgets() {
        AppWidgetStore.Entry mixed = new AppWidgetStore.Entry("mixed", CarPlayTarget.ID);
        AppWidgetStore.addProfile(mixed, "app.music", 160);
        List<AppWidgetStore.Entry> entries = new ArrayList<>();
        entries.add(mixed);
        entries.add(new AppWidgetStore.Entry("music", "app.music"));
        entries.add(new AppWidgetStore.Entry("carplay", CarPlayTarget.ID));
        entries.add(new AppWidgetStore.Entry("duplicate", CarPlayTarget.ID));

        assertTrue(AppWidgetStore.removeDuplicateCarPlayWidgets(entries));
        assertEquals(3, entries.size());
        assertEquals("mixed", entries.get(0).id);
        assertEquals(2, mixed.profiles.size());
        assertEquals("music", entries.get(1).id);
        assertEquals("carplay", entries.get(2).id);
    }

}
