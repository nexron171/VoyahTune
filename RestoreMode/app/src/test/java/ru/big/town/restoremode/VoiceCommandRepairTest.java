package ru.big.town.restoremode;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class VoiceCommandRepairTest {
    private final List<VoiceCommandCatalog.Command> commands = VoiceCommandCatalog.builtIns();
    private static final String WHEEL_ON = "can:64088000000000000003";
    private static final String WHEEL_OFF = "can:64084000000000000003";

    public VoiceCommandRepairTest() {
        VoiceCommandCatalog.addVehicle(commands, WHEEL_ON, "Обогрев руля",
                "включи обогрев руля", "включить обогрев руля");
        VoiceCommandCatalog.addVehicle(commands, WHEEL_OFF, "Выключить обогрев руля",
                "выключи обогрев руля", "отключи обогрев руля");
        VoiceCommandCatalog.addVehicle(commands, "can:rear-on", "Обогрев заднего стекла",
                "включи обогрев заднего стекла");
    }

    private void accepts(String action, String... phrases) {
        for (String phrase : phrases) {
            VoiceCommandCatalog.Command result = VoiceCommandCatalog.match(commands, phrase);
            assertNotNull(phrase, result);
            assertEquals(phrase, action, result.action);
        }
    }

    private void rejects(String... phrases) {
        for (String phrase : phrases) assertNull(phrase, VoiceCommandCatalog.match(commands, phrase));
    }

    @Test public void reportedInflectionsAndBrokenHeatingPhraseResolve() {
        accepts("drive:COMFORT", "комфорта", "режим комфорта", "включи комфортный режим");
        accepts("energy:SREV", "топлива", "режим топлива");
        accepts(WHEEL_ON, "под грела руль", "подогрев руля", "включи подогрев руль");
    }

    @Test public void morphologyKeepsCommandObjectsAndLevels() {
        accepts("recycle:HIGH", "высокую рекуперацию", "рекуперации высокой");
        accepts("recycle:LOW", "низкую рекуперацию");
        accepts("headlights:on", "ближнего света");
        accepts("headlights:auto", "фара авто");
        accepts("port_cap:fuel", "открой бензобака");
        accepts("can:rear-on", "подогрев заднее стекло");
        rejects("высокую низкую рекуперацию", "подогрев руль батарея", "подогрев", "руль");
    }

    @Test public void joinsUpToThreeAdjacentFragments() {
        accepts("drive:COMFORT", "ком форт", "ком фор та");
        accepts("energy:SREV", "топ лива");
        accepts(WHEEL_ON, "подо грев руля", "под о грев руль");
        accepts("recycle:HIGH", "высокую ре купера цию");
    }

    @Test public void toleratesOneCharacterErrorOnlyInLongVocabularyWords() {
        accepts("drive:COMFORT", "комфарта");
        accepts("energy:SREV", "топливаа");
        accepts(WHEEL_ON, "подгрела руля");
        rejects("спор", "эка", "комф", "топл", "комфртт");
        accepts("recycle:HIGH", "высакая рекупирация");
    }

    @Test public void neverDropsNegationUnknownSpeechOrCommandParts() {
        rejects("не комфорта", "ни комфорта", "не под грела руль", "под не грела руль",
                "комфорта или спорта", "комфорта потом топлива", "я люблю комфорта",
                "под грела руль [unk]", "нет подогрев руль", "неизвестно топлива",
                "комфорта и спорт", "открой лючка", "подогрев руля и стекла");
    }

    @Test public void neverGuessesOnOffOrSwitchVerbs() {
        accepts(WHEEL_OFF, "выключи под грела руль", "отключи подогрев руля");
        accepts("toggle_headlights", "переключи фара");
        accepts("headlights:off", "выключи фара");
        rejects("выключи комфорта", "выключи включи подогрев руля", "включи переключи фара",
                "вы клю чи подогрев руля", "включ подогрев руля", "выклчи подогрев руля");
    }

    @Test public void fuelNumbersRetainOrderSignsAndDecimalSeparators() {
        accepts("fuel_charge:75", "топлива семьдесят три", "топлива 72,5", "топ лива 72.5%");
        accepts("fuel_charge:25", "топлива -80", "топлива минус восемьдесят");
        rejects("топлива 50 80", "топлива 80 80", "топлива восемьдесят восемьдесят",
                "топлива семьдесят два три", "топлива 50/80", "топлива 80-80",
                "топлива семьдесяд", "выключи топлива 80", "не топлива 80");
    }

    @Test public void dynamicAndDestructiveCommandsStayExactOnly() {
        VoiceCommandCatalog.add(commands, "app:demo", "Приложение", "открой комфорт");
        VoiceCommandCatalog.add(commands, "call:demo", "Контакт", "позвони комфорт");
        commands.add(new VoiceCommandCatalog.Command("can:custom", "Своя команда", true, "команда комфорт"));
        accepts("app:demo", "открой комфорт");
        rejects("открой комфорта", "позвони комфорта", "команда комфорта",
                "перезагрузи систем", "закрой все приложени");
    }

    @Test public void exactMatchWinsAndRepairCannotResolveAmbiguousCommands() {
        VoiceCommandCatalog.add(commands, "app:exact", "Точное совпадение", "комфорта");
        accepts("app:exact", "комфорта");
        VoiceCommandCatalog.addVehicle(commands, "other:comfort", "Другое действие", "комфорт");
        rejects("ком фарта", "комфорт");
        VoiceCommandCatalog.addVehicle(commands, "other:fuel", "Другое действие", "топливо 80");
        rejects("топлива 80");
    }

    @Test public void allPublishedVehiclePhrasesRemainRecognizable() {
        for (VoiceCommandCatalog.Command command : commands) {
            for (String phrase : command.phrases) accepts(command.action, phrase);
        }
    }
}
