package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EventRetryBudgetTest {
    @Test
    public void retriesAreFiniteWithinOneEventScope() {
        EventRetryBudget budget = new EventRetryBudget(2);
        budget.reset(7L);

        assertTrue(budget.claim(7L));
        assertTrue(budget.claim(7L));
        assertFalse(budget.claim(7L));
        assertEquals(2, budget.claimedForTest());
    }

    @Test
    public void newRealEventRearmsAndRejectsStaleTimer() {
        EventRetryBudget budget = new EventRetryBudget(1);
        budget.reset(10L);
        assertTrue(budget.claim(10L));

        budget.reset(11L);

        assertFalse(budget.claim(10L));
        assertTrue(budget.isCurrent(11L));
        assertTrue(budget.claim(11L));
    }

    @Test
    public void closeRejectsCurrentAndFutureScopes() {
        EventRetryBudget budget = new EventRetryBudget(1);
        budget.reset(1L);
        budget.close();
        budget.reset(2L);

        assertFalse(budget.isCurrent(1L));
        assertFalse(budget.isCurrent(2L));
        assertFalse(budget.claim(2L));
    }
}
