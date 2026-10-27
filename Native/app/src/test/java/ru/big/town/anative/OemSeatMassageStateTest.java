package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

public class OemSeatMassageStateTest {
    @Test
    public void cyclesOffHighMediumLowOffFromTheActualSwitchAndIntensity() {
        int[] levels = {0, 3, 2, 1, 0};
        for (int index = 0; index < levels.length - 1; index++) {
            int power = index == 0 ? 1 : 2;
            Integer intensity = index == 0 ? 2 : levels[index];
            assertEquals(
                    Integer.valueOf(levels[index]),
                    OemSeatMassageState.activeLevel(power, intensity));
            assertEquals(
                    Integer.valueOf(levels[index + 1]),
                    OemSeatMassageState.nextLevel(power, intensity));
        }
    }

    @Test
    public void offAlwaysStartsAtHighRegardlessOfRetainedIntensity() {
        for (Integer retained : new Integer[] {null, -1, 0, 1, 2, 3, 4, 255}) {
            assertEquals(Integer.valueOf(0), OemSeatMassageState.activeLevel(1, retained));
            assertEquals(Integer.valueOf(3), OemSeatMassageState.nextLevel(1, retained));
        }
    }

    @Test
    public void unknownSwitchOrActiveIntensityDoesNotInventANextLevel() {
        for (Integer power : new Integer[] {null, -1, 0, 3, 255}) {
            assertNull(OemSeatMassageState.activeLevel(power, 3));
            assertNull(OemSeatMassageState.nextLevel(power, 3));
        }
        for (Integer intensity : new Integer[] {null, -1, 0, 4, 255}) {
            assertNull(OemSeatMassageState.activeLevel(2, intensity));
            assertNull(OemSeatMassageState.nextLevel(2, intensity));
        }
    }
}
