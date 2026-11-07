package ru.big.town.anative;

import ru.big.town.anative.OemVehicleStateTransport.Session;
import ru.big.town.anative.OemVehicleStateTransport.StateKey;
import ru.big.town.anative.OemVehicleStateTransport.StateValue;
import ru.big.town.common.FragranceWidgetProtocol;
import ru.big.town.common.FragranceWidgetProtocol.Snapshot;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FragranceVehicle {
    private static final StateKey POWER =
            new StateKey(
                    FragranceRestorePolicy.SWITCH_STATE, FragranceRestorePolicy.SWITCH_STATE_ID);
    private static final StateKey SCENT =
            new StateKey(FragranceRestorePolicy.TASTE_STATE, FragranceRestorePolicy.TASTE_STATE_ID);
    private static final StateKey LEVEL =
            new StateKey(
                    FragranceRestorePolicy.INTENSITY_STATE,
                    FragranceRestorePolicy.INTENSITY_STATE_ID);
    private static final StateKey TIMER =
            new StateKey(
                    FragranceRestorePolicy.DURATION_STATE,
                    FragranceRestorePolicy.DURATION_STATE_ID);
    static final List<StateKey> KEYS = Arrays.asList(POWER, SCENT, LEVEL, TIMER);

    private FragranceVehicle() {}

    static Snapshot read(Session session) {
        return new Snapshot(
                read(session, POWER),
                read(session, SCENT),
                read(session, LEVEL),
                read(session, TIMER));
    }

    private static int read(Session session, StateKey key) {
        Integer observed = session.readVehicleState(key);
        return observed == null ? -1 : observed;
    }

    static boolean apply(Session session, int command, int value) {
        Snapshot current = read(session);
        if (!current.allows(command, value)) return false;
        if (current.matches(command, value)) return true;
        StateKey key;
        switch (command) {
            case FragranceWidgetProtocol.SCENT:
                if (!current.on()) {
                    Map<StateKey, Integer> values = new LinkedHashMap<>();
                    values.put(SCENT, value);
                    values.put(POWER, FragranceRestorePolicy.SWITCH_ON);
                    return session.sendBundle(values, "enable selected fragrance").accepted();
                }
                key = SCENT;
                break;
            case FragranceWidgetProtocol.POWER:
                key = POWER;
                break;
            case FragranceWidgetProtocol.LEVEL:
                key = LEVEL;
                break;
            case FragranceWidgetProtocol.TIMER:
                key = TIMER;
                break;
            default:
                return false;
        }
        return session.sendVehicleState(new StateValue(key, value), "fragrance widget").accepted();
    }
}
