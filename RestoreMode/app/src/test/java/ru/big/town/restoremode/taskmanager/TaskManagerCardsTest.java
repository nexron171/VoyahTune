package ru.big.town.restoremode.taskmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class TaskManagerCardsTest {

    @Test
    public void closeAllCardNeedsAtLeastOneApp() {
        assertFalse(TaskManagerCards.hasCloseAllCard(null));
        assertFalse(TaskManagerCards.hasCloseAllCard(Collections.<String>emptyList()));
        assertTrue(TaskManagerCards.hasCloseAllCard(Collections.singletonList("com.example.app")));
        assertTrue(TaskManagerCards.hasCloseAllCard(Arrays.asList("a", "b")));
    }

    @Test
    public void sixSlotsIncludeTheCloseAllCard() {
        assertEquals(6, TaskManagerCards.VISIBLE_SLOTS);
        assertEquals(5, TaskManagerCards.APP_SLOTS);
    }

    @Test
    public void cardWidthSplitsAvailableSpaceIntoVisibleSlots() {
        // 1763 px полезной ширины (1920 минус полоса дока) и промежутки 12 px.
        int width = TaskManagerCards.cardWidth(1763, 12);
        assertEquals((1763 - 12 * 5) / 6, width);
        // Ряд из шести карточек с промежутками не выходит за доступную ширину.
        assertTrue(TaskManagerCards.VISIBLE_SLOTS * width + 12 * 5 <= 1763);
    }

    @Test
    public void cardWidthNeverGoesNegative() {
        assertEquals(0, TaskManagerCards.cardWidth(0, 12));
        assertEquals(0, TaskManagerCards.cardWidth(40, 12));
    }
}
