package ru.big.town.anative;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ClusterNavigationText {
    private static final Pattern WORD = Pattern.compile("[\\p{L}]+");
    private static final Map<String, String> ABBREVIATIONS = abbreviations();
    private static final String RUSSIAN = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
    private static final String[] LATIN = {
        "a", "b", "v", "g", "d", "e", "yo", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r",
        "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya"
    };

    private ClusterNavigationText() {}

    static String format(String text, boolean transliteration, boolean showStreet) {
        return showStreet ? format(text, transliteration) : "";
    }

    static String format(String text, boolean transliteration) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher matcher = WORD.matcher(text);
        StringBuffer shortened = new StringBuffer();
        while (matcher.find()) {
            String word = matcher.group();
            String replacement = ABBREVIATIONS.get(word.toLowerCase(Locale.ROOT));
            matcher.appendReplacement(
                    shortened, Matcher.quoteReplacement(replacement == null ? word : replacement));
        }
        matcher.appendTail(shortened);
        if (!transliteration) {
            return shortened.toString();
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < shortened.length(); index++) {
            char letter = shortened.charAt(index);
            int position = RUSSIAN.indexOf(Character.toLowerCase(letter));
            if (position < 0) {
                result.append(letter);
                continue;
            }
            String replacement = LATIN[position];
            if (Character.isUpperCase(letter) && !replacement.isEmpty()) {
                result.append(Character.toUpperCase(replacement.charAt(0)));
                result.append(replacement.substring(1));
            } else {
                result.append(replacement);
            }
        }
        return result.toString();
    }

    private static Map<String, String> abbreviations() {
        Map<String, String> values = new HashMap<>();
        String[][] entries = {
            {"улица", "ул."},
            {"проспект", "пр-кт"},
            {"проезд", "пр-д"},
            {"переулок", "пер."},
            {"бульвар", "б-р"},
            {"набережная", "наб."},
            {"площадь", "пл."},
            {"шоссе", "ш."},
            {"тупик", "туп."},
            {"микрорайон", "мкр."},
            {"квартал", "кв-л"},
            {"территория", "тер."},
            {"район", "р-н"},
            {"область", "обл."},
            {"посёлок", "п."},
            {"поселок", "п."},
            {"деревня", "д."},
            {"село", "с."},
            {"город", "г."},
            {"станица", "ст-ца"},
            {"хутор", "х."},
            {"дорога", "дор."},
            {"площадка", "пл-ка"},
            {"участок", "уч-к"},
            {"станция", "ст."},
            {"платформа", "платф."},
            {"разъезд", "рзд."},
            {"слобода", "сл."},
            {"республика", "респ."},
            {"километр", "км"},
            {"строение", "стр."},
            {"корпус", "корп."}
        };
        for (String[] entry : entries) {
            values.put(entry[0], entry[1]);
        }
        return Collections.unmodifiableMap(values);
    }
}
