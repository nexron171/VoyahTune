package ru.big.town.restoremode.voice.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class VoiceHotwordsTest {
    @Test
    public void dictionaryUsesCatalogAndIncludesNegativePhrasesWithoutSyntaxInjection() {
        List<VoiceCommandCatalog.Command> commands =
                Arrays.asList(
                        new VoiceCommandCatalog.Command(
                                "x", "x", false, "Подогрев руля", "подогрев руля"),
                        new VoiceCommandCatalog.Command(
                                "y",
                                "y",
                                false,
                                "Открой Навигатор",
                                "имя/два :99",
                                "топливо 70",
                                "открой Spotify"));
        String hotwords = VoiceHotwords.fromCommands(commands);
        List<String> phrases = Arrays.asList(hotwords.split("/"));
        assertTrue(phrases.contains("подогрев руля"));
        assertTrue(phrases.contains("не подогрев руля"));
        assertTrue(phrases.contains("открой навигатор"));
        assertTrue(phrases.contains("не"));
        assertFalse(hotwords.contains(":"));
        assertFalse(hotwords.contains("spotify"));
        assertFalse(hotwords.contains("70"));
        assertEquals(1, phrases.stream().filter("подогрев руля"::equals).count());
    }

    @Test
    public void caseSpacingAndYoFoldingMatchTheModelVocabulary() {
        String tooLong = "повтори" + " слово".repeat(40);
        List<VoiceCommandCatalog.Command> commands =
                Arrays.asList(
                        new VoiceCommandCatalog.Command(
                                "seat",
                                "seat",
                                false,
                                "  ВКЛЮЧИ   ПОДОГРЕВ—СИДЕНЬЯ!  ",
                                "включи ёлку",
                                "   ",
                                tooLong));
        List<String> phrases =
                Arrays.asList(VoiceHotwords.fromCommands(commands).split("/"));

        assertTrue(
                "case, dashes, punctuation and repeated spaces are folded",
                phrases.contains("включи подогрев сиденья"));
        assertTrue(
                "both spellings of one word reach a single form",
                phrases.contains("включи елку"));
        assertTrue(phrases.contains("не включи елку"));
        assertFalse("a phrase without letters biases nothing", phrases.contains(""));
        assertFalse(
                "an unsupported phrase is dropped whole, never truncated to a biasing fragment",
                phrases.stream().anyMatch(phrase -> phrase.startsWith("повтори ")));
    }
}
