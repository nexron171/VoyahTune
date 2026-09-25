package ru.big.town.restoremode;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceFuzzyMatcherTest {
    private final List<VoiceCommandCatalog.Command> commands = VoiceCommandCatalog.builtIns();
    public VoiceFuzzyMatcherTest() {
        VoiceCommandCatalog.addVehicle(commands, "wheel:on", "Обогрев руля", "включи обогрев руля");
        VoiceCommandCatalog.addVehicle(commands, "wheel:off", "Выключить обогрев руля", "выключи обогрев руля");
    }
    private void accepts(String action, String... texts) {
        for (String text : texts) {
            VoiceCommandCatalog.Command found = VoiceCommandCatalog.match(commands, text);
            assertNotNull(text, found); assertEquals(text, action, found.action);
        }
    }
    private void rejects(String... texts) {
        for (String text : texts) assertNull(text, VoiceCommandCatalog.match(commands, text));
    }
    @Test public void countsInsertionDeletionAndSubstitution() {
        assertEquals(1, VoiceFuzzyMatcher.distance("рубля", "руля"));
        assertEquals(3, VoiceFuzzyMatcher.distance("гребля", "руля"));
        assertEquals(5, VoiceFuzzyMatcher.distance("абвгд", "12345"));
    }
    @Test public void reportedErrorsAndWordOrderFindWheel() {
        accepts("wheel:on", "подогрев рубля", "подогрев гребля", "гребля подогрев",
                "включи подогрев рубля", "подо грев гребля");
        accepts("wheel:off", "выключи подогрев гребля");
    }
    @Test public void acceptsSeveralEditsInLongPhraseButKeepsShortWordsStrict() {
        accepts("recycle:HIGH", "высакая рекупирация");
        List<VoiceCommandCatalog.Command> custom = new ArrayList<>();
        VoiceCommandCatalog.addVehicle(custom, "x", "x", "абвгдежзийк лмнопрстуфх");
        assertNotNull(VoiceCommandCatalog.match(custom, "абвгдежзйык лмнопртсух"));
        assertNull(VoiceCommandCatalog.match(custom, "абвгдежыыык лмнопртсух"));
        rejects("спор", "эка", "комф", "комфртт");
    }
    @Test public void protectsNegationVerbsNumbersAndExtraWords() {
        rejects("не подогрев рубля", "ни подогрев гребля", "подогрев гребля или батареи",
                "подогрев гребля завтра", "подогрев рубля рубля", "выклчи подогрев рубля",
                "включи выключи подогрев рубля", "включи переключи подогрев рубля",
                "подогрев 3", "топливо семьдесяд", "подогрев свет",
                "сегодня хорошая погода", "я люблю комфорт", "руля");
        accepts("battery_heat", "подогрев батарея");
    }
    @Test public void declinesTiesAndNearTiesBetweenDifferentActions() {
        VoiceCommandCatalog.addVehicle(commands, "other", "Другое", "обогрев рубка");
        rejects("подогрев рубла");
        VoiceCommandCatalog.addVehicle(commands, "other-wheel", "Другое", "обогрев руля");
        rejects("подогрев гребля");
    }
    @Test public void exactOnlyActionsNeverOptIn() {
        VoiceCommandCatalog.add(commands, "app:test", "x", "открой приложение навигатор");
        commands.add(new VoiceCommandCatalog.Command("destructive", "x", true, true, "перезагрузи систему"));
        rejects("открой приложение навигатар", "перезагрузи систиму");
    }
}
