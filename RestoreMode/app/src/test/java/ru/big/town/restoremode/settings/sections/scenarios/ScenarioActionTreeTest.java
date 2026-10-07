package ru.big.town.restoremode.settings.sections.scenarios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import ru.big.town.restoremode.voice.commands.VoiceCommandGroups;
import ru.big.town.restoremode.voice.commands.VoiceFuelCommand;

/** Дерево выбора действия: раздел → подраздел → действие. */
public class ScenarioActionTreeTest {

    @Test
    public void seatActionsSplitBySide() {
        assertEquals(
                "Водитель",
                ScenarioSettingsPage.subGroupFor(
                        VoiceCommandGroups.Group.SEATS, "seat:driver:massage:on"));
        assertEquals(
                "Пассажир",
                ScenarioSettingsPage.subGroupFor(
                        VoiceCommandGroups.Group.SEATS, "seat:passenger:heat:2"));
    }

    @Test
    public void windowActionsSplitFromRoof() {
        assertEquals(
                "Окна",
                ScenarioSettingsPage.subGroupFor(
                        VoiceCommandGroups.Group.WINDOWS, "windows:driver:open"));
        assertEquals(
                "Окна",
                ScenarioSettingsPage.subGroupFor(VoiceCommandGroups.Group.WINDOWS, "windows:vent"));
        assertEquals(
                "Люк и шторка",
                ScenarioSettingsPage.subGroupFor(VoiceCommandGroups.Group.WINDOWS, "sunroof:vent"));
        assertEquals(
                "Люк и шторка",
                ScenarioSettingsPage.subGroupFor(
                        VoiceCommandGroups.Group.WINDOWS, "sunshade:close"));
    }

    @Test
    public void carActionsSplitByFamily() {
        assertEquals(
                "Режим движения",
                ScenarioSettingsPage.subGroupFor(VoiceCommandGroups.Group.CAR, "drive:SPORT"));
        assertEquals(
                "Энергия и заряд",
                ScenarioSettingsPage.subGroupFor(VoiceCommandGroups.Group.CAR, "energy:EV"));
        assertEquals(
                "Энергия и заряд",
                ScenarioSettingsPage.subGroupFor(VoiceCommandGroups.Group.CAR, "recycle:LOW"));
        assertEquals(
                "Энергия и заряд",
                ScenarioSettingsPage.subGroupFor(
                        VoiceCommandGroups.Group.CAR, VoiceFuelCommand.PREFIX + "75"));
        assertEquals(
                "Свет",
                ScenarioSettingsPage.subGroupFor(VoiceCommandGroups.Group.CAR, "headlights:on"));
        assertEquals(
                "Свет",
                ScenarioSettingsPage.subGroupFor(
                        VoiceCommandGroups.Group.CAR, "toggle_headlights_auto"));
        assertEquals(
                "Тумблеры",
                ScenarioSettingsPage.subGroupFor(VoiceCommandGroups.Group.CAR, "forced_ev:on"));
        assertEquals(
                "Тумблеры",
                ScenarioSettingsPage.subGroupFor(
                        VoiceCommandGroups.Group.CAR, "suspension_maintenance:off"));
        assertEquals(
                "Сервис и лючки",
                ScenarioSettingsPage.subGroupFor(VoiceCommandGroups.Group.CAR, "port_cap:fuel"));
        assertEquals(
                "Сервис и лючки",
                ScenarioSettingsPage.subGroupFor(VoiceCommandGroups.Group.CAR, "wash"));
    }

    @Test
    public void heatingHasNoSubsections() {
        assertNull(
                ScenarioSettingsPage.subGroupFor(
                        VoiceCommandGroups.Group.HEATING, "wheel_heat:on"));
    }

    @Test
    public void otherActionsSplitByKind() {
        assertEquals(
                "Приложения",
                ScenarioSettingsPage.subGroupFor(
                        VoiceCommandGroups.Group.OTHER, "app:com.example.app"));
        assertEquals(
                "Сплиты и экраны",
                ScenarioSettingsPage.subGroupFor(VoiceCommandGroups.Group.OTHER, "split:0"));
        assertEquals(
                "Звонки",
                ScenarioSettingsPage.subGroupFor(
                        VoiceCommandGroups.Group.OTHER, "call:88005553535"));
        assertEquals(
                "Свои команды",
                ScenarioSettingsPage.subGroupFor(
                        VoiceCommandGroups.Group.OTHER, "can:7a080000000001000000"));
        assertEquals(
                "Система",
                ScenarioSettingsPage.subGroupFor(VoiceCommandGroups.Group.OTHER, "open_voyahtune"));
    }
}
