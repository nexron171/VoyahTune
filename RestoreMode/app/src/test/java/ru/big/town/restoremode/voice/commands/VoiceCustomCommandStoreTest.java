package ru.big.town.restoremode.voice.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import ru.big.town.common.CustomVoiceCommandProtocol;
import ru.big.town.restoremode.vehicle.steering.SteeringActionStore;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class VoiceCustomCommandStoreTest {
    private Context context;
    private SharedPreferences preferences;

    @Before
    public void reset() {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        VoiceCommands.invalidate();
    }

    @Test
    public void saveEditDisableAndRemoveUpdateTheNextRecognitionVocabulary() {
        assertNull(VoiceCommandCatalog.match(VoiceCommands.load(context), "едем домой"));
        VoiceCustomCommandStore.Entry entry =
                entry("едем домой", "headlights:parking", "drive:COMFORT");
        VoiceCustomCommandStore.save(preferences, entry);
        VoiceCommandCatalog.Command command =
                VoiceCommandCatalog.match(VoiceCommands.load(context), "едем домой");
        assertNotNull(command);
        assertEquals(
                entry.actions,
                SteeringActionStore.decode(
                        CustomVoiceCommandProtocol.configuration(command.action)));
        assertTrue(
                Arrays.asList(VoiceHotwords.fromCommands(VoiceCommands.load(context)).split("/"))
                        .contains("едем домой"));

        entry.phrase = "доброе утро";
        Collections.reverse(entry.actions);
        VoiceCustomCommandStore.save(preferences, entry);
        assertEquals(entry.actions, VoiceCustomCommandStore.load(preferences).get(0).actions);
        assertNull(VoiceCommandCatalog.match(VoiceCommands.load(context), "едем домой"));
        assertNotNull(VoiceCommandCatalog.match(VoiceCommands.load(context), "доброе утро"));
        assertFalse(
                Arrays.asList(VoiceHotwords.fromCommands(VoiceCommands.load(context)).split("/"))
                        .contains("едем домой"));

        entry.enabled = false;
        VoiceCustomCommandStore.save(preferences, entry);
        assertNull(VoiceCommandCatalog.match(VoiceCommands.load(context), "доброе утро"));
        assertEquals(1, VoiceCustomCommandStore.load(preferences).size());
        VoiceCustomCommandStore.remove(preferences, entry.id);
        assertTrue(VoiceCustomCommandStore.load(preferences).isEmpty());
    }

    @Test
    public void requiresAWholePhraseAndKeepsConfiguredActionsInOneSpokenSegment() {
        VoiceCustomCommandStore.Entry entry =
                entry(
                        "едем домой затем отдыхать",
                        "headlights:parking",
                        "drive:COMFORT",
                        "recycle:LOW",
                        "toggle_forced_ev");
        VoiceCustomCommandStore.save(preferences, entry);
        List<VoiceCommandCatalog.Command> catalog = VoiceCommands.load(context);
        assertNotNull(VoiceCommandCatalog.match(catalog, "Едем домой, затем отдыхать!"));
        assertNull(VoiceCommandCatalog.match(catalog, "домой едем затем отдыхать"));
        assertNull(VoiceCommandCatalog.match(catalog, "едем домой"));
        assertNull(VoiceCommandCatalog.match(catalog, "не едем домой затем отдыхать"));
        assertNull(VoiceCommandCatalog.match(catalog, "едем домой затем отдыхать пожалуйста"));
        List<VoiceCommandSequence.Segment> segments =
                VoiceCommandSequence.parse(catalog, entry.phrase);
        assertEquals(1, segments.size());
        assertTrue(segments.get(0).accepted());
        assertTrue(VoiceCommandSequence.responseBudgetMillis(segments) > 20000);
    }

    @Test
    public void navigationInsideACustomListMakesItTheLastSpokenSegment() {
        VoiceCustomCommandStore.save(
                preferences, entry("едем домой", "app:com.demo", "headlights:parking"));
        List<VoiceCommandCatalog.Command> catalog = VoiceCommands.load(context);
        List<VoiceCommandSequence.Segment> segments =
                VoiceCommandSequence.parse(catalog, "едем домой затем включи массаж");
        assertEquals(2, segments.size());
        assertEquals(VoiceCommandSequence.REASON_NAVIGATION, segments.get(0).reason);
        assertTrue(segments.get(1).accepted());
        segments = VoiceCommandSequence.parse(catalog, "включи массаж затем едем домой");
        assertTrue(segments.get(0).accepted());
        assertTrue(segments.get(1).accepted());
    }

    @Test
    public void rejectsDuplicateBuiltInAndUnsupportedPhrasesOrActions() {
        List<VoiceCommandCatalog.Command> catalog = VoiceCommandCatalog.builtIns();
        VoiceCustomCommandStore.Entry first = entry("едем домой", "headlights:parking");
        assertNull(VoiceCustomCommandStore.validate(first, Collections.emptyList(), catalog));
        assertNotNull(
                VoiceCustomCommandStore.validate(
                        entry("Едем, домой!", "wash"), Collections.singletonList(first), catalog));
        assertNotNull(
                VoiceCustomCommandStore.validate(
                        entry("включи габариты", "wash"), Collections.emptyList(), catalog));
        assertNotNull(
                VoiceCustomCommandStore.validate(
                        entry("режим 123", "wash"), Collections.emptyList(), catalog));
        assertNotNull(
                VoiceCustomCommandStore.validate(
                        entry("hello", "wash"), Collections.emptyList(), catalog));
        assertNotNull(
                VoiceCustomCommandStore.validate(
                        entry("едем домой"), Collections.emptyList(), catalog));
        assertNotNull(
                VoiceCustomCommandStore.validate(
                        entry("едем домой", "voice_assistant"), Collections.emptyList(), catalog));
    }

    @Test
    public void damagedDefinitionsNeverBecomePartialActionLists() {
        String malformed =
                "[{\"id\":\"first\",\"phrase\":\"едем домой\",\"actions\":[\"wash\",\"unknown\"]}]";
        assertTrue(VoiceCustomCommandStore.fromJson(malformed).isEmpty());
        assertTrue(VoiceCustomCommandStore.fromJson("broken").isEmpty());
        VoiceCustomCommandStore.Entry entry = entry("едем домой", "wash", "split:999");
        VoiceCustomCommandStore.save(preferences, entry);
        assertNull(VoiceCommandCatalog.match(VoiceCommands.load(context), entry.phrase));
    }

    private static VoiceCustomCommandStore.Entry entry(String phrase, String... actions) {
        VoiceCustomCommandStore.Entry entry = new VoiceCustomCommandStore.Entry();
        entry.phrase = phrase;
        entry.actions.addAll(Arrays.asList(actions));
        return entry;
    }
}
