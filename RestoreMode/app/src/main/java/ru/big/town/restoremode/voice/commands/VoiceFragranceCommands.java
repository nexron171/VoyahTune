package ru.big.town.restoremode.voice.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VoiceFragranceCommands {
    private static final String[][] NAMES = {
        {"свежесть бамбука", "бамбук"},
        {"пионовая красота", "пионовую красоту", "пион"},
        {"туман", "утренняя роса", "утреннюю росу", "роса", "росу"}
    };
    private static final String[] TITLES = {
        "Свежесть бамбука", "Пионовая красота", "Туман / утренняя роса"
    };
    private static final String[] ORDINAL = {"первый", "второй", "третий"};
    private static final String[] NUMBERS = {"один", "два", "три"};
    private static final String[] STRENGTH = {"", "слабо", "средне", "сильно"};
    private static final String[][] DURATION = {
        {"безлимит", "без ограничения времени", "без таймера"},
        {"тридцать минут", "30 минут", "полчаса"},
        {"шестьдесят минут", "60 минут", "час"}
    };

    private VoiceFragranceCommands() {}

    static void addTo(List<VoiceCommandCatalog.Command> commands) {
        for (int taste = 1; taste <= 3; taste++) {
            List<String> base = new ArrayList<>();
            Collections.addAll(
                    base,
                    "выбери " + ORDINAL[taste - 1] + " ароматизатор",
                    "ароматизатор " + taste,
                    "ароматизатор " + NUMBERS[taste - 1],
                    "выбери " + ORDINAL[taste - 1] + " аромат");
            for (String name : NAMES[taste - 1]) {
                Collections.addAll(
                        base,
                        "включи " + name,
                        "выбери " + name,
                        "ароматизатор " + name,
                        "аромат " + name,
                        "включи ароматизатор " + name,
                        "выбери ароматизатор " + name);
            }
            for (int intensity = 0; intensity <= 3; intensity++) {
                for (int duration = -1; duration <= 2; duration++) {
                    add(commands, base, taste, intensity, duration);
                }
            }
        }
    }

    private static void add(
            List<VoiceCommandCatalog.Command> commands,
            List<String> base,
            int taste,
            int intensity,
            int duration) {
        String action = "fragrance:" + taste;
        String title = "Ароматизатор: " + TITLES[taste - 1] + " (№" + taste + ")";
        if (intensity != 0) {
            action += ":intensity:" + intensity;
            title += ", " + STRENGTH[intensity];
        }
        if (duration != -1) {
            action += ":duration:" + duration;
            title += ", " + (duration == 0 ? "безлимит" : duration == 1 ? "30 минут" : "60 минут");
        }
        List<String> phrases = new ArrayList<>();
        for (String phrase : base) {
            String strength = intensity == 0 ? phrase : phrase + " " + STRENGTH[intensity];
            if (duration == -1) {
                phrases.add(strength);
            } else {
                for (String length : DURATION[duration]) phrases.add(strength + " " + length);
            }
        }
        VoiceCommandCatalog.add(commands, action, title, phrases.toArray(new String[0]));
    }
}
