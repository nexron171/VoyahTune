package ru.big.town.restoremode.settings.sections.scenarios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import ru.big.town.common.ScenarioProtocol;

/** Ввод паузы текстовым полем: секунды в допустимых границах. */
public class ScenarioPauseInputTest {

    @Test
    public void acceptsSecondsWithinRange() {
        assertEquals(Integer.valueOf(1), ScenarioSettingsPage.parsePauseSeconds("1"));
        assertEquals(Integer.valueOf(10), ScenarioSettingsPage.parsePauseSeconds("10"));
        assertEquals(Integer.valueOf(10), ScenarioSettingsPage.parsePauseSeconds(" 10 "));
        assertEquals(
                Integer.valueOf(ScenarioProtocol.MAX_PAUSE_SECONDS),
                ScenarioSettingsPage.parsePauseSeconds("600"));
    }

    @Test
    public void rejectsOutOfRangeAndGarbage() {
        assertNull("ниже минимума", ScenarioSettingsPage.parsePauseSeconds("0"));
        assertNull("выше максимума", ScenarioSettingsPage.parsePauseSeconds("601"));
        assertNull("не число", ScenarioSettingsPage.parsePauseSeconds("abc"));
        assertNull("дробное", ScenarioSettingsPage.parsePauseSeconds("2.5"));
        assertNull("пусто", ScenarioSettingsPage.parsePauseSeconds("   "));
        assertNull(ScenarioSettingsPage.parsePauseSeconds(null));
    }
}
