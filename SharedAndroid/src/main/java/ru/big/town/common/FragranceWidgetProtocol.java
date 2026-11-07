package ru.big.town.common;

import android.os.Bundle;

/** Commands carry the desired OEM value; snapshots contain only observed values. */
public final class FragranceWidgetProtocol {
    public static final int WATCH = 220, UNWATCH = 221, STATE = 222;
    public static final int POWER = 223, SCENT = 224, LEVEL = 225, TIMER = 226;
    public static final String BUSY = "pending";
    public static final String MESSAGE = "message";

    private FragranceWidgetProtocol() {}

    public static final class Snapshot {
        public static final Snapshot UNKNOWN = new Snapshot(-1, -1, -1, -1);

        public final int power;
        public final int scent;
        public final int level;
        public final int timer;

        public Snapshot(int power, int scent, int level, int timer) {
            this.power = valid(power, 1, 2);
            this.scent = valid(scent, 1, 3);
            this.level = valid(level, 1, 3);
            this.timer = valid(timer, 0, 2);
        }

        private static int valid(int value, int min, int max) {
            return min <= value && value <= max ? value : -1;
        }

        public boolean available() {
            return power != -1 && scent != -1;
        }

        public boolean on() {
            return power == 2;
        }

        public boolean allows(int command, int value) {
            if (!available()) return false;
            switch (command) {
                case POWER:
                    return valid(value, 1, 2) != -1;
                case SCENT:
                    return valid(value, 1, 3) != -1;
                case LEVEL:
                    return on() && level != -1 && valid(value, 1, 3) != -1;
                case TIMER:
                    return on() && timer != -1 && valid(value, 0, 2) != -1;
                default:
                    return false;
            }
        }

        public boolean matches(int command, int value) {
            if (!allows(command, value)) return false;
            switch (command) {
                case POWER:
                    return power == value;
                case SCENT:
                    return on() && scent == value;
                case LEVEL:
                    return level == value;
                case TIMER:
                    return timer == value;
                default:
                    return false;
            }
        }

        public Bundle bundle() {
            Bundle data = new Bundle();
            data.putInt("power", power);
            data.putInt("scent", scent);
            data.putInt("level", level);
            data.putInt("timer", timer);
            return data;
        }

        public static Snapshot from(Bundle data) {
            return new Snapshot(
                    data.getInt("power", -1),
                    data.getInt("scent", -1),
                    data.getInt("level", -1),
                    data.getInt("timer", -1));
        }
    }
}
