package ru.big.town.restoremode;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceResultPresentationTest {
    @Test public void fuelCapDisplaysFreeVolumeForTenSeconds() {
        assertEquals("Можно заправить ~26л бензина",
                VoiceResultPresentation.successText("port_cap:fuel", "Открыть бензобак", 26));
        assertEquals("Можно заправить ~0л бензина",
                VoiceResultPresentation.successText("port_cap:fuel", "Открыть бензобак", 0));
        assertEquals(10000, VoiceResultPresentation.successDurationMs("port_cap:fuel"));
    }

    @Test public void missingOrInvalidFuelDataNeverShowsInventedLiters() {
        for (int liters : new int[]{-1, -9999, 201, Integer.MAX_VALUE}) {
            assertEquals("Не удалось получить данные о топливе",
                    VoiceResultPresentation.successText("port_cap:fuel", "Открыть бензобак", liters));
        }
    }

    @Test public void otherCommandsKeepTheirNormalTitleAndTimeout() {
        for (String action : new String[]{"port_cap:charge", "fuel_charge:80", "energy:SREV", "drive:SPORT"}) {
            assertEquals("Название команды", VoiceResultPresentation.successText(action, "Название команды", 26));
            assertEquals(3000, VoiceResultPresentation.successDurationMs(action));
        }
    }
}
