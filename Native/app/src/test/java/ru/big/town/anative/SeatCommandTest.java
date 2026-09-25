package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;

public class SeatCommandTest {
    private void maps(String action, String field, int value) {
        SeatCommand c = SeatCommand.parse(action);
        assertNotNull(action, c); assertEquals(action, field, c.field); assertEquals(action, value, c.value);
    }

    @Test public void mapsAllThirtySixActionsToExactFieldsAndValues() {
        String[][] functions = {{"massage", "MASS"}, {"heat", "HEATING"}, {"vent", "VENTILATION"}};
        for (String side : new String[]{"driver", "passenger"}) {
            String suffix = side.equals("driver") ? "LEFT" : "RIGHT";
            for (String[] f : functions) {
                String action = "seat:" + side + ":" + f[0] + ":";
                String field = "FRONT_SEAT_" + f[1] + "_";
                maps(action + "on", field + "SWITCH_" + suffix, 2);
                maps(action + "off", field + "SWITCH_" + suffix, 1);
                for (int n = 1; n <= 3; n++) maps(action + n,
                        field + (f[0].equals("massage") ? "INTEN_" : "COMMAND_") + suffix, n);
                if (f[0].equals("massage")) {
                    maps(action + "waves", field + "COMMAND_" + suffix, 1);
                    maps(action + "rollers", field + "COMMAND_" + suffix, 2);
                }
            }
        }
        maps("wheel_heat:on", "STEER_WHEEL_HEAT_SWITCH", 2);
        maps("wheel_heat:off", "STEER_WHEEL_HEAT_SWITCH", 1);
    }

    @Test public void rejectsUntrustedIpcValuesAndFeedbackFields() {
        for (String action : new String[]{"seat:rear:heat:on", "seat:both:heat:on", "seat:driver:heat:0",
                "seat:driver:heat:4", "seat:driver:heat:-1", "seat:driver:heat:01", "seat:driver:heat:on:",
                "seat:driver:heat:waves", "seat:driver:STEER_WHEEL_HEAT_SWITCH_FB:2", "wheel_heat:toggle",
                "wheel_heat:3", "seat:driver:massage:1:2", "", "seat:driver:heat", "seat:driver:heat:1.0"}) {
            assertNull(action, SeatCommand.parse(action));
        }
        assertNull(SeatCommand.parse(null));
    }
}
