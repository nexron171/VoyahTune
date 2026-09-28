package ru.big.town.restoremode;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class VoiceCommandSequenceTest {
    private final List<VoiceCommandCatalog.Command> commands = VoiceCommandCatalog.builtIns();

    private String actions(String text) {
        return actions(VoiceCommandSequence.parse(commands, text));
    }

    private static String actions(List<VoiceCommandSequence.Segment> segments) {
        StringBuilder out = new StringBuilder();
        for (VoiceCommandSequence.Segment segment : segments) {
            if (out.length() > 0) out.append(',');
            out.append(segment.accepted() ? segment.command.action : "reject:" + segment.reason);
        }
        return out.toString();
    }

    @Test public void separatorsRunEverySegmentInSpeechOrder() {
        for (String separator : new String[]{"затем", "потом", "далее", "и"}) {
            assertEquals(separator, "seat:driver:massage:on,wheel_heat:on",
                    actions("включи массаж " + separator + " включи подогрев руля"));
        }
        assertEquals("energy:SREV,drive:COMFORT,seat:driver:massage:on",
                actions("включи режим топливо затем режим комфорт потом включи массаж"));
    }

    @Test public void ambiguousWindowSegmentIsRejectedWhileTheRestRuns() {
        List<VoiceCommandSequence.Segment> segments =
                VoiceCommandSequence.parse(commands, "включи режим топливо затем открой окно потом включи массаж");
        assertEquals(3, segments.size());
        assertTrue(segments.get(0).accepted());
        assertFalse(segments.get(1).accepted());
        assertEquals(VoiceCommandSequence.REASON_WINDOW, segments.get(1).reason);
        assertEquals("открой окно", segments.get(1).text);
        assertTrue(segments.get(2).accepted());
    }

    @Test public void confirmedSegmentIsRejectedWhileTheRestRuns() {
        assertEquals("reject:" + VoiceCommandSequence.REASON_CONFIRM + ",seat:driver:massage:on",
                actions("перезагрузи систему и включи массаж"));
        assertEquals("reject:" + VoiceCommandSequence.REASON_CONFIRM + ",headlights:on",
                actions("закрой все приложения затем включи фары"));
    }

    @Test public void negationRejectsOnlyItsOwnSegment() {
        assertEquals("reject:" + VoiceCommandSequence.REASON_NEGATION + ",headlights:on",
                actions("не включай спорт затем включи фары"));
        assertEquals("reject:" + VoiceCommandSequence.REASON_NEGATION + ",seat:driver:massage:on",
                actions("не надо массаж и включи массаж"));
    }

    @Test public void navigationRunsOnlyAsTheLastSegment() {
        List<VoiceCommandCatalog.Command> all = new ArrayList<>(commands);
        VoiceCommandCatalog.add(all, "app:demo", "Открыть приложение: Демо", "открой демо");
        assertEquals("reject:" + VoiceCommandSequence.REASON_NAVIGATION + ",seat:driver:massage:on",
                actions(VoiceCommandSequence.parse(all, "открой демо затем включи массаж")));
        assertEquals("seat:driver:massage:on,app:demo",
                actions(VoiceCommandSequence.parse(all, "включи массаж затем открой демо")));
    }

    @Test public void onlyThreeSegmentsRunAndTheRestIsRejected() {
        assertEquals("seat:driver:massage:on,wheel_heat:on,headlights:on,reject:"
                        + VoiceCommandSequence.REASON_TOO_LONG,
                actions("включи массаж затем включи подогрев руля затем включи фары затем выключи фары"));
    }

    @Test public void singlePhraseKeepsThePublishedCommandIncludingItsOwnAnd() {
        List<VoiceCommandSequence.Segment> segments =
                VoiceCommandSequence.parse(commands, "включи форс и ви");
        assertEquals(1, segments.size());
        assertEquals("forced_ev:on", actions(segments));
        assertEquals("forced_ev:on,wheel_heat:on",
                actions("включи форс и ви затем подогрев руля"));
    }

    @Test public void conflictingVerbsInsideOneSegmentStayRejected() {
        List<VoiceCommandSequence.Segment> segments =
                VoiceCommandSequence.parse(commands, "включи выключи фары");
        assertEquals(1, segments.size());
        assertFalse(segments.get(0).accepted());
        assertEquals(VoiceCommandSequence.REASON_CHOICE, segments.get(0).reason);
    }

    @Test public void unrecognizedWordsDoNotBecomeCommands() {
        assertEquals("reject:" + VoiceCommandSequence.REASON_UNKNOWN, actions("абвгдежзий"));
        assertEquals("seat:driver:massage:on,reject:" + VoiceCommandSequence.REASON_UNKNOWN,
                actions("включи массаж затем абвгдежзий"));
    }

    @Test public void emptyOrUnrelatedSpeechProducesNoSegments() {
        assertTrue(VoiceCommandSequence.parse(commands, "").isEmpty());
        assertTrue(VoiceCommandSequence.parse(commands, "   ").isEmpty());
        assertTrue(VoiceCommandSequence.parse(commands, null).isEmpty());
    }
}
