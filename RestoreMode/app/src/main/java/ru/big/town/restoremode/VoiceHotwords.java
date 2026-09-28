package ru.big.town.restoremode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** sherpa's per-stream hotword syntax: slash-separated phrases, BPE tokenized by the runtime. */
final class VoiceHotwords {
    static String fromCommands(List<VoiceCommandCatalog.Command> commands) {
        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        // Bias rejection words as well: omitting a quiet "не" must not be encouraged by our vocabulary.
        phrases.add("не"); phrases.add("нет"); phrases.add("отмена");
        for (VoiceCommandCatalog.Command command : commands) {
            for (String phrase : command.phrases) {
                String clean = phrase.toLowerCase(Locale.ROOT).replace('ё', 'е')
                        .replaceAll("[^\\p{L}\\p{N} ]", " ").trim().replaceAll("\\s+", " ");
                // The published 500-token Russian vocabulary has no Latin letters or digits.
                // Skip the entire unsupported phrase; never bias a truncated name.
                if (!clean.matches("[а-я ]+") || clean.length() > 160) continue;
                phrases.add(clean);
                // Provide the negative counterpart at the same score as its positive form.
                phrases.add("не " + clean);
            }
        }
        return String.join("/", phrases);
    }
}
