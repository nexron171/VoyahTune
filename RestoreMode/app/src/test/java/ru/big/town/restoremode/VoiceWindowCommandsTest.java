package ru.big.town.restoremode;

import org.junit.Test;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class VoiceWindowCommandsTest {
    private final List<VoiceCommandCatalog.Command> commands = VoiceCommandCatalog.builtIns();

    private void accepts(String action, String... phrases) {
        for (String phrase : phrases) {
            VoiceCommandCatalog.Command result = VoiceCommandCatalog.match(commands, phrase);
            assertNotNull(phrase, result);
            assertEquals(phrase, action, result.action);
        }
    }

    @Test public void individualWindowsAcceptLocationAndMotionSynonyms() {
        String[][] targets = {
                {"driver", "водительское окно", "водительское стекло", "окно водителя", "переднее левое окно", "окно спереди слева"},
                {"passenger", "окно пассажира", "пассажирское стекло", "окно переднего пассажира", "переднее правое окно", "окно спереди справа"},
                {"rear_left", "заднее левое окно", "заднее левое стекло", "окно сзади слева"},
                {"rear_right", "заднее правое окно", "заднее правое стекло", "окно сзади справа"}
        };
        for (String[] target : targets) for (int i = 1; i < target.length; i++) {
            for (String verb : new String[]{"открой", "открыть", "откройте", "опусти", "опустить", "опустите"})
                accepts("windows:" + target[0] + ":open", verb + " " + target[i], target[i] + " " + verb + " пожалуйста");
            for (String verb : new String[]{"закрой", "закрыть", "закройте", "подними", "поднять", "поднимите"})
                accepts("windows:" + target[0] + ":close", verb + " " + target[i], "пожалуйста " + target[i] + " " + verb);
        }
        accepts("windows:driver:open", "открой только водительское окно", "переднее окно левое открой");
    }

    @Test public void groupsRetainBothRowAndSideMeaning() {
        String[][] targets = {{"front", "передние окна", "оба передних стекла", "окна спереди"},
                {"rear", "задние окна", "оба задних стекла", "окна сзади"},
                {"left", "левые окна", "оба левых стекла", "окна слева"},
                {"right", "правые окна", "оба правых стекла", "окна справа"}};
        for (String[] target : targets) for (int i = 1; i < target.length; i++) {
            accepts("windows:" + target[0] + ":open", "открой " + target[i], "опусти " + target[i]);
            accepts("windows:" + target[0] + ":close", "закрой " + target[i], "подними " + target[i]);
        }
        accepts("windows:rear:open", "открой все задние окна");
        accepts("windows:left:close", "подними все левые стёкла");
        accepts("windows:open", "открой окна", "открой все окна", "опустить стёкла", "окна открыть");
        accepts("windows:close", "закрыть окна", "подними все стёкла", "окна закрыть");
    }

    @Test public void roofShadeAndTwoVentilationTargetsStaySeparate() {
        accepts("sunroof:open", "открой люк", "открыть люк на крыше", "люк открыть");
        accepts("sunroof:close", "закрой люк", "закрыть люк крыши", "люк закройте");
        accepts("sunshade:open", "открой шторку люка", "открыть солнцезащитную шторку", "открой штору");
        accepts("sunshade:close", "закрой шторку", "закрой шторку панорамы", "штору люка закрыть");
        accepts("windows:vent", "проветривание", "включи режим проветривания", "проветри салон",
                "приоткрой окна", "немного открой все окна", "проветривание окон", "слегка опусти стекла");
        accepts("sunroof:vent", "проветривание люка", "приоткрой люк", "открой люк на проветривание",
                "включи проветривание люка", "немного открой люк", "режим проветривания люка");
        accepts("port_cap:fuel", "открой люк бензобака", "открой топливный лючок");
        accepts("port_cap:charge", "открой люк зарядки", "открой лючок зарядного порта");
        accepts("seat:driver:vent:on", "вентиляция сиденья");
        accepts("seat:passenger:vent:2", "вентиляция сиденья пассажира два");
    }

    @Test public void ambiguityPartialTargetsAndNegationDoNotFallThroughToFuzzyMatching() {
        for (String phrase : new String[]{"окна", "люк", "шторка", "открой окно", "подними стекло",
                "открой левое окно", "закрой переднее окно", "открой заднее окно", "открой окно пассажиров",
                "открой окна водителя", "открой все водительское окно", "открой оба окна", "открой передние левые окна",
                "открой водительское правое окно", "открой заднее окно пассажира", "открой левое правое окно",
                "открой переднее заднее окно", "открой окно водителя пассажира", "открой неизвестное окно",
                "не закрывай окна", "не открой люк", "открой и закрой окна", "открой закрой окна",
                "открой водительское и заднее правое окно", "открой люк и шторку", "открой окна люк",
                "приоткрой водительское окно", "приоткрой задние окна", "проветривание задних окон",
                "проветривание шторки", "немного открой шторку", "закрой люк на проветривание",
                "закрой немного окна", "выключи проветривание", "открой окна на 50 процентов", "открой окна на 50%",
                "открой окна %", "окна немного", "открой открой окна", "открой левы окна", "люк бензобака проветривание",
                "открой шторку бензобака", "открой окна если жарко", "открой окно и включи спорт"}) {
            assertNull(phrase, VoiceCommandCatalog.match(commands, phrase));
        }
    }

    @Test public void all24ActionsArePublishedAndBiasBothPositiveAndNegativePhrases() {
        Set<String> actions = new HashSet<>();
        for (VoiceCommandCatalog.Command c : commands) {
            if (!VoiceWindowCommands.isAction(c.action)) continue;
            assertTrue(c.action, actions.add(c.action));
            assertFalse(c.confirm);
            assertTrue(c.fuzzyPhrases.isEmpty());
            for (String phrase : c.phrases) accepts(c.action, phrase);
        }
        assertEquals(24, actions.size());
        String hotwords = VoiceHotwords.fromCommands(commands);
        assertTrue(hotwords.contains("приоткрой люк"));
        assertTrue(hotwords.contains("не приоткрой люк"));
        assertTrue(hotwords.contains("опусти водительское стекло"));
    }

    @Test public void customPhraseCollisionStillRejectsInsteadOfChoosingAnAction() {
        List<VoiceCommandCatalog.Command> all = new ArrayList<>(commands);
        VoiceCommandCatalog.add(all, "can:other", "Другое", "открой водительское окно");
        assertNull(VoiceCommandCatalog.match(all, "открой водительское окно"));
    }
}
