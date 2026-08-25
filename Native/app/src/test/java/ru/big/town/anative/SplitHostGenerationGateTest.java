package ru.big.town.anative;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class SplitHostGenerationGateTest {
    @Test
    public void pauseAndResumeRejectOldSupervisionSnapshot() {
        SplitHostGenerationGate gate = new SplitHostGenerationGate(41L);
        long first = gate.resumeSupervision();
        assertTrue(gate.acceptsSupervision(41L, first));

        gate.pauseSupervision();
        assertFalse(gate.acceptsSupervision(41L, first));

        long second = gate.resumeSupervision();
        assertNotEquals(first, second);
        assertFalse(gate.acceptsSupervision(41L, first));
        assertTrue(gate.acceptsSupervision(41L, second));
    }

    @Test
    public void paneReleaseRejectsOnlyThatPaneCompletion() {
        SplitHostGenerationGate gate = new SplitHostGenerationGate(9L);
        long left = gate.nextPaneGeneration(SplitHostGenerationGate.LEFT);
        long right = gate.nextPaneGeneration(SplitHostGenerationGate.RIGHT);

        gate.invalidatePane(SplitHostGenerationGate.LEFT);

        assertFalse(gate.acceptsPane(9L, SplitHostGenerationGate.LEFT, left));
        assertTrue(gate.acceptsPane(9L, SplitHostGenerationGate.RIGHT, right));
    }

    @Test
    public void hostGenerationAndCloseFenceEveryCallback() {
        SplitHostGenerationGate gate = new SplitHostGenerationGate(7L);
        long supervision = gate.resumeSupervision();
        long pane = gate.nextPaneGeneration(SplitHostGenerationGate.LEFT);

        assertFalse(gate.acceptsSupervision(8L, supervision));
        assertFalse(gate.acceptsPane(8L, SplitHostGenerationGate.LEFT, pane));

        gate.close();

        assertFalse(gate.acceptsSupervision(7L, supervision));
        assertFalse(gate.acceptsPane(7L, SplitHostGenerationGate.LEFT, pane));
        assertTrue(gate.nextPaneGeneration(SplitHostGenerationGate.LEFT)
                == SplitHostGenerationGate.REJECTED);
    }
}
