package ru.big.town.restoremode.vehicle.steering;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class SteeringActions {
    public static final class Action {
        public final String id;
        public final String label;

        private Action(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    public static final List<Action> ALL =
            Collections.unmodifiableList(
                    Arrays.asList(
                            new Action("open_voyahtune", "Открыть VoyahTune"),
                            new Action("open_carplay", "Открыть CarPlay"),
                            new Action("system_back", "Системное действие: Назад"),
                            new Action("energy:EV", "Энергорежим: Electric"),
                            new Action("energy:REV", "Энергорежим: Fuel"),
                            new Action("energy:SREV", "Энергорежим: Save"),
                            new Action("energy:EV,REV", "Энергорежим: Electric → Fuel"),
                            new Action("energy:EV,REV,SREV", "Энергорежим: Electric → Fuel → Save"),
                            new Action("energy:EV,SREV", "Энергорежим: Electric → Save"),
                            new Action("energy:REV,SREV", "Энергорежим: Fuel → Save"),
                            new Action("drive:ECO", "Режим езды: Eco"),
                            new Action("drive:COMFORT", "Режим езды: Comf"),
                            new Action("drive:SPORT", "Режим езды: Sport"),
                            new Action("drive:OUTING", "Режим езды: Outing"),
                            new Action("drive:SNOW", "Режим езды: Snow"),
                            new Action("drive:INDIVIDUAL", "Режим езды: Indiv"),
                            new Action("drive:ECO,COMFORT", "Режим езды: Eco → Comf"),
                            new Action("drive:ECO,SPORT", "Режим езды: Eco → Sport"),
                            new Action("drive:ECO,OUTING", "Режим езды: Eco → Outing"),
                            new Action("drive:ECO,SNOW", "Режим езды: Eco → Snow"),
                            new Action("drive:ECO,INDIVIDUAL", "Режим езды: Eco → Indiv"),
                            new Action("drive:COMFORT,SPORT", "Режим езды: Comf → Sport"),
                            new Action("drive:COMFORT,OUTING", "Режим езды: Comf → Outing"),
                            new Action("drive:COMFORT,SNOW", "Режим езды: Comf → Snow"),
                            new Action("drive:COMFORT,INDIVIDUAL", "Режим езды: Comf → Indiv"),
                            new Action("drive:SPORT,OUTING", "Режим езды: Sport → Outing"),
                            new Action("drive:SPORT,SNOW", "Режим езды: Sport → Snow"),
                            new Action("drive:SPORT,INDIVIDUAL", "Режим езды: Sport → Indiv"),
                            new Action("drive:OUTING,SNOW", "Режим езды: Outing → Snow"),
                            new Action("drive:OUTING,INDIVIDUAL", "Режим езды: Outing → Indiv"),
                            new Action("drive:SNOW,INDIVIDUAL", "Режим езды: Snow → Indiv"),
                            new Action("recycle:LOW", "Рекуперация: Низкая"),
                            new Action("recycle:MEDIUM", "Рекуперация: Стандартная"),
                            new Action("recycle:HIGH", "Рекуперация: Высокая"),
                            new Action("recycle:LOW,MEDIUM", "Рекуперация: Низкая → Стандартная"),
                            new Action("recycle:LOW,HIGH", "Рекуперация: Низкая → Высокая"),
                            new Action("recycle:MEDIUM,HIGH", "Рекуперация: Стандартная → Высокая"),
                            new Action(
                                    "toggle_suspension_maintenance",
                                    "Сервисный режим подвески: вкл/выкл"),
                            new Action("toggle_forced_ev", "Force EV: вкл/выкл"),
                            new Action("toggle_pedestrian_sound", "Звук пешеходов: вкл/выкл"),
                            new Action("toggle_headlights", "Фары: выкл/ближний"),
                            new Action("toggle_headlights_auto", "Фары: ближний/авто"),
                            new Action("headlights:parking", "Фары: габариты"),
                            new Action("wash", "Режим мойки")));

    private SteeringActions() {}

    public static boolean isSelectable(String id) {
        if (id == null) return false;
        for (Action action : ALL) {
            if (action.id.equals(id)) return true;
        }
        if (id.startsWith("app:"))
            return id.substring(4).matches("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+");
        if (id.startsWith("split:")) return id.substring(6).matches("[0-9]+");
        if (id.startsWith("call:")) return id.substring(5).matches("[0-9]{4,11}");
        if (id.startsWith("can:")) return SteeringCanCommandPolicy.isValid(id.substring(4));
        return false;
    }
}
