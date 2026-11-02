package ru.big.town.restoremode.widgets.brightness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import ru.big.town.common.BrightnessWidgetProtocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class BrightnessWidgetTest {
    @Test
    public void buttonsSelectDayNightAndAutoAndReflectConfirmedState() {
        List<Integer> selections = new ArrayList<>();
        BrightnessWidgetView widget = new BrightnessWidgetView(
                RuntimeEnvironment.getApplication(), selections::add);
        Bundle state = new Bundle();
        state.putBoolean(BrightnessWidgetProtocol.AVAILABLE, true);
        state.putInt(BrightnessWidgetProtocol.MODE, BrightnessWidgetProtocol.NIGHT);
        widget.update(state);
        LinearLayout buttons = (LinearLayout) widget.getChildAt(1);
        for (int index = 0; index < buttons.getChildCount(); index++) {
            View button = buttons.getChildAt(index);
            assertTrue(button.isEnabled());
            assertEquals(index == 1, button.isSelected());
            button.performClick();
        }
        assertEquals(Arrays.asList(1, 0, 2), selections);
    }

    @Test
    public void headingAndButtonsRemainInsetAfterDashboardReplacesBackground() {
        BrightnessWidgetView widget = new BrightnessWidgetView(
                RuntimeEnvironment.getApplication(), mode -> {});
        Bundle state = new Bundle();
        state.putBoolean(BrightnessWidgetProtocol.AVAILABLE, true);
        state.putString(BrightnessWidgetProtocol.MESSAGE,
                "Длинное сообщение о подключении к дисплеям автомобиля");
        widget.update(state);
        widget.setBackground(new android.graphics.drawable.ColorDrawable(0xff22252f));
        float density = widget.getResources().getDisplayMetrics().density;
        int width = Math.round(330 * density);
        int height = Math.round(110 * density);
        widget.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        widget.layout(0, 0, width, height);
        int inset = Math.round(10 * density);
        View heading = widget.getChildAt(0);
        LinearLayout buttons = (LinearLayout) widget.getChildAt(1);
        assertEquals(inset, heading.getLeft());
        assertEquals(inset, heading.getTop());
        assertEquals(width - inset, heading.getRight());
        assertTrue(heading.getBottom() < buttons.getTop());
        assertEquals(width - inset, buttons.getRight());
        assertEquals(height - inset, buttons.getBottom());
        for (int index = 0; index < buttons.getChildCount(); index++) {
            View button = buttons.getChildAt(index);
            assertTrue(button.getWidth() >= 48 * density);
            assertTrue(button.getHeight() >= 48 * density);
            if (index > 0) {
                assertTrue(button.getLeft() > buttons.getChildAt(index - 1).getRight());
            }
        }
    }

    @Test
    public void unavailableStateDisablesCommandsAndLongPressReachesDashboard() {
        BrightnessWidgetView widget = new BrightnessWidgetView(
                RuntimeEnvironment.getApplication(), mode -> {});
        LinearLayout buttons = (LinearLayout) widget.getChildAt(1);
        for (int index = 0; index < buttons.getChildCount(); index++) {
            assertFalse(buttons.getChildAt(index).isEnabled());
            assertFalse(buttons.getChildAt(index).isSelected());
        }
        boolean[] moved = {false};
        widget.setOnLongClickListener(view -> {
            moved[0] = true;
            return true;
        });
        Bundle state = new Bundle();
        state.putBoolean(BrightnessWidgetProtocol.AVAILABLE, true);
        widget.update(state);
        assertTrue(buttons.getChildAt(0).performLongClick());
        assertTrue(moved[0]);
    }
}
