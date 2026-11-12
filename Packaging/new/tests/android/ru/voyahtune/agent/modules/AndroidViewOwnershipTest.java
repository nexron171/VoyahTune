package ru.voyahtune.agent.modules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import android.app.Application;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RadioGroup;
import android.widget.RelativeLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import ru.voyahtune.agent.runtime.AndroidViewOwnership;
import ru.voyahtune.agent.runtime.ViewOwnership;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE, application = Application.class)
public class AndroidViewOwnershipTest {
    private final PassengerDockViewAccess access =
            new PassengerDockViewAccess(new AndroidViewOwnership());

    @Test
    public void temperatureContainerStillMeasuresAfterChangingTopMargin() throws Exception {
        verifyFrameMargin("topMargin", 37);
    }

    @Test
    public void frameContainerStillMeasuresAfterChangingBottomMargin() throws Exception {
        verifyFrameMargin("bottomMargin", 0);
    }

    private void verifyFrameMargin(String property, int value) throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout panel = new FrameLayout(context);
        View temperature = new View(context);
        FrameLayout.LayoutParams original = new FrameLayout.LayoutParams(40, 20, Gravity.END);
        original.setMargins(3, 11, 5, 13);
        original.setMarginStart(7);
        original.setMarginEnd(9);
        panel.addView(temperature, original);

        try (ViewOwnership ownership = new ViewOwnership(access)) {
            ownership.write(temperature, property, value);
            measure(panel);
            FrameLayout.LayoutParams actual = (FrameLayout.LayoutParams) temperature.getLayoutParams();
            assertEquals(value, access.read(temperature, property));
            assertEquals(Gravity.END, actual.gravity);
            assertEquals(7, actual.getMarginStart());
            assertEquals(9, actual.getMarginEnd());
            assertEquals(40, temperature.getMeasuredWidth());
            assertEquals(20, temperature.getMeasuredHeight());
            assertEquals(property.equals("topMargin") ? 13 : 11,
                    property.equals("topMargin") ? actual.bottomMargin : actual.topMargin);
        }

        measure(panel);
        assertEquals(11, access.read(temperature, "topMargin"));
        assertEquals(13, access.read(temperature, "bottomMargin"));
    }

    @Test
    public void radioButtonsKeepIndependentMarginsAndLayoutProperties() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        RadioGroup group = new RadioGroup(context);
        View first = new View(context);
        View second = new View(context);
        RadioGroup.LayoutParams shared = new RadioGroup.LayoutParams(40, 20, 2.0f);
        shared.gravity = Gravity.END;
        shared.topMargin = 11;
        shared.bottomMargin = 13;
        shared.setMarginStart(7);
        group.addView(first, shared);
        group.addView(second, shared);

        try (ViewOwnership ownership = new ViewOwnership(access)) {
            ownership.write(first, "topMargin", 37);
            ownership.write(first, "bottomMargin", 0);
            measure(group);
            RadioGroup.LayoutParams changed = (RadioGroup.LayoutParams) first.getLayoutParams();
            assertNotSame(shared, changed);
            assertSame(shared, second.getLayoutParams());
            assertEquals(11, shared.topMargin);
            assertEquals(13, shared.bottomMargin);
            assertEquals(37, changed.topMargin);
            assertEquals(0, changed.bottomMargin);
            assertEquals(2.0f, changed.weight, 0.0f);
            assertEquals(Gravity.END, changed.gravity);
            assertEquals(7, changed.getMarginStart());
        }

        measure(group);
        assertEquals(11, access.read(first, "topMargin"));
        assertEquals(13, access.read(first, "bottomMargin"));
        assertSame(shared, second.getLayoutParams());
    }

    @Test
    public void otherContainersKeepTheirLayoutRules() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        RelativeLayout panel = new RelativeLayout(context);
        View child = new View(context);
        RelativeLayout.LayoutParams original = new RelativeLayout.LayoutParams(40, 20);
        original.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        panel.addView(child, original);

        access.write(child, "topMargin", 37);
        access.write(child, "bottomMargin", 9);
        measure(panel);

        RelativeLayout.LayoutParams actual = (RelativeLayout.LayoutParams) child.getLayoutParams();
        assertEquals(RelativeLayout.TRUE, actual.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM));
        assertEquals(37, actual.topMargin);
        assertEquals(9, actual.bottomMargin);
    }

    private static void measure(View view) {
        int size = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY);
        view.measure(size, size);
        view.layout(0, 0, 400, 400);
    }
}
