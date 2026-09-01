package ru.big.town.anative;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Wire-format and validation helpers for ordered steering-wheel action lists. */
final class SteeringActionSequence {
    static final String PREFIX = "steer-actions-v1:";

    private SteeringActionSequence() {}

    /** Legacy single ids remain valid while RestoreMode migrates each edited slot to the list format. */
    static List<String> decode(String stored) {
        if (stored == null || stored.isEmpty() || "none".equals(stored)) {
            return new ArrayList<>();
        }
        if (!stored.startsWith(PREFIX)) {
            return new ArrayList<>(Collections.singletonList(stored));
        }

        ArrayList<String> actions = new ArrayList<>();
        int position = PREFIX.length();
        while (position < stored.length()) {
            int separator = stored.indexOf(':', position);
            if (separator <= position) return new ArrayList<>();
            final int length;
            try {
                length = Integer.parseInt(stored.substring(position, separator));
            } catch (NumberFormatException e) {
                return new ArrayList<>();
            }
            int start = separator + 1;
            int end = start + length;
            if (length <= 0 || end > stored.length()) return new ArrayList<>();
            String action = stored.substring(start, end);
            if (!"none".equals(action)) actions.add(action);
            position = end;
        }
        return actions;
    }

    static boolean contains(String stored, String target) {
        return target != null && decode(stored).contains(target);
    }

    /** Returns one exact 10-byte CAN frame or null for a malformed/non-CAN action. */
    static byte[] parseCustomCan(String action) {
        if (action == null || !action.startsWith("can:")) return null;
        String hex = action.substring("can:".length());
        if (hex.length() != 20) return null;
        byte[] frame = new byte[10];
        for (int i = 0; i < hex.length(); i += 2) {
            int high = Character.digit(hex.charAt(i), 16);
            int low = Character.digit(hex.charAt(i + 1), 16);
            if (high < 0 || low < 0) return null;
            frame[i / 2] = (byte) ((high << 4) | low);
        }
        return frame;
    }
}
