package ru.big.town.restoremode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded repairs of vehicle vocabulary. Never discard unknown words or guess action verbs. */
final class VoiceCommandRepair {
    private static final Map<String, String> FORMS = new LinkedHashMap<>();
    static {
        forms("комфорт", "комфорта комфорту комфортом комфорте комфортный комфортного комфортном комфортную");
        forms("спорт", "спорта спорту спортом спорте спортивный спортивного спортивном спортивную");
        forms("топливо", "топлива топливу топливом топливе топливный топливного топливном топливную");
        forms("гибрид", "гибрида гибриду гибридом гибриде гибридный гибридного гибридном гибридную");
        forms("электро", "электрический электрического электрическом электрическую");
        forms("экономичный", "экономичного экономичном экономичную");
        forms("снег", "снега снегу снегом снеге снежный снежного снежном снежную");
        forms("внедорожный", "внедорожного внедорожном внедорожную внедорожье");
        forms("загородный", "загородного загородном загородную загород");
        forms("индивидуальный", "индивидуального индивидуальном индивидуальную");
        forms("рекуперация", "рекуперации рекуперацию рекуперацией");
        forms("низкая", "низкий низкую низкой низкое низкого низком");
        forms("слабая", "слабый слабую слабой слабое слабого слабом");
        forms("средняя", "средний среднюю средней среднее среднего среднем");
        forms("стандартная", "стандартный стандартную стандартной стандартное стандартного стандартном");
        forms("высокая", "высокий высокую высокой высокое высокого высоком");
        forms("сильная", "сильный сильную сильной сильное сильного сильном");
        forms("обогрев", "обогрева обогреву обогревом обогреве подогрев подогрева подогреву подогревом подогреве подогрей подогреть подогрела подогрел подогрели");
        forms("руль", "руля рулю рулем руле");
        forms("стекло", "стекла стеклу стеклом стекле");
        forms("задний", "заднее заднего заднем заднюю задней");
        forms("батарея", "батареи батарею батарее батареей");
        forms("ближний", "ближнего ближнем ближнюю");
        forms("свет", "света свету светом свете");
        forms("фары", "фара фару фарам фарами фарах");
        forms("бензобак", "бензобака бензобаку бензобаком бензобаке");
        forms("бак", "бака баку баком баке");
        forms("лючок", "лючка лючку лючком лючке люк люка люку люком люке");
        forms("зарядка", "зарядки зарядку зарядке зарядкой");
        forms("зарядный", "зарядного зарядном зарядную");
        forms("порт", "порта порту портом порте");
        forms("подвеска", "подвески подвеску подвеске подвеской");
        forms("мойка", "мойки мойку мойке мойкой");
        forms("звук", "звука звуку звуком звуке");
        forms("пешеходы", "пешеходов пешеходам пешеходами пешеходах");
        forms("заряд", "заряда заряду зарядом заряде");
        forms("сохранение", "сохранения сохранению сохранением сохранении");
    }

    private static void forms(String canonical, String variants) {
        FORMS.put(canonical, canonical);
        for (String form : variants.split(" ")) FORMS.put(form, canonical);
    }

    static boolean known(String word) { return FORMS.containsKey(word); }

    /** Inflected targets keep edit costs meaningful: «гребля» -> «руля» costs three. */
    static List<String> variants(String canonical) {
        List<String> out = new ArrayList<>();
        out.add(canonical);
        for (Map.Entry<String, String> form : FORMS.entrySet()) {
            if (form.getValue().equals(canonical) && !form.getKey().equals(canonical)) out.add(form.getKey());
        }
        return out;
    }

    /** Preserve decimal punctuation, signs, numbers, unknown words and their order. */
    static String normalize(String text, boolean allowTypo) {
        String normalized = text.toLowerCase(Locale.ROOT).replace('ё', 'е').replace('−', '-')
                .replaceAll("[^\\p{L}\\p{N}+.,%\\- ]", " ")
                .replaceAll("(?<![0-9])[.,]|[.,](?![0-9])", " ").trim();
        String[] tokens = normalized.split("\\s+");
        if (tokens.length > 32 || normalized.length() > 256) return null;
        List<String> out = new ArrayList<>();
        int typos = 0;
        for (int at = 0; at < tokens.length;) {
            String word = tokens[at];
            String canonical = FORMS.get(word);
            int consumed = 1;
            if (canonical == null && repairable(word)) {
                // Join only adjacent fragments, without crossing verbs, negation or numbers.
                String joined = "";
                for (int end = at; end < Math.min(at + 3, tokens.length); end++) {
                    if (!repairable(tokens[end])) break;
                    joined += tokens[end];
                    String form = FORMS.get(joined);
                    if (form != null) { canonical = form; consumed = end - at + 1; }
                }
                if (canonical == null && allowTypo) {
                    joined = "";
                    for (int end = at; end < Math.min(at + 3, tokens.length); end++) {
                        if (!repairable(tokens[end])) break;
                        joined += tokens[end];
                        String form = oneTypo(joined);
                        if (form == null) continue;
                        // Competing interpretations of different spans are not a command.
                        if (canonical != null) return null;
                        canonical = form; consumed = end - at + 1;
                    }
                    if (canonical != null && ++typos > 1) return null;
                }
            }
            out.add(canonical == null ? word : canonical);
            at += consumed;
        }
        return String.join(" ", out);
    }

    private static boolean repairable(String word) {
        // In particular, do not turn an imperfect 'выключи' into 'включи'.
        return word.matches("[а-я]+") && !word.contains("ключ")
                && !Arrays.asList("не", "ни", "нет", "нельзя", "отмена", "отмени", "или", "и",
                "потом", "затем", "если", "включи", "включить", "включите", "выключи", "выключить",
                "отключи", "переключи", "переключить", "переключите", "установи", "поставь",
                "открой", "открыть", "откройте", "закрой", "закрыть", "режим", "режима", "на",
                "пожалуйста", "минус", "плюс").contains(word);
    }

    private static String oneTypo(String word) {
        if (word.length() < 6) return null;
        String found = null;
        for (Map.Entry<String, String> form : FORMS.entrySet()) {
            if (form.getKey().length() < 6 || !distanceOne(word, form.getKey())) continue;
            if (found != null && !found.equals(form.getValue())) return null;
            found = form.getValue();
        }
        return found;
    }

    /** One insertion, deletion or substitution; never a substring/partial-word search. */
    private static boolean distanceOne(String first, String second) {
        if (Math.abs(first.length() - second.length()) > 1) return false;
        int a = 0, b = 0, edits = 0;
        while (a < first.length() && b < second.length()) {
            if (first.charAt(a) == second.charAt(b)) { a++; b++; continue; }
            if (++edits > 1) return false;
            if (first.length() >= second.length()) a++;
            if (second.length() >= first.length()) b++;
        }
        return edits + (first.length() - a) + (second.length() - b) == 1;
    }
}
