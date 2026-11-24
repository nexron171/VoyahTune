package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class ActionNoticeTextTest {
    @Test
    public void resolvedTargetsDescribeTheCommandAndNotTheCycle() {
        assertEquals(
                "Обогрев руля: включение",
                ActionNoticeText.action("wheel_heat:on", value -> value));
        assertEquals(
                "Обогрев руля: выключение",
                ActionNoticeText.action("wheel_heat:off", value -> value));
        assertEquals("Энергорежим: Fuel", ActionNoticeText.action("energy:REV", value -> value));
        assertEquals(
                "Фары: автоматический режим",
                ActionNoticeText.action("headlights:auto", value -> value));
        assertEquals(
                "Звук предупреждения пешеходов: включение",
                ActionNoticeText.action("pedestrian:on", value -> value));
    }

    @Test
    public void applicationAndSplitUseDisplayNames() {
        assertEquals(
                "Открытие «Карты» на приборной панели",
                ActionNoticeText.action(
                        "cluster_app:media:com.example.maps", packageName -> "Карты"));
        assertEquals("Открытие «Карты»", ActionNoticeText.action("app:maps", value -> "Карты"));
        assertEquals(
                "Открытие сплита: Карты + Музыка",
                ActionNoticeText.action(
                        "split:maps,music,50", value -> value.equals("maps") ? "Карты" : "Музыка"));
    }

    @Test
    public void sequenceCombinesResultsAndKeepsFailuresVisible() {
        assertEquals(
                "Обогрев руля: включение; Режим движения: Sport",
                ActionNoticeText.sequence(
                        Arrays.asList(
                                ActionResult.sent("wheel_heat:on"),
                                ActionResult.sent("drive:SPORT")),
                        value -> value));
        assertEquals(
                "Отправлено 1 из 2 действий. Нет связи с автомобилем",
                ActionNoticeText.sequence(
                        Arrays.asList(
                                ActionResult.sent("wheel_heat:on"),
                                ActionResult.failed("drive:SPORT", "Нет связи с автомобилем")),
                        value -> value));
        assertEquals(
                "Не удалось отправить команду",
                ActionNoticeText.sequence(
                        Collections.singletonList(ActionResult.failed("wheel_heat:on", null)),
                        value -> value));
    }

    @Test
    public void oversizedSequenceUsesCount() {
        assertEquals(
                "Действия кнопки отправлены: 10",
                ActionNoticeText.sequence(
                        Collections.nCopies(10, ActionResult.sent("wheel_heat:on")),
                        value -> value));
    }

    @Test
    public void scenarioNeverReportsSuccessAfterFailedSteps() {
        assertEquals("Сценарий «Зима» выполнен", ActionNoticeText.scenario("Зима", 2, 2));
        assertEquals(
                "Сценарий «Зима»: выполнено 1 из 2 действий",
                ActionNoticeText.scenario("Зима", 1, 2));
        assertEquals(
                "Не удалось выполнить сценарий «Зима»", ActionNoticeText.scenario("Зима", 0, 2));
    }
}
