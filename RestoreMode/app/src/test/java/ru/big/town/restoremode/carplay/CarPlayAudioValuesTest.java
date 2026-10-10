package ru.big.town.restoremode.carplay;

import org.junit.Test;
import static org.junit.Assert.*;

public class CarPlayAudioValuesTest {
    @Test public void malformedOrUnavailableLevelsCannotEnableASlider() {
        assertArrayEquals(new int[] {-1,-1,-1,-1}, new CarPlayAudioClient.Volumes(null, null).maxima);
        assertArrayEquals(new int[] {-1,-1,-1,-1}, new CarPlayAudioClient.Volumes(new int[] {0}, new int[] {32}).maxima);
        CarPlayAudioClient.Volumes partial = new CarPlayAudioClient.Volumes(new int[] {16,33,-1,4}, new int[] {32,32,32,32});
        assertArrayEquals(new int[] {16,-1,-1,4}, partial.levels);
        assertArrayEquals(new int[] {32,-1,-1,32}, partial.maxima);
    }
    @Test public void snapshotsKeepTheActualVolumeAndDoNotShareMutableArrays() {
        int[] levels = {0,16,32,5}, maxima = {32,32,32,32};
        CarPlayAudioClient.Volumes snapshot = new CarPlayAudioClient.Volumes(levels, maxima);
        levels[0] = 10; maxima[0] = 0;
        assertArrayEquals(new int[] {0,16,32,5}, snapshot.levels);
        assertArrayEquals(new int[] {32,32,32,32}, snapshot.maxima);
    }
}
