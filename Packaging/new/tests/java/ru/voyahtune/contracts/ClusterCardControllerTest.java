package ru.voyahtune.contracts;

import java.util.ArrayList;
import java.util.List;
import ru.voyahtune.agent.policy.ClusterCardController;

public final class ClusterCardControllerTest {
    public static void main(String[] arguments) throws Exception { verify(); System.out.println("ClusterCardControllerTest: passed"); }

    static void verify() throws Exception {
        Fixture fixture = new Fixture();
        ClusterCardController controller = new ClusterCardController(fixture);
        controller.configure();
        fixture.scheduler.advance(4000);
        require(fixture.launches == 0 && fixture.themes == 1 && fixture.cards.isEmpty(), "Disabled startup launched navigator or failed to restore theme");
        controller.broadcast(null, "DAY", null, true);
        require(!fixture.enabled && fixture.theme.equals("day") && fixture.launches == 0, "Theme-only broadcast toggled navigation");
        controller.broadcast("maps/.Main", null, 1, true);
        require(fixture.enabled && fixture.launches == 1 && fixture.displayLaunched == 7 && fixture.cards.isEmpty(), "Explicit enable did not launch before presenting card");
        fixture.scheduler.advance(1499);
        require(fixture.cards.isEmpty(), "Card was announced before draw delay");
        fixture.scheduler.advance(1);
        require(fixture.cards.equals(List.of(ClusterCardController.NAVI_FULL)), "NAVI_FULL not selected after first frame delay");
        controller.presentation(ClusterCardController.NAVI_FULL, 1);
        fixture.scheduler.advance(0);
        require(fixture.launches == 1, "MCU echo restarted navigator inside cooldown");
        fixture.scheduler.advance(5000);
        controller.presentation(ClusterCardController.NAVI, 1);
        fixture.scheduler.advance(0);
        fixture.scheduler.advance(1500);
        require(fixture.cards.get(fixture.cards.size() - 1).equals(ClusterCardController.NAVI_FULL) && fixture.launches == 1,
                "Plain NAVI reached unsupported category or running navigator was relaunched");
        fixture.display = 9;
        controller.displayRecreated();
        fixture.scheduler.advance(2500);
        require(fixture.launches == 2 && fixture.displayLaunched == 9, "New virtual display was not rearmed");
        controller.broadcast(null, null, 0, true);
        int cards = fixture.cards.size();
        fixture.scheduler.advance(1500);
        require(fixture.cards.size() == cards && fixture.cards.get(cards - 1).equals(ClusterCardController.MEDIA_SMALL), "Queued show revived disabled navigation");
        controller.close();
        fixture.scheduler.advance(10000);
        require(fixture.launches == 2, "Closed card controller retained launch timer");

        Fixture early = new Fixture();
        early.enabled = true;
        ClusterCardController earlyController = new ClusterCardController(early);
        earlyController.configure();
        earlyController.presentation(ClusterCardController.NAVI_FULL, 1);
        early.scheduler.advance(0);
        early.scheduler.advance(5000);
        require(early.launches == 1, "Startup timer restarted an already launched navigator");
        earlyController.close();

        Fixture revision = new Fixture();
        revision.enabled = true;
        ClusterCardController revisionController = new ClusterCardController(revision);
        revisionController.configure();
        revisionController.presentation(ClusterCardController.NAVI_FULL, 1);
        revision.scheduler.advance(0);
        revision.scheduler.advance(500);
        revisionController.configure();
        revision.scheduler.advance(999);
        require(revision.cards.isEmpty(), "Revision moved pending card deadline earlier");
        revision.scheduler.advance(1);
        require(revision.cards.size() == 1 && revision.launches == 1, "Revision lost or duplicated pending card command");
        revisionController.close();
        require(revision.announcements.get(revision.announcements.size() - 1) == 0 && revision.cards.get(revision.cards.size() - 1).equals(ClusterCardController.MEDIA_SMALL),
                "Cleanup did not release owned MCU/local card state");
    }

    static final class Fixture implements ClusterCardController.IO {
        final ManualScheduler scheduler = new ManualScheduler();
        final List<Integer> announcements = new ArrayList<>();
        final List<String> cards = new ArrayList<>();
        boolean enabled;
        boolean running;
        String theme = "night";
        String component;
        int display = 7;
        int displayLaunched = -1;
        int launches;
        int themes;
        @Override public long now() { return scheduler.now(); }
        @Override public boolean enabled() { return enabled; }
        @Override public void enabled(boolean value) { enabled = value; }
        @Override public void component(String value) { component = value; }
        @Override public void theme(String value) { theme = value; }
        @Override public int display() { return display; }
        @Override public boolean navigatorRunning() { return running; }
        @Override public void launch(int display, boolean restart) { displayLaunched = display; launches++; running = true; }
        @Override public void announce(int state) { announcements.add(state); }
        @Override public void switchCard(String category) { cards.add(category); }
        @Override public void applyTheme() { themes++; }
        @Override public void schedule(long delay, Runnable task) { scheduler.schedule(delay, task); }
        @Override public void error(Exception failure) { throw new AssertionError(failure); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
