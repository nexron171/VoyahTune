package ru.big.town.restoremode;

import java.util.*;

/** Last-resort, bounded edit-distance matching of trusted vehicle phrases only. */
final class VoiceFuzzyMatcher {
    private static final int NONE = 1000;
    private static final Set<String> REJECT = new HashSet<>(Arrays.asList(
            "не", "ни", "нет", "нельзя", "отмена", "отмени", "или", "и", "потом", "затем", "если"));
    private static final Set<String> FILLER = new HashSet<>(Arrays.asList("режим", "режима", "на", "пожалуйста"));
    private static final Set<String> FIXED = new HashSet<>(Arrays.asList(
            "прогрей", "прогреть", "оставь", "поднять", "подними", "поднимите", "минус", "плюс"));

    static final class Phrase {
        final String intent;
        final String[] words;
        final List<List<String>> forms;
        final int letters;
        Phrase(String intent, List<String> words) {
            this.intent = intent;
            this.words = words.toArray(new String[0]);
            forms = new ArrayList<>();
            int count = 0;
            for (String word : words) { forms.add(VoiceCommandRepair.variants(word)); count += word.length(); }
            letters = count;
        }
    }

    static Phrase prepare(String text) {
        String normalized = VoiceCommandRepair.normalize(text, false);
        if (normalized == null) return null;
        List<String> words = new ArrayList<>();
        String intent = null;
        for (String word : normalized.split("\\s+")) {
            if (REJECT.contains(word) || !word.matches("[а-я]+")) return null;
            if (FILLER.contains(word)) continue;
            String action = action(word);
            if (action != null) {
                if (intent != null && !intent.equals(action)) return null;
                intent = action;
            } else {
                // Misspelt on/off/switch must never be corrected into a different operation.
                if (word.contains("ключ") || word.contains("блок") || word.startsWith("вкл") || word.startsWith("выкл")
                        || word.startsWith("откл") || word.startsWith("перекл")) return null;
                words.add(word);
            }
        }
        if (words.isEmpty() || words.size() > 8) return null;
        return new Phrase(intent == null ? "on" : intent, words);
    }

    private static String action(String word) {
        if (Arrays.asList("включи", "включить", "включите", "установи", "поставь").contains(word)) return "on";
        if (Arrays.asList("выключи", "выключить", "выключите", "отключи", "отключить", "отключите").contains(word)) return "off";
        if (Arrays.asList("переключи", "переключить", "переключите").contains(word)) return "switch";
        if (Arrays.asList("заблокировать", "заблокируй", "заблокируйте").contains(word)) return "lock";
        if (Arrays.asList("разблокировать", "разблокируй", "разблокируйте").contains(word)) return "unlock";
        if (Arrays.asList("открой", "открыть", "откройте").contains(word)) return "open";
        if (Arrays.asList("закрой", "закрыть").contains(word)) return "close";
        return null;
    }

    static VoiceCommandCatalog.Command match(List<VoiceCommandCatalog.Command> commands, String text) {
        Phrase input = prepare(text);
        if (input == null) return null;
        Map<String, Integer> scores = new HashMap<>();
        Map<String, VoiceCommandCatalog.Command> actions = new HashMap<>();
        Map<String, Boolean> eligible = new HashMap<>();
        for (VoiceCommandCatalog.Command command : commands) {
            for (Phrase phrase : command.fuzzyPhrases) {
                int score = score(input, phrase);
                if (score < scores.getOrDefault(command.action, NONE)) {
                    scores.put(command.action, score); actions.put(command.action, command);
                    eligible.put(command.action, score <= budget(input, phrase));
                } else if (score == scores.getOrDefault(command.action, NONE) && score <= budget(input, phrase)) {
                    eligible.put(command.action, true);
                }
            }
        }
        String best = null;
        int first = NONE, second = NONE;
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            int score = entry.getValue();
            if (score < first) { second = first; first = score; best = entry.getKey(); }
            else if (score < second) second = score;
        }
        // Distinct actions need a clear winner. Aliases of the same action don't compete.
        return Boolean.TRUE.equals(eligible.get(best)) && first <= 5 && second - first >= 2 ? actions.get(best) : null;
    }

    private static int budget(Phrase input, Phrase target) {
        if (input.words.length == 1) return input.letters >= 6 && target.letters >= 6 ? 1 : 0;
        return Math.min(5, Math.min(input.letters, target.letters) / 3);
    }

    private static int score(Phrase input, Phrase target) {
        int n = input.words.length;
        if (!input.intent.equals(target.intent) || n != target.words.length) return NONE;
        int[][] costs = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                String a = input.words[i], b = target.words[j];
                int cost = a.equals(b) ? 0 : NONE;
                if (cost != 0 && !VoiceCommandRepair.known(a) && !FIXED.contains(a) && !FIXED.contains(b)) {
                    for (String form : target.forms.get(j)) {
                        if (Math.min(a.length(), form.length()) < 4) continue;
                        int distance = distance(a, form);
                        if (distance <= Math.max(a.length(), form.length()) / 2) cost = Math.min(cost, distance);
                    }
                }
                costs[i][j] = cost;
            }
        }
        // Minimum-cost one-to-one alignment permits word order changes, not missing/extra words.
        int[] dp = new int[1 << n]; Arrays.fill(dp, NONE); dp[0] = 0;
        for (int mask = 0; mask < dp.length; mask++) {
            int i = Integer.bitCount(mask);
            if (i == n || dp[mask] > 7) continue;
            for (int j = 0; j < n; j++) if ((mask & (1 << j)) == 0) {
                int next = mask | (1 << j);
                dp[next] = Math.min(dp[next], dp[mask] + costs[i][j]);
            }
        }
        int result = dp[dp.length - 1];
        return result <= 7 ? result : NONE;
    }

    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1], current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) current[j] = Math.min(
                    Math.min(previous[j] + 1, current[j - 1] + 1),
                    previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] swap = previous; previous = current; current = swap;
        }
        return previous[b.length()];
    }
}
