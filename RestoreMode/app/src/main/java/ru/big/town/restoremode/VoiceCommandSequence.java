package ru.big.town.restoremode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splits one recognition result into ordered segments and classifies each independently.
 * A phrase without separators keeps the single-command behaviour of the caller: rejected
 * segments never suppress the accepted ones, and a rejected segment is never sent.
 */
final class VoiceCommandSequence {
    static final int MAX_SEGMENTS = 3;

    static final String REASON_CONFIRM = "Требует подтверждения";
    static final String REASON_WINDOW = "Не указано, какое окно";
    static final String REASON_SEAT = "Не указано место";
    static final String REASON_NEGATION = "Отрицание в команде";
    static final String REASON_CHOICE = "Неоднозначная команда";
    static final String REASON_UNKNOWN = "Не распознана";
    static final String REASON_NAVIGATION = "Недоступна в последовательности";
    static final String REASON_TOO_LONG = "Больше " + MAX_SEGMENTS + " команд в одной фразе";

    /** Always split speech; these words carry no command meaning of their own. */
    private static final List<String> HARD_SEPARATORS = Arrays.asList("далее", "затем", "потом");
    private static final List<String> NEGATION = Arrays.asList("не", "ни", "нет", "нельзя");
    private static final List<String> CHOICE = Arrays.asList("или", "если", "отмена", "отмени");
    private static final List<String> ON_VERBS = Arrays.asList("включи", "включить", "включите", "установи", "поставь");
    private static final List<String> OFF_VERBS = Arrays.asList("выключи", "выключить", "выключите", "отключи", "отключить", "отключите");

    /** One spoken fragment: accepted with its command, or rejected with a visible reason. */
    static final class Segment {
        final String text;
        final VoiceCommandCatalog.Command command;
        final String reason;

        private Segment(String text, VoiceCommandCatalog.Command command, String reason) {
            this.text = text;
            this.command = command;
            this.reason = reason;
        }

        boolean accepted() { return command != null; }
    }

    private VoiceCommandSequence() { }

    static List<Segment> parse(List<VoiceCommandCatalog.Command> commands, String text) {
        List<Segment> out = new ArrayList<>();
        String normalized = VoiceCommandCatalog.normalize(text);
        if (normalized.isEmpty()) return out;
        // A phrase that is one published command stays one command, separators included
        // ("включи форс и ви" must not split around its own "и").
        VoiceCommandCatalog.Command whole = VoiceCommandCatalog.match(commands, normalized);
        if (whole != null) {
            out.add(new Segment(normalized, whole, null));
            return classify(out);
        }
        for (String chunk : splitOn(normalized, HARD_SEPARATORS)) {
            for (String part : splitOnAnd(commands, chunk)) {
                VoiceCommandCatalog.Command command = VoiceCommandCatalog.match(commands, part);
                out.add(command != null ? new Segment(part, command, null)
                        : new Segment(part, null, reason(part)));
            }
        }
        return classify(out);
    }

    /** Debug-only sample for the animation preview activity; it never sends a command. */
    static List<Segment> sample() {
        return parse(VoiceCommandCatalog.builtIns(),
                "примени настройки затем открой окно затем включи массаж");
    }

    static boolean isNavigation(String action) {
        return "system_back".equals(action) || "open_voyahtune".equals(action)
                || action.startsWith("app:") || action.startsWith("split:") || action.startsWith("call:");
    }

    /** "и" splits only where at least one side already reads as a command on its own. */
    private static List<String> splitOnAnd(List<VoiceCommandCatalog.Command> commands, String chunk) {
        String[] words = chunk.split(" ");
        List<Integer> cuts = new ArrayList<>();
        for (int at = 0; at < words.length; at++) {
            if (!"и".equals(words[at])) continue;
            String left = join(words, 0, at), right = join(words, at + 1, words.length);
            if (left.isEmpty() || right.isEmpty()) continue;
            if (VoiceCommandCatalog.match(commands, left) != null
                    || VoiceCommandCatalog.match(commands, right) != null) cuts.add(at);
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int at : cuts) {
            parts.add(join(words, start, at));
            start = at + 1;
        }
        parts.add(join(words, start, words.length));
        parts.removeIf(String::isEmpty);
        return parts;
    }

    private static List<String> splitOn(String text, List<String> separators) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (separators.contains(word)) {
                if (current.length() > 0) parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) parts.add(current.toString());
        return parts;
    }

    private static String join(String[] words, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int at = from; at < to; at++) {
            if (out.length() > 0) out.append(' ');
            out.append(words[at]);
        }
        return out.toString();
    }

    /** Rejections that need an executor, a place or an unambiguous wording are never guessed. */
    private static List<Segment> classify(List<Segment> segments) {
        List<Segment> out = new ArrayList<>();
        for (int at = 0; at < segments.size(); at++) {
            Segment segment = segments.get(at);
            if (segment.command == null) { out.add(segment); continue; }
            String reason = null;
            if (at >= MAX_SEGMENTS) reason = REASON_TOO_LONG;
            else if (segment.command.confirm) reason = REASON_CONFIRM;
            else if (isNavigation(segment.command.action) && at != segments.size() - 1) {
                reason = REASON_NAVIGATION;
            }
            out.add(reason == null ? segment : new Segment(segment.text, null, reason));
        }
        return out;
    }

    private static String reason(String part) {
        boolean on = false, off = false;
        for (String word : part.split(" ")) {
            if (NEGATION.contains(word)) return REASON_NEGATION;
            if (CHOICE.contains(word)) return REASON_CHOICE;
            on |= ON_VERBS.contains(word);
            off |= OFF_VERBS.contains(word);
        }
        // Conflicting verbs inside one segment remain a rejected command, never two actions.
        if (on && off) return REASON_CHOICE;
        if (VoiceWindowCommands.mentionsWindow(part)) return REASON_WINDOW;
        if (mentionsSeat(part)) return REASON_SEAT;
        return REASON_UNKNOWN;
    }

    private static boolean mentionsSeat(String part) {
        for (String word : part.split(" ")) {
            if (word.startsWith("массаж") || word.startsWith("сидень") || word.startsWith("сиден")
                    || word.startsWith("кресл") || word.startsWith("подогрев")
                    || word.startsWith("обогрев") || word.startsWith("вентиляц")
                    || word.startsWith("обдув") || word.startsWith("водител")
                    || word.startsWith("пассажир")) return true;
        }
        return false;
    }
}
