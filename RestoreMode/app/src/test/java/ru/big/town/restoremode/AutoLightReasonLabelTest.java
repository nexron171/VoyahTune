package ru.big.town.restoremode;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class AutoLightReasonLabelTest {
    @Test public void labelsKnownAndUnknownOemReasons() {
        String[] names = {"День", "Прочее", "Темно", "Тоннель", "Запуск в темноте"};
        for (int i = 0; i < names.length; i++) {
            assertEquals("SWReason: " + i + " — " + names[i], AutoLightReasonLabel.format(i));
        }
        for (int i : new int[]{5, 6, 7, 99}) {
            assertEquals("SWReason: " + i + " — Неизвестно", AutoLightReasonLabel.format(i));
        }
        assertEquals("SWReason: —", AutoLightReasonLabel.format(-1));
    }
}
