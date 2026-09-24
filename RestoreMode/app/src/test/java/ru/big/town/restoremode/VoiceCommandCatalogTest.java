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
