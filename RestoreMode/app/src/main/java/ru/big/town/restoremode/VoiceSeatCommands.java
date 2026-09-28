package ru.big.town.restoremode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit seat/level parsing: never fuzzy-match a passenger into the driver's seat. */
final class VoiceSeatCommands {
    private static final Map<String, String> WORDS = new LinkedHashMap<>();
    static {
        forms("massage", "массаж массажа массажем массажный массажного");
        forms("heat", "подогрев подогрева обогрев обогрева подогрей подогреть");
        forms("vent", "вентиляция вентиляцию вентиляции обдув обдува");
        forms("seat", "сиденье сиденья сидение сидения сиденью сидению кресло кресла креслу");
        forms("driver", "водитель водителя водителю водительское водительского водительском водительский");
        forms("passenger", "пассажир пассажира пассажиру пассажирское пассажирского пассажирском пассажирский");
        forms("front", "передний переднего переднее переднем");
        forms("waves", "волны волна волнами волн волновой волнового");
        forms("rollers", "ролики роликами роликов роликовый роликового");
        forms("level", "уровень уровня уровне интенсивность интенсивности");
        forms("1", "1 один единица первый первом первую");
        forms("2", "2 два двойка второй втором вторую");
        forms("3", "3 три тройка третий третьем третью");
        forms("on", "включи включить включите установи установите поставь поставьте");
        forms("off", "выключи выключить выключите отключи отключить отключите");
        forms("filler", "на у для пожалуйста режим режима");
    }

    private static void forms(String canonical, String variants) {
        for (String word : variants.split(" ")) WORDS.put(word, canonical);
    }

    static boolean isAction(String action) { return action.startsWith("seat:"); }

    /** Unknown words, negation, multiple targets/numbers and punctuation in numbers fail closed. */
    static String action(String text) {
        if (text == null || text.length() > 256 || text.matches(".*[0-9][.,/+-][0-9].*")
                || text.matches(".*[+−-][0-9].*")) return null;
        String target = null, function = null, kind = null, level = null, verb = null;
        boolean seat = false, front = false, levelWord = false;
        for (String word : VoiceCommandCatalog.normalize(text).split(" ")) {
            String token = WORDS.get(word);
            if (token == null) return null;
            switch (token) {
                case "filler": break;
                case "driver": case "passenger":
                    if (target != null) return null;
                    target = token; break;
                case "massage": case "heat": case "vent":
                    if (function != null) return null;
                    function = token; break;
                case "waves": case "rollers":
                    if (kind != null) return null;
                    kind = token; break;
                case "1": case "2": case "3":
                    if (level != null) return null;
                    level = token; break;
                case "on": case "off":
                    if (verb != null && !verb.equals(token)) return null;
                    verb = token; break;
                case "seat": if (seat) return null; seat = true; break;
                case "front": if (front) return null; front = true; break;
                case "level": if (levelWord) return null; levelWord = true; break;
                default: return null;
            }
        }
        if (front && target == null) return null;
        if (kind != null) {
            if (function != null && !function.equals("massage")) return null;
            function = "massage";
        }
        if (function == null || (function.equals("heat") && !seat && target == null)) return null;
        if (levelWord && level == null) return null;
        if (kind != null && level != null) return null;
        if ("off".equals(verb) && (kind != null || level != null)) return null;
        String value = kind != null ? kind : level != null ? level : "off".equals(verb) ? "off" : "on";
        return "seat:" + (target == null ? "driver" : target) + ":" + function + ":" + value;
    }

    static VoiceCommandCatalog.Command match(List<VoiceCommandCatalog.Command> commands, String text) {
        String action = action(text);
        if (action == null) return null;
        for (VoiceCommandCatalog.Command command : commands) if (action.equals(command.action)) return command;
        return null;
    }

    static void addTo(List<VoiceCommandCatalog.Command> out) {
        for (String target : new String[]{"driver", "passenger"}) {
            String person = target.equals("driver") ? "водителя" : "пассажира";
            for (String function : new String[]{"massage", "heat", "vent"}) {
                String title = function.equals("massage") ? "Массаж" : function.equals("heat") ? "Подогрев сиденья" : "Вентиляция сиденья";
                String[] names = function.equals("massage") ? new String[]{"массаж"}
                        : function.equals("heat") ? new String[]{"подогрев сиденья", "обогрев кресла"}
                        : new String[]{"вентиляция сиденья", "обдув кресла"};
                List<String> values = new ArrayList<>(Arrays.asList("on", "off", "1", "2", "3"));
                if (function.equals("massage")) values.addAll(Arrays.asList("waves", "rollers"));
                for (String value : values) {
                    List<String> phrases = new ArrayList<>();
                    for (String name : names) {
                        variants(phrases, name + " " + person, value);
                        if (target.equals("driver")) variants(phrases, name, value);
                    }
                    String suffix = value.equals("on") ? "включить" : value.equals("off") ? "выключить"
                            : value.equals("waves") ? "волны" : value.equals("rollers") ? "ролики" : "уровень " + value;
                    out.add(new VoiceCommandCatalog.Command("seat:" + target + ":" + function + ":" + value,
                            title + " " + person + ": " + suffix, false, phrases.toArray(new String[0])));
                }
            }
        }
    }

    private static void variants(List<String> out, String name, String value) {
        if (value.equals("on")) out.addAll(Arrays.asList(name, "включи " + name, "включить " + name));
        else if (value.equals("off")) out.addAll(Arrays.asList("выключи " + name, "выключить " + name, "отключи " + name));
        else if (value.equals("waves") || value.equals("rollers")) {
            String noun = value.equals("waves") ? "волны" : "ролики";
            out.add(name + " " + noun);
            out.add("включи " + name + " " + (value.equals("waves") ? "волнами" : "роликами"));
        } else {
            int n = Integer.parseInt(value);
            out.add(name + " " + value);
            out.add(name + " " + new String[]{"", "один", "два", "три"}[n]);
            out.add(name + " на " + value);
            out.add(name + " " + new String[]{"", "первый", "второй", "третий"}[n] + " уровень");
        }
    }
}
