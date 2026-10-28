package ru.voyahtune.contracts;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.voyahtune.agent.policy.RadioRestoreController;
import ru.voyahtune.agent.policy.RadioStation;

public final class RadioControllersTest {
    public static void main(String[] arguments) throws Exception {
        verify();
        System.out.println("RadioControllersTest: passed");
    }

    static void verify() throws Exception {
        JsonObject initial = JsonParser.parseString("{\"saved\":{\"fm\":true,\"freq\":10170,\"rds\":true,\"pi\":1234,\"ta\":false},\"current\":{\"fm\":true,\"freq\":8750,\"rds\":false,\"pi\":0,\"ta\":false},\"ready\":true,\"focus\":true,\"playing\":false}").getAsJsonObject();
        RadioContractDriver.State state = new RadioContractDriver.State(initial);
        RadioRestoreController controller = new RadioRestoreController(state, state.saved);
        require(controller.restoreSilent(1) && state.selected.frequency == 10170 && state.tunes.isEmpty() && state.plays == 0 && state.owned == null,
                "Silent ACC restoration changed tuner/audio state");
        require(!controller.restoreSilent(1) && !controller.restoreSilent(0), "Silent cycle was repeated or invalid cycle accepted");
        RadioStation choice = new RadioStation(true, 10230, false, 0, false, "Choice");
        controller.select(choice);
        require(state.selected == null && !controller.restoreSilent(2), "Later ACC pass undid explicit selection");
        state.scheduler.advance(15001);
        require(!controller.restoreSilent(2) && controller.restoreSilent(3), "Same rejected cycle replayed after selection expired");
        state.playing = true;
        require(!controller.restoreSilent(4), "Silent restoration changed active playback selection");
        state.playing = false;
        controller.resume(() -> state.fallbacks++, false);
        require(state.tunes.size() == 1 && !controller.restoreSilent(5), "Silent restoration replaced active playback request");
        controller.close();
        state.scheduler.advance(20000);
        require(state.owned == null && state.tunes.size() == 1 && !controller.restoreSilent(6), "Closed controller retained pending tune");
        require(new RadioStation(false, 150, false, 0, false, null).persistable()
                && new RadioStation(false, 30000, false, 65535, false, null).persistable()
                && !new RadioStation(false, 149, false, 0, false, null).persistable()
                && !new RadioStation(true, 10801, false, 0, false, null).persistable()
                && !new RadioStation(true, 6500, null, null, false, null).persistable(), "Station persistence boundary accepted invalid shape/range");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
