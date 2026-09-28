package ru.big.town.restoremode;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class VoiceSeatCommandsTest {
    private final List<VoiceCommandCatalog.Command> commands = VoiceCommandCatalog.builtIns();
    private void accepts(String action, String... phrases) {
        for (String phrase : phrases) {
            VoiceCommandCatalog.Command result = VoiceCommandCatalog.match(commands, phrase);
            assertNotNull(phrase, result);
            assertEquals(phrase, action, result.action);
        }
    }

    @Test public void allFunctionsDefaultToDriverAndHaveSeparatePassengerActions() {
        String[][] cases = {{"massage:on", "массаж"}, {"massage:off", "выключи массаж"},
                {"massage:waves", "массаж волны"}, {"massage:rollers", "роликовый массаж"},
                {"heat:on", "подогрев сиденья"}, {"heat:off", "отключи обогрев кресла"},
                {"vent:on", "вентиляция сиденья"}, {"vent:off", "выключить обдув кресла"}};
        for (String[] c : cases) {
            accepts("seat:driver:" + c[0], c[1], c[1] + " водителя", "у водителя " + c[1]);
            accepts("seat:passenger:" + c[0], c[1] + " пассажира", "у пассажира " + c[1],
                    "для переднего пассажира " + c[1]);
        }
    }

    @Test public void allLevelsAcceptDigitsWordsOrdinalsAndBothSeats() {
        String[] numbers = {"один", "два", "три"}, ordinals = {"первый", "второй", "третий"};
        String[][] functions = {{"massage", "массаж"}, {"heat", "подогрев сиденья"}, {"vent", "вентиляция сиденья"}};
        for (String[] f : functions) for (int n = 1; n <= 3; n++) {
            for (String side : new String[]{"driver", "passenger"}) {
                String name = f[1] + " " + (side.equals("driver") ? "водителя" : "пассажира");
                accepts("seat:" + side + ":" + f[0] + ":" + n, name + " " + n,
                        name + " на " + numbers[n - 1], name + " " + ordinals[n - 1] + " уровень",
                        "поставь " + ordinals[n - 1] + " уровень " + name);
            }
            accepts("seat:driver:" + f[0] + ":" + n, f[1] + " " + numbers[n - 1]);
        }
    }

    @Test public void recognizesSeatAdjectivesAndNaturalWordOrder() {
        accepts("seat:passenger:heat:3", "подогрев пассажирского сиденья три",
                "три подогрев сиденья пассажира пожалуйста", "включи подогрев пассажира на 3");
        accepts("seat:driver:vent:on", "включи вентиляцию водительского кресла");
        accepts("seat:passenger:massage:waves", "включи массаж пассажира волнами", "волны пассажира");
        accepts("seat:driver:massage:rollers", "ролики", "включи роликовый массаж водителя");
        accepts("seat:passenger:massage:2", "интенсивность массажа пассажира 2");
        accepts("wheel_heat:on", "обогрев руля", "подогрев руля", "включи обогрев руля");
        accepts("wheel_heat:off", "отключи подогрев руля", "выключи обогрев руля");
    }

    @Test public void rejectsAmbiguityUnsupportedSeatsAndInvalidLevelsWithoutFuzzyFallback() {
        for (String phrase : new String[]{"не включи массаж", "не массаж пассажира", "включи выключи массаж",
                "массаж водителя пассажира", "массаж обоих сидений", "массаж заднего пассажира",
                "подогрев заднего сиденья", "массаж пассажира четыре", "массаж 0", "массаж 4", "массаж -1",
                "массаж 1.0", "массаж 1,2", "массаж 1/2", "массаж 1 1", "массаж один два",
                "массаж волны ролики", "массаж волны два", "выключи массаж два", "подогрев сиденья волны",
                "массаж и вентиляция", "подогрев", "подогрев сиденья неизвестно", "массаж уровень",
                "вы клю чи массаж", "массаж пасажира", "подогрев сиденья переднего", "подогрев руля пассажира"}) {
            assertNull(phrase, VoiceCommandCatalog.match(commands, phrase));
        }
    }

    @Test public void duplicateExternalPhraseRemainsAmbiguous() {
        List<VoiceCommandCatalog.Command> all = new ArrayList<>(commands);
        VoiceCommandCatalog.add(all, "can:other", "Другое", "массаж пассажира два");
        assertNull(VoiceCommandCatalog.match(all, "массаж пассажира два"));
    }

    @Test public void publishesAllActionsWithExplicitTargetAndRussianHotwords() {
        long seats = commands.stream().filter(c -> VoiceSeatCommands.isAction(c.action)).count();
        assertEquals(34, seats);
        for (VoiceCommandCatalog.Command command : commands) {
            if (!VoiceSeatCommands.isAction(command.action)) continue;
            assertTrue(command.title, command.title.contains(command.action.contains(":passenger:") ? "пассажира" : "водителя"));
        }
        String hotwords = VoiceHotwords.fromCommands(commands);
        assertTrue(hotwords.contains("массаж пассажира два"));
        assertTrue(hotwords.contains("не массаж пассажира два"));
    }
}
