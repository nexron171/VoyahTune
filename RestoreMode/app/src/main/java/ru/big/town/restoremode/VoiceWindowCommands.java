package ru.big.town.restoremode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps window number, location and motion explicit; never guesses a different target. */
final class VoiceWindowCommands {
    private static final Map<String, String> WORDS = new HashMap<>();
    static {
        forms("open", "открой открыть откройте опусти опустить опустите");
        forms("close", "закрой закрыть закройте подними поднять поднимите");
        forms("ventVerb", "приоткрой приоткрыть приоткройте проветри проветрить проветрите");
        forms("vent", "проветривание проветривания");
        forms("little", "немного чуть слегка");
        forms("on", "включи включить включите");
        forms("window", "окно стекло");
        forms("windows", "окна окон стекла стекол");
        forms("roof", "люк люка");
        forms("shade", "шторка шторку шторки штора штору шторы");
        forms("roofPlace", "крыша крыши крыше панорама панорамы панораме");
        forms("shadeKind", "солнцезащитную солнцезащитная солнцезащитной");
        forms("driver", "водителя водительское водительского");
        forms("passenger", "пассажира пассажирское пассажирского");
        forms("front", "переднее переднего передние передних спереди");
        forms("rear", "заднее заднего задние задних сзади");
        forms("left", "левое левого левые левых слева");
        forms("right", "правое правого правые правых справа");
        forms("all", "все всех");
        forms("both", "оба обоих");
        forms("cabin", "салон салона");
        forms("filler", "пожалуйста на у для режим режима только");
    }

    private static void forms(String token, String words) {
        for (String word : words.split(" ")) WORDS.put(word, token);
    }

    static boolean isAction(String action) {
        return action != null && (action.startsWith("windows:") || action.startsWith("sunroof:")
                || action.startsWith("sunshade:"));
    }

    /** Prevent a rejected window phrase from falling through to fuzzy hatch/other commands. */
    static boolean mentionsWindow(String text) {
        boolean window = false, motion = false;
        for (String word : VoiceCommandCatalog.normalize(text).split(" ")) {
            String token = WORDS.get(word);
            if ("roof".equals(token) || "shade".equals(token) || "vent".equals(token)
                    || "ventVerb".equals(token)) return true;
            if ("window".equals(token) || "windows".equals(token)) window = true;
            if ("open".equals(token) || "close".equals(token) || "little".equals(token)) motion = true;
        }
        // Heating the rear glass is an existing command with its own repair vocabulary.
        return window && motion;
    }

    static String action(String text) {
        if (text == null || text.length() > 256 || text.contains("%")) return null;
        Set<String> tokens = new HashSet<>();
        for (String word : VoiceCommandCatalog.normalize(text).split(" ")) {
            String token = WORDS.get(word);
            if (token == null) return null;
            if (!token.equals("filler") && !tokens.add(token)) return null;
        }
        boolean single = tokens.contains("window"), plural = tokens.contains("windows");
        boolean roof = tokens.contains("roof"), shade = tokens.contains("shade");
        boolean vent = tokens.contains("vent") || tokens.contains("ventVerb") || tokens.contains("little");
        boolean open = tokens.contains("open"), close = tokens.contains("close"), on = tokens.contains("on");
        if ((open && close) || (close && vent) || (on && !vent) || (on && close)) return null;
        if (tokens.contains("little") && !open && !tokens.contains("ventVerb") && !tokens.contains("vent")) return null;
        if (!vent && !open && !close) return null;
        if (single && plural) return null;
        boolean located = tokens.contains("driver") || tokens.contains("passenger") || tokens.contains("front")
                || tokens.contains("rear") || tokens.contains("left") || tokens.contains("right");
        if (roof || shade) {
            if (single || plural || located || tokens.contains("all") || tokens.contains("both")
                    || tokens.contains("cabin") || (tokens.contains("shadeKind") && !shade)) return null;
            if (shade && vent) return null;
            return (shade ? "sunshade:" : "sunroof:") + (vent ? "vent" : close ? "close" : "open");
        }
        if (tokens.contains("roofPlace") || tokens.contains("shadeKind")) return null;
        if (tokens.contains("cabin") && (!vent || single || plural || located)) return null;
        if (!single && !plural && (!vent || located || tokens.contains("all") || tokens.contains("both"))) return null;
        String target = target(tokens, single);
        if (target == null) return null;
        if (vent) return target.isEmpty() && !single ? "windows:vent" : null;
        return "windows:" + target + (target.isEmpty() ? "" : ":") + (close ? "close" : "open");
    }

    private static String target(Set<String> t, boolean single) {
        boolean driver = t.contains("driver"), passenger = t.contains("passenger");
        boolean front = t.contains("front"), rear = t.contains("rear");
        boolean left = t.contains("left"), right = t.contains("right");
        if ((driver && passenger) || (front && rear) || (left && right)) return null;
        String target;
        boolean individual;
        if (driver || passenger) {
            if (rear || (driver && right) || (passenger && left)) return null;
            target = driver ? "driver" : "passenger";
            individual = true;
        } else if ((front || rear) && (left || right)) {
            target = front ? (left ? "driver" : "passenger") : (left ? "rear_left" : "rear_right");
            individual = true;
        } else {
            target = front ? "front" : rear ? "rear" : left ? "left" : right ? "right" : "";
            individual = false;
        }
        if (single != individual) return null;
        if (individual && (t.contains("all") || t.contains("both"))) return null;
        if (t.contains("both") && target.isEmpty()) return null;
        return target;
    }

    static VoiceCommandCatalog.Command match(List<VoiceCommandCatalog.Command> commands, String text) {
        String action = action(text);
        if (action != null) for (VoiceCommandCatalog.Command command : commands) {
            if (action.equals(command.action)) return command;
        }
        return null;
    }

    static void addTo(List<VoiceCommandCatalog.Command> out) {
        String[][] windows = {
                {"", "Все окна", "окна", "все окна", "все стекла"},
                {"driver", "Окно водителя", "водительское окно", "водительское стекло", "окно водителя", "переднее левое окно"},
                {"passenger", "Окно переднего пассажира", "окно пассажира", "пассажирское стекло", "окно переднего пассажира", "переднее правое окно"},
                {"rear_left", "Заднее левое окно", "заднее левое окно", "заднее левое стекло", "окно сзади слева"},
                {"rear_right", "Заднее правое окно", "заднее правое окно", "заднее правое стекло", "окно сзади справа"},
                {"front", "Передние окна", "передние окна", "оба передних стекла", "окна спереди"},
                {"rear", "Задние окна", "задние окна", "оба задних стекла", "окна сзади"},
                {"left", "Левые окна", "левые окна", "оба левых стекла", "окна слева"},
                {"right", "Правые окна", "правые окна", "оба правых стекла", "окна справа"}
        };
        for (String[] w : windows) addMotion(out, "windows:" + w[0] + (w[0].isEmpty() ? "" : ":"), w, true);
        addMotion(out, "sunroof:", new String[]{"", "Люк", "люк", "люк на крыше"}, false);
        addMotion(out, "sunshade:", new String[]{"", "Шторка люка", "шторку", "шторку люка", "шторку панорамы", "солнцезащитную шторку", "штору"}, false);
        out.add(new VoiceCommandCatalog.Command("windows:vent", "Все окна: проветривание", false,
                "проветривание", "режим проветривания", "включи проветривание", "проветри салон",
                "приоткрой окна", "немного открой все окна", "проветривание окон"));
        out.add(new VoiceCommandCatalog.Command("sunroof:vent", "Люк: проветривание", false,
                "проветривание люка", "приоткрой люк", "открой люк на проветривание",
                "включи проветривание люка", "немного открой люк"));
    }

    private static void addMotion(List<VoiceCommandCatalog.Command> out, String prefix, String[] names, boolean windows) {
        for (boolean open : new boolean[]{true, false}) {
            List<String> phrases = new ArrayList<>();
            for (int i = 2; i < names.length; i++) {
                phrases.add((open ? "открой " : "закрой ") + names[i]);
                phrases.add((open ? "открыть " : "закрыть ") + names[i]);
                if (windows) phrases.add((open ? "опусти " : "подними ") + names[i]);
            }
            out.add(new VoiceCommandCatalog.Command(prefix + (open ? "open" : "close"),
                    names[1] + (open ? ": открыть" : ": закрыть"), false, phrases.toArray(new String[0])));
        }
    }
}
