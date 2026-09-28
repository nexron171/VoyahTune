package ru.big.town.restoremode;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class AppWidgetStoreTest {

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
}
