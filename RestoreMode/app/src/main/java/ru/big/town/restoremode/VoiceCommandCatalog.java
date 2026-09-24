package ru.big.town.restoremode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One source for the phrase guide and deterministic, order-independent intent matching. */
final class VoiceCommandCatalog {
    static final class Command {
        final String action, title;
        final List<String> phrases;
        final boolean confirm;
        Command(String action, String title, boolean confirm, String... phrases) {
            this.action = action;
            this.title = title;
            this.confirm = confirm;
            this.phrases = Collections.unmodifiableList(Arrays.asList(phrases));
        }
    }

    private static final Set<String> FILLER = new LinkedHashSet<>(Arrays.asList(
            "включи", "включить", "включите", "установи", "поставь",
            "режим", "режима", "на", "пожалуйста"));
    private static final Set<String> REJECT = new LinkedHashSet<>(Arrays.asList(
            "не", "нет", "нельзя", "отмена", "отмени", "или", "потом", "затем", "если"));

    static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N} ]", " ").trim().replaceAll("\\s+", " ");
    }

    private static Set<String> words(String text) {
        Set<String> out = new LinkedHashSet<>();
        String intent = null;
        for (String word : normalize(text).split(" ")) {
            if (REJECT.contains(word)) return null;
            if (word.equals("переключи") || word.equals("переключите")) word = "переключить";
            String verb = word.equals("переключить") ? "switch"
                    : Arrays.asList("включи", "включить", "включите", "установи", "поставь").contains(word) ? "on"
                    : Arrays.asList("выключи", "выключить", "отключи").contains(word) ? "off" : null;
            if (verb != null) {
                if (intent != null && !intent.equals(verb)) return null;
                intent = verb;
            }
            if (!word.isEmpty() && !FILLER.contains(word)) out.add(word);
        }
        return out;
    }

    static Command match(List<Command> commands, String text) {
        Set<String> input = words(text);
        if (input == null || input.isEmpty()) return null;
        Command found = VoiceFuelCommand.match(text);
        for (Command command : commands) {
            // Numeric commands must retain word order and repeated tokens for strict parsing.
            if (command.action.startsWith(VoiceFuelCommand.PREFIX)) continue;
            for (String phrase : command.phrases) {
                if (!input.equals(words(phrase))) continue;
                if (found != null && !found.action.equals(command.action)) return null;
                found = command;
            }
        }
        return found;
    }

    static List<Command> builtIns() {
        List<Command> all = new ArrayList<>();
        mode(all, "drive:SPORT", "Режим движения: Спорт", "спорт", "спортивный");
        mode(all, "drive:ECO", "Режим движения: Эко", "эко", "экономичный");
        mode(all, "drive:COMFORT", "Режим движения: Комфорт", "комфорт", "комфортный");
        mode(all, "drive:OUTING", "Режим движения: Outing",
                "загород", "загородный", "внедорожье", "внедорожный");
        add(all, "drive:OUTING", "Режим Outing — поднять подвеску",
                "поднять подвеску", "подними подвеску", "поднимите подвеску");
        mode(all, "drive:SNOW", "Режим движения: Снег", "снег", "снежный");
        mode(all, "drive:INDIVIDUAL", "Режим движения: Индивидуальный", "индивидуальный");
        mode(all, "energy:EV", "Энергорежим: Электро", "электро", "электрический");
        mode(all, "energy:REV", "Энергорежим: Гибрид", "гибрид", "гибридный");
        mode(all, "energy:SREV", "Энергорежим: Топливо / сохранение заряда",
                "топливо", "топливный", "сохранение заряда");
        for (int percent = 25; percent <= 80; percent += 5) all.add(VoiceFuelCommand.command(percent));
        mode(all, "recycle:LOW", "Рекуперация: Низкая", "низкая рекуперация", "слабая рекуперация");
        mode(all, "recycle:MEDIUM", "Рекуперация: Стандартная", "стандартная рекуперация", "средняя рекуперация");
        mode(all, "recycle:HIGH", "Рекуперация: Высокая", "высокая рекуперация", "сильная рекуперация");
        binary(all, "forced_ev", "Принудительный электрорежим", "форсированный электро", "форс и ви", "форсированный электрорежим", "принудительный электрорежим", "форсед и ви");
        binary(all, "pedestrian", "Звук предупреждения пешеходов", "звук пешеходов", "предупреждение пешеходов");
        binary(all, "headlights", "Ближний свет", "фары", "ближний свет");
        add(all, "headlights:auto", "Штатный свет: Авто", "автоматический свет", "фары авто", "включи авто свет");
        binary(all, "auto_light", "Автосвет VoyahTune", "автосвет воя тюн", "автосвет приложения");
        add(all, "toggle_headlights", "Переключить фары: выкл / ближний",
                "переключи фары", "переключить фары");
        add(all, "toggle_headlights_auto", "Переключить фары: ближний / авто",
                "переключи фары авто", "переключить фары авто",
                "переключи авто свет", "переключить авто свет");
        portCap(all, "port_cap:fuel", "Открыть лючок бензобака (только в P)",
                "бензобак", "бак", "люк бензобака", "лючок бензобака", "люк бака", "лючок бака",
                "топливный люк", "топливный лючок");
        portCap(all, "port_cap:charge", "Открыть лючок зарядки (только в P)",
                "зарядку", "зарядка", "люк зарядки", "лючок зарядки", "зарядный люк", "зарядный лючок",
                "люк для зарядки", "лючок для зарядки", "люк зарядного порта", "лючок зарядного порта");
        add(all, "power_hold", "Power Hold — оставить автомобиль включённым", "пауэр холд", "оставь машину включенной", "режим ожидания");
        add(all, "wash", "Режим мойки", "мойка", "включи мойку", "режим мойки");
        add(all, "battery_heat", "Запросить прогрев батареи", "прогрей батарею", "прогрев батареи", "включи подогрев батареи");
        add(all, "apply", "Применить сохранённые настройки", "примени настройки", "применить настройки", "восстанови настройки автомобиля");
        add(all, "open_voyahtune", "Открыть VoyahTune", "открой воя тюн", "открой приложение воя тюн", "открой настройки автомобиля");
        add(all, "system_back", "Назад", "назад", "вернись назад", "вернуться назад");
        all.add(new Command("close_all", "Закрыть сторонние приложения", true,
                "закрой все приложения", "закрыть все приложения"));
        all.add(new Command("reboot", "Перезагрузить головное устройство", true,
                "перезагрузи систему", "перезагрузи головное устройство", "перезагрузка системы"));
        return all;
    }

    private static void portCap(List<Command> all, String action, String title, String... names) {
        List<String> phrases = new ArrayList<>();
        for (String name : names) {
            Collections.addAll(phrases, name, "открой " + name, "открыть " + name, "откройте " + name);
        }
        all.add(new Command(action, title, false, phrases.toArray(new String[0])));
    }

    private static void mode(List<Command> all, String action, String title, String... names) {
        List<String> phrases = new ArrayList<>();
        for (String name : names) {
            Collections.addAll(phrases, name, "включи " + name,
                    name + " режим", "включи " + name + " режим", "включи режим " + name,
                    "переключи на " + name, "поставь " + name);
            if (action.startsWith("drive:") || action.startsWith("energy:")) Collections.addAll(phrases,
                    "режим " + name, "включить режим " + name, "включить " + name + " режим",
                    "переключи на режим " + name, "переключи на " + name + " режим");
        }
        all.add(new Command(action, title, false, phrases.toArray(new String[0])));
    }

    private static void binary(List<Command> all, String action, String title, String... names) {
        List<String> on = new ArrayList<>(), off = new ArrayList<>();
        for (String name : names) {
            Collections.addAll(on, "включи " + name, "включить " + name);
            Collections.addAll(off, "выключи " + name, "отключи " + name, "выключить " + name);
        }
        all.add(new Command(action + ":on", title + ": включить", false, on.toArray(new String[0])));
        all.add(new Command(action + ":off", title + ": выключить", false, off.toArray(new String[0])));
    }

    static void add(List<Command> all, String action, String title, String... phrases) {
        all.add(new Command(action, title, false, phrases));
    }

}
