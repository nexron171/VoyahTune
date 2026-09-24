package ru.big.town.restoremode;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class VoiceCommandCatalogTest {
    private final List<VoiceCommandCatalog.Command> commands = VoiceCommandCatalog.builtIns();
    private void assertAction(String action, String... phrases) {
        for (String phrase : phrases) {
            VoiceCommandCatalog.Command result = VoiceCommandCatalog.match(commands, phrase);
            assertNotNull(phrase, result);
            assertEquals(phrase, action, result.action);
        }
    }
    @Test public void sportAcceptsAllRequestedFormsAndWordOrder() {
        assertAction("drive:SPORT", "включи спорт", "спорт", "спорт режим", "включи спорт режим",
                "включи режим спорт", "пожалуйста, режим СПОРТ включи!", "переключи на спортивный режим");
    }
    @Test public void everyDriveModePublishesAndRecognizesBothModeWordPositions() {
        String[][] modes = {{"SPORT", "спорт"}, {"ECO", "эко"}, {"COMFORT", "комфорт"},
                {"OUTING", "внедорожный"}, {"SNOW", "снег"}, {"INDIVIDUAL", "индивидуальный"}};
        for (String[] mode : modes) {
            String action = "drive:" + mode[0], name = mode[1];
            assertAction(action, "режим " + name, name + " режим",
                    "включить режим " + name, "включить " + name + " режим",
                    "переключи на режим " + name, "переключи на " + name + " режим");
            VoiceCommandCatalog.Command command = VoiceCommandCatalog.match(commands, name);
            assertTrue(action, command.phrases.contains("режим " + name));
            assertTrue(action, command.phrases.contains(name + " режим"));
        }
    }
    @Test public void headlightsHaveThreeDistinctIntents() {
        assertAction("headlights:on", "ближний свет", "включи ближний свет", "включить фары");
        assertAction("headlights:off", "выключи фары", "отключи ближний свет");
        assertAction("headlights:auto", "фары авто", "включи авто свет");
    }
    @Test public void fuelChargeAcceptsWordsDigitsAndOptionalPercent() {
        assertAction("fuel_charge:80", "топливо восемьдесят", "Топливо 80", "топливо 80%",
                "включи режим топливо на восемьдесят процентов пожалуйста");
        assertAction("fuel_charge:25", "топливо двадцать пять", "топливо 25 процентов");
        assertAction("fuel_charge:50", "поставь топливо пятьдесят", "топливо 50");
        assertAction("fuel_charge:75", "переключи на топливо семьдесят пять");
        assertEquals("Топливо: поддерживать 80% (SREV)",
                VoiceCommandCatalog.match(commands, "топливо восемьдесят").title);
        assertAction("energy:SREV", "топливо");
    }
    @Test public void energyModesUseHybridForRevAndFuelForSrevWithModeInEitherPosition() {
        String[][] modes = {{"EV", "электро"}, {"REV", "гибрид"}, {"SREV", "топливо"},
                {"SREV", "сохранение заряда"}};
        for (String[] mode : modes) {
            String action = "energy:" + mode[0], name = mode[1];
            assertAction(action, name, "режим " + name, name + " режим", "включи режим " + name,
                    "включить режим " + name, "переключи на режим " + name);
            VoiceCommandCatalog.Command command = VoiceCommandCatalog.match(commands, name);
            assertTrue(command.phrases.contains("режим " + name));
            assertTrue(command.phrases.contains(name + " режим"));
        }
        assertAction("energy:REV", "гибридный режим", "включи гибрид");
        assertAction("energy:SREV", "топливный режим", "включи топливо");
        assertAction("fuel_charge:80", "режим топливо восемьдесят", "включи режим топливо 80");
        for (String phrase : new String[]{"гибрид 80", "режим гибрид 80", "гибрид топливо", "электро гибрид"}) {
            assertNull(phrase, VoiceCommandCatalog.match(commands, phrase));
        }
        for (VoiceCommandCatalog.Command command : commands) {
            if (command.action.equals("energy:REV")) {
                for (String phrase : command.phrases) assertFalse(phrase.contains("топлив"));
            }
        }
    }
    @Test public void fuelChargeRoundsAndClampsBeforeShowingTheResult() {
        assertAction("fuel_charge:70", "топливо семьдесят два", "топливо 72");
        assertAction("fuel_charge:75", "топливо семьдесят три", "топливо 73", "топливо 72,5", "топливо 72.5");
        assertAction("fuel_charge:25", "топливо ноль", "топливо нуль", "топливо десять", "топливо 0", "топливо -80",
                "топливо минус восемьдесят", "топливо минус 80", "топливо -999999999999999999999999");
        assertAction("fuel_charge:80", "топливо восемьдесят три", "топливо девяносто девять",
                "топливо сто", "топливо сто двадцать три", "топливо двести",
                "топливо тысяча", "топливо две тысячи", "топливо миллион",
                "топливо 999999999999999999999999");
        assertEquals("Топливо: поддерживать 75% (SREV)",
                VoiceCommandCatalog.match(commands, "топливо семьдесят три").title);
    }
    @Test public void fuelChargeRejectsNegationMultipleNumbersAndUnrelatedWords() {
        for (String phrase : new String[]{"не включи топливо восемьдесят", "выключи топливо 80",
                "топливо 80 и спорт", "топливо 50 или 80", "топливо 50 80", "топливо 80 80",
                "топливо восемьдесят восемьдесят", "топливо пять семьдесят", "топливо 80-80",
                "топливо 80 80%", "топливо 80 литров", "топливо неизвестно восемьдесят",
                "топливо [unk] восемьдесят", "топливо восемь десятков", "топливо процентов",
                "топливо минус", "топливо двадцать десять", "топливо сто ноль",
                "топливо тысяча миллион", "топливо 50/80"}) {
            assertNull(phrase, VoiceCommandCatalog.match(commands, phrase));
        }
    }
    @Test public void numericFuelCommandDoesNotWinOverAnAmbiguousCustomPhrase() {
        List<VoiceCommandCatalog.Command> all = new ArrayList<>(commands);
        VoiceCommandCatalog.add(all, "can:other", "Другая команда", "топливо 80");
        assertNull(VoiceCommandCatalog.match(all, "топливо 80"));
    }
    @Test public void outingAcceptsCountryOffRoadAndRaiseSuspensionPhrases() {
        for (String name : new String[]{"загород", "загородный", "внедорожье", "внедорожный"}) {
            assertAction("drive:OUTING", name, "режим " + name, name + " режим",
                    "включи " + name, "включи режим " + name, "включи " + name + " режим",
                    "включить режим " + name, "переключи на " + name, "поставь " + name);
        }
        assertAction("drive:OUTING", "поднять подвеску", "подними подвеску", "поднимите подвеску",
                "пожалуйста подними подвеску");
        assertNull(VoiceCommandCatalog.match(commands, "не поднимай подвеску"));
    }
    @Test public void shortSwitchPhrasesRemainDistinctFromSettingHeadlights() {
        assertAction("toggle_headlights", "переключи фары", "переключить фары",
                "фары переключи пожалуйста", "переключите фары");
        assertAction("toggle_headlights_auto", "переключи фары авто", "переключить фары авто",
                "переключи авто свет", "переключить авто свет");
        assertAction("headlights:on", "фары", "включи фары", "включить фары");
        assertAction("headlights:off", "выключи фары", "выключить фары");
        assertAction("headlights:auto", "фары авто", "включи фары авто");
    }
    @Test public void portCapsAcceptOpenAndShortPhrasesWithoutUnlockingConnector() {
        assertAction("port_cap:fuel", "открой бензобак", "открыть бензобак", "бензобак",
                "открой люк бензобака", "открой лючок бензобака", "люк бензобака", "лючок бензобака",
                "откройте топливный лючок", "топливный люк", "люк бака", "пожалуйста бензобака лючок открой");
        assertAction("port_cap:charge", "открой зарядку", "открыть зарядку", "зарядка", "зарядку",
                "открой люк зарядки", "открой лючок зарядки", "люк зарядки", "лючок зарядки",
                "зарядный люк", "зарядный лючок", "откройте лючок для зарядки", "люк зарядного порта");
        for (String phrase : new String[]{"люк", "лючок", "открой люк", "открой лючок",
                "не открывай бензобак", "не открой бензобак", "не зарядка", "закрой бензобак",
                "закрой зарядку", "открой бензобак и зарядку", "бензобак зарядка",
                "разблокируй зарядку", "разблокируй зарядный разъем", "открой люк крыши"}) {
            assertNull(phrase, VoiceCommandCatalog.match(commands, phrase));
        }
        assertAction("energy:SREV", "топливо");
        assertAction("fuel_charge:80", "топливо 80");
    }
    @Test public void modesAreSelectedDirectlyAndCyclePhrasesAreRejected() {
        for (String phrase : new String[]{"переключи эко и комфорт", "переключи эко и спорт",
                "переключи режим эко и спорт", "переключи электро и топливо",
                "переключи низкую и высокую рекуперацию", "включи эко и спорт", "эко и спорт"}) {
            assertNull(phrase, VoiceCommandCatalog.match(commands, phrase));
        }
        assertAction("drive:ECO", "включи режим эко");
        assertAction("drive:COMFORT", "переключи на комфорт");
        assertAction("energy:EV", "включи электро");
        assertAction("recycle:HIGH", "высокая рекуперация");
    }
    @Test public void explicitOnAndOffAreNeverToggles() {
        assertAction("forced_ev:on", "включи принудительный электрорежим", "включи форс и ви");
        assertAction("forced_ev:off", "выключи принудительный электрорежим", "отключи форс и ви");
        assertAction("pedestrian:on", "включи звук пешеходов");
        assertAction("pedestrian:off", "выключи звук пешеходов");
    }
    @Test public void refusesNegationQuestionsUnrelatedSpeechAndMultipleCommands() {
        for (String text : new String[]{"", "пожалуйста", "не включи спорт", "не надо спорт",
                "спорт или комфорт", "спорт и комфорт", "спорт потом эко", "я люблю спорт",
                "ты включил спорт", "включи спорт и выключи фары", "выключи спорт", "[unk] спорт",
                "включи переключи фары", "переключи выключи фары", "выключи включи фары",
                "не переключи фары"}) {
            assertNull(text, VoiceCommandCatalog.match(commands, text));
        }
    }
    @Test public void everyPublishedPhraseResolvesUnambiguously() {
        for (VoiceCommandCatalog.Command command : commands) {
            for (String phrase : command.phrases) assertAction(command.action, phrase);
        }
    }
    @Test public void duplicateAppNamesDoNotPickAnArbitraryApp() {
        List<VoiceCommandCatalog.Command> all = new ArrayList<>(commands);
        VoiceCommandCatalog.add(all, "app:a", "Первое", "открой музыку");
        VoiceCommandCatalog.add(all, "app:b", "Второе", "открой музыку");
        assertNull(VoiceCommandCatalog.match(all, "открой музыку"));
    }
    @Test public void removedActionsAreNotOfferedOrRecognized() {
        for (VoiceCommandCatalog.Command command : commands) {
            assertFalse(command.action.startsWith("theme:"));
            assertFalse(command.action.startsWith("floating_back:"));
            assertNotEquals("toggle_forced_ev", command.action);
            assertNotEquals("toggle_pedestrian_sound", command.action);
        }
        assertNull(VoiceCommandCatalog.match(commands, "светлая тема"));
        assertNull(VoiceCommandCatalog.match(commands, "включи плавающие кнопки"));
    }
}
