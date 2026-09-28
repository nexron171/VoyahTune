package ru.big.town.restoremode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Ordered number parsing: never drop unknown words or collapse repeated/multiple numbers. */
final class VoiceFuelCommand {
    static final String PREFIX = "fuel_charge:";
    private static final String[] SMALL = {"ноль", "один", "два", "три", "четыре", "пять",
            "шесть", "семь", "восемь", "девять", "десять", "одиннадцать", "двенадцать",
            "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать",
            "восемнадцать", "девятнадцать"};
    private static final String[] TENS = {"", "", "двадцать", "тридцать", "сорок", "пятьдесят",
            "шестьдесят", "семьдесят", "восемьдесят", "девяносто"};
    private static final String[] HUNDREDS = {"", "сто", "двести", "триста", "четыреста",
            "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот"};
    private static final List<String> FILLER = Arrays.asList("включи", "включить", "включите",
            "установи", "поставь", "режим", "режима", "на", "пожалуйста",
            "переключи", "переключите", "переключить");

    static VoiceCommandCatalog.Command match(String text) {
        // Preserve signs and decimal separators until the number is parsed.
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replace('−', '-').replaceAll("[^\\p{L}\\p{N}+.,%\\- ]", " ")
                .replaceAll("(?<![0-9])[.,]|[.,](?![0-9])", " ").trim();
        List<String> tokens = new ArrayList<>();
        int fuel = 0;
        for (String word : normalized.split("\\s+")) {
            if (word.equals("топливо")) fuel++;
            else if (!word.isEmpty() && !FILLER.contains(word)) tokens.add(word);
        }
        if (fuel != 1 || tokens.isEmpty()) return null;
        String last = tokens.get(tokens.size() - 1);
        if (Arrays.asList("процент", "процента", "процентов", "%").contains(last)) {
            tokens.remove(tokens.size() - 1);
        } else if (last.endsWith("%")) {
            tokens.set(tokens.size() - 1, last.substring(0, last.length() - 1));
        }
        BigDecimal number = parseNumber(tokens);
        if (number == null) return null;
        int percent = number.max(BigDecimal.valueOf(25)).min(BigDecimal.valueOf(80))
                .divide(BigDecimal.valueOf(5), 0, RoundingMode.HALF_UP).intValue() * 5;
        return command(percent);
    }

    static VoiceCommandCatalog.Command command(int percent) {
        String spoken = TENS[percent / 10] + (percent % 10 == 0 ? "" : " " + SMALL[percent % 10]);
        return new VoiceCommandCatalog.Command(PREFIX + percent,
                "Топливо: поддерживать " + percent + "% (SREV)", false,
                "топливо " + spoken, "топливо " + percent,
                "включи топливо " + spoken + " процентов");
    }

    private static BigDecimal parseNumber(List<String> tokens) {
        if (tokens.isEmpty()) return null;
        if (tokens.size() == 1 && tokens.get(0).matches("[+-]?[0-9]+([.,][0-9]+)?")) {
            return new BigDecimal(tokens.get(0).replace(',', '.'));
        }
        boolean negative = tokens.get(0).equals("минус");
        if (negative || tokens.get(0).equals("плюс")) tokens = tokens.subList(1, tokens.size());
        if (tokens.isEmpty()) return null;
        if (tokens.size() == 1 && tokens.get(0).matches("[0-9]+([.,][0-9]+)?")) {
            BigDecimal value = new BigDecimal(tokens.get(0).replace(',', '.'));
            return negative ? value.negate() : value;
        }
        long total = 0;
        int start = 0, previousScale = Integer.MAX_VALUE;
        for (int i = 0; i < tokens.size(); i++) {
            String word = tokens.get(i);
            int scale = Arrays.asList("тысяча", "тысячи", "тысяч").contains(word) ? 1000
                    : Arrays.asList("миллион", "миллиона", "миллионов").contains(word) ? 1000000
                    : Arrays.asList("миллиард", "миллиарда", "миллиардов").contains(word) ? 1000000000 : 0;
            if (scale == 0) continue;
            if (scale >= previousScale) return null;
            Integer group = i == start ? (start == 0 ? 1 : null) : parseGroup(tokens.subList(start, i));
            if (group == null || group == 0) return null;
            total += (long) group * scale;
            previousScale = scale;
            start = i + 1;
        }
        if (start < tokens.size()) {
            Integer group = parseGroup(tokens.subList(start, tokens.size()));
            if (group == null || (start > 0 && group == 0)) return null;
            total += group;
        }
        return BigDecimal.valueOf(negative ? -total : total);
    }

    /** Russian cardinal group 0..999, in descending place order. */
    private static Integer parseGroup(List<String> tokens) {
        int at = 0, value = 0;
        int hundreds = Arrays.asList(HUNDREDS).indexOf(tokens.get(at));
        if (hundreds > 0) { value = hundreds * 100; at++; }
        if (at < tokens.size()) {
            int tens = Arrays.asList(TENS).indexOf(tokens.get(at));
            if (tens >= 2) {
                value += tens * 10;
                at++;
                if (at < tokens.size()) {
                    int unit = small(tokens.get(at));
                    if (unit < 1 || unit > 9) return null;
                    value += unit;
                    at++;
                }
            } else {
                int small = small(tokens.get(at));
                if (small < 0 || (value > 0 && small == 0)) return null;
                value += small;
                at++;
            }
        }
        return at == tokens.size() ? value : null;
    }

    private static int small(String word) {
        if (word.equals("нуль")) return 0;
        if (word.equals("одна")) return 1;
        if (word.equals("две")) return 2;
        return Arrays.asList(SMALL).indexOf(word);
    }
}
