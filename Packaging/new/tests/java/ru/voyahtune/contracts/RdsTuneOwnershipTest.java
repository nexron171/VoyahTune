package ru.voyahtune.contracts;

import ru.voyahtune.agent.modules.RdsTuneOwnership;
import ru.voyahtune.agent.policy.RadioStation;

public final class RdsTuneOwnershipTest {
    public static void main(String[] arguments) throws Exception { verify(); System.out.println("RdsTuneOwnershipTest: passed"); }

    static void verify() throws Exception {
        Manager manager = new Manager();
        RdsTuneOwnership[] owner = new RdsTuneOwnership[1];
        int[] calls = {0};
        Runnable[] whileTuning = {null};
        owner[0] = new RdsTuneOwnership(manager, Station.class, (role, receiver, values) -> {
            calls[0]++;
            if (role.equals("tune")) {
                if (whileTuning[0] != null) whileTuning[0].run();
                manager.tempPlayStation = (Station) values[0];
                manager.actionCode = 1002;
            }
            return null;
        });
        RadioStation saved = new RadioStation(false, 999, true, 1234, false, null);
        owner[0].tune(saved);
        Station command = manager.tempPlayStation;
        require(command.frequency == 999 && !command.fm && !command.rds && command.pi == 0 && owner[0].owns(command), "Tune did not sanitize stale RDS identity");
        owner[0].cancel();
        require(manager.tempPlayStation == null && manager.actionCode == -1 && manager.handler.removals == 1 && !owner[0].hasOwned(), "Owned pending tune not cancelled");
        owner[0].tune(saved);
        Station newer = new Station(9800);
        manager.tempPlayStation = newer;
        manager.delayPlayStation = newer;
        manager.actionCode = 77;
        owner[0].cancel();
        require(manager.tempPlayStation == newer && manager.delayPlayStation == newer && manager.actionCode == 77 && manager.handler.removals == 1,
                "Cancellation removed a later OEM/user command");
        whileTuning[0] = () -> {
            try { owner[0].cancel(); }
            catch (Exception failure) { throw new AssertionError(failure); }
        };
        owner[0].tune(saved);
        require(manager.tempPlayStation == null && !owner[0].hasOwned() && owner[0].dispatching() == null && manager.handler.removals == 2,
                "Cancellation during dispatch missed the subsequently queued command");
        whileTuning[0] = null;
        manager.delayPlay = true;
        owner[0].resumeDelay(true);
        owner[0].cancel();
        require(!manager.delayPlay, "Owned readiness delay leaked after cancellation");
        owner[0].playCurrent();
        require(calls[0] == 4 && owner[0].playbackCalls() == 4, "Playback attempt accounting changed");
        owner[0].tune(saved);
        Station confirmed = manager.tempPlayStation;
        owner[0].release();
        require(manager.tempPlayStation == confirmed && !owner[0].hasOwned(), "Confirmed release cleared OEM state");
    }

    static final class Manager {
        Station delayPlayStation;
        Station tempPlayStation;
        boolean delayPlay;
        int actionCode = -1;
        final Handler handler = new Handler();
    }
    static final class Handler { int removals; void removeMessages(int code) { require(code == 1002, "Unrelated OEM message removed"); removals++; } }
    static final class Station {
        final int frequency;
        boolean fm;
        boolean rds;
        int pi;
        Station(int frequency) { this.frequency = frequency; }
        void setIsFMStation(boolean value) { fm = value; }
        void setIsRDS(boolean value) { rds = value; }
        void setPICode(int value) { pi = value; }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
