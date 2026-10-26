package ru.big.town.anative;

/** Massage intensity is meaningful only with a known switch state. */
final class OemSeatMassageState {
    private OemSeatMassageState() {}

    static Integer activeLevel(Integer power, Integer intensity) {
        if (power == null) {
            return null;
        }
        if (power == 1) {
            return 0;
        }
        return power == 2 && intensity != null && intensity >= 1 && intensity <= 3
                ? intensity
                : null;
    }

    static Integer nextLevel(Integer power, Integer intensity) {
        Integer current = activeLevel(power, intensity);
        return current == null ? null : current == 0 ? 3 : current - 1;
    }
}
