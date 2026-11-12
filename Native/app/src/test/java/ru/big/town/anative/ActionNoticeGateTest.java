package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

public class ActionNoticeGateTest {
    @Test
    public void fuelFlapNoticeBypassesDisabledCommandNotifications() {
        ActionNoticeGate gate = new ActionNoticeGate();
        assertFalse(gate.accept(false, true, "Сценарий выполнен", 100, 3000));
        assertTrue(gate.accept(false, false, "Можно заправить примерно 28 л", 101, 3000));
    }

    @Test
    public void disablingSuppressesPendingMessagesWithoutConsumingTheirDeduplicationSlot() {
        ActionNoticeGate gate = new ActionNoticeGate();
        assertFalse(gate.accept(false, true, "Обогрев руля: включение", 100, 3000));
        assertTrue(gate.accept(true, true, "Обогрев руля: включение", 101, 3000));
        assertFalse(gate.accept(false, true, "Сценарий выполнен", 102, 3000));
        assertTrue(gate.accept(true, true, "Сценарий выполнен", 103, 3000));
    }

    @Test
    public void identicalMessagesAreSuppressedOnlyForTheirLifetime() {
        ActionNoticeGate gate = new ActionNoticeGate();
        assertTrue(gate.accept(true, true, "Готово", 100, 3000));
        assertFalse(gate.accept(true, true, "Готово", 3099, 3000));
        assertTrue(gate.accept(true, true, "Готово", 3100, 3000));
        assertTrue(gate.accept(true, true, "Ошибка", 3101, 5000));
        assertFalse(gate.accept(true, true, "Ошибка", 8100, 5000));
        assertTrue(gate.accept(true, true, "Ошибка", 8101, 5000));
    }
}
