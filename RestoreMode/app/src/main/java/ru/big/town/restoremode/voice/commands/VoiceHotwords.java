package ru.big.town.restoremode.voice.commands;

import java.util.LinkedHashSet;
import java.util.List;

/** sherpa's per-stream hotword syntax: slash-separated phrases, BPE tokenized by the runtime. */
public final class VoiceHotwords {
    /** Phrases longer than this are dropped by the current model vocabulary. */
    private static final int MAX_PHRASE_LENGTH = 160;

    private VoiceHotwords() {}

    public static String fromCommands(List<VoiceCommandCatalog.Command> commands) {
        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        // Bias rejection words as well: omitting a quiet "не" must not be encouraged by our
        // vocabulary.
        phrases.add("не");
        phrases.add("нет");
        phrases.add("отмена");
        for (VoiceCommandCatalog.Command command : commands) {
            for (String phrase : command.phrases) {
                String clean = normalize(phrase);
                // The published 500-token Russian vocabulary has no Latin letters or digits.
                // Skip the entire unsupported phrase; never bias a truncated name.
                if (clean.isEmpty()
                        || clean.length() > MAX_PHRASE_LENGTH
                        || !isModelVocabulary(clean)) {
                    continue;
                }
                phrases.add(clean);
                // Provide the negative counterpart at the same score as its positive form.
                phrases.add("не " + clean);
            }
        }
        return String.join("/", phrases);
    }

    /**
     * Lower-cases, folds "ё" to "е", turns every non-letter into a separator and collapses
     * separators. Equivalent to the published phrase syntax, in one pass: the whole catalog is
     * rebuilt on every settings change and the result is reused by every recording.
     */
    private static String normalize(String phrase) {
        StringBuilder normalized = new StringBuilder(phrase.length());
        for (int index = 0; index < phrase.length(); index++) {
            char symbol = Character.toLowerCase(phrase.charAt(index));
            if (symbol == 'ё') {
                symbol = 'е';
            }
            if (Character.isLetter(symbol) || Character.isDigit(symbol)) {
                normalized.append(symbol);
            } else if (normalized.length() > 0 && normalized.charAt(normalized.length() - 1)
                    != ' ') {
                normalized.append(' ');
            }
        }
        if (normalized.length() > 0 && normalized.charAt(normalized.length() - 1) == ' ') {
            normalized.setLength(normalized.length() - 1);
        }
        return normalized.toString();
    }

    private static boolean isModelVocabulary(String normalized) {
        for (int index = 0; index < normalized.length(); index++) {
            char symbol = normalized.charAt(index);
            if (symbol != ' ' && (symbol < 'а' || symbol > 'я')) {
                return false;
            }
        }
        return true;
    }
}
