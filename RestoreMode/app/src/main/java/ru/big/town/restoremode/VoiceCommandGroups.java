package ru.big.town.restoremode;

import java.util.EnumSet;
import java.util.Set;

/** Presentation only: grouping never changes the recognizer's command catalog. */
final class VoiceCommandGroups {
    enum Group {
        SEATS("Сиденья"), WINDOWS("Окна"), CAR("Настройки автомобиля"),
        HEATING("Тёплые опции"), OTHER("Другое");

        final String title;
        Group(String title) { this.title = title; }
    }

    static Set<Group> forAction(String action) {
        EnumSet<Group> groups = EnumSet.noneOf(Group.class);
        if (action.startsWith("seat:")) {
            groups.add(Group.SEATS);
            if (action.contains(":heat:")) groups.add(Group.HEATING);
        } else if (VoiceWindowCommands.isAction(action)) {
            groups.add(Group.WINDOWS);
        } else if (action.startsWith("wheel_heat:") || action.equals("battery_heat")
                || action.equals("can:65080000c1c020000000") || action.equals("can:65080000c1c010000000")) {
            groups.add(Group.HEATING);
        } else if (action.startsWith("drive:") || action.startsWith("energy:")
                || action.startsWith(VoiceFuelCommand.PREFIX) || action.startsWith("recycle:")
                || action.startsWith("suspension_maintenance:") || action.startsWith("forced_ev:") || action.startsWith("pedestrian:")
                || action.startsWith("headlights:") || action.startsWith("auto_light:")
                || action.startsWith("port_cap:") || action.equals("toggle_headlights")
                || action.equals("toggle_headlights_auto") || action.equals("power_hold")
                || action.equals("wash") || action.equals("apply")
                || action.equals("can:7a080000000001000000") || action.equals("can:7a080000000002000000")) {
            groups.add(Group.CAR);
        } else groups.add(Group.OTHER);
        return groups;
    }
}
