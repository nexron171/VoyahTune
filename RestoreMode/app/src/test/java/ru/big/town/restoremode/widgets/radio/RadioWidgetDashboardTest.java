package ru.big.town.restoremode.widgets.radio;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.media.session.MediaController;
import android.os.Bundle;
import android.os.Looper;
import android.os.ResultReceiver;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import ru.big.town.common.RadioWidgetProtocol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class RadioWidgetDashboardTest {
    private Context context;
    private RadioWidgetDashboard dashboard;
    private RadioWidgetView view;
    private final List<Bundle> requests = new ArrayList<>();
    private final List<ResultReceiver> replies = new ArrayList<>();

    @Before
    public void connectFakeRadioSession() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("radio_widget", 0)
                .edit()
                .clear()
                .putBoolean(RadioWidgetProtocol.FM, true)
                .putInt(RadioWidgetProtocol.FREQUENCY, 10620)
                .putString(RadioWidgetProtocol.NAME, "Saved station")
                .commit();
        dashboard = new RadioWidgetDashboard(context);
        view = dashboard.create(2, 2);
        MediaController controller = mock(MediaController.class);
        doAnswer(
                        invocation -> {
                            requests.add(new Bundle(invocation.getArgument(1, Bundle.class)));
                            replies.add(invocation.getArgument(2));
                            return null;
                        })
                .when(controller)
                .sendCommand(eq(RadioWidgetProtocol.COMMAND), any(), any());
        ReflectionHelpers.setField(dashboard, "controller", controller);
        ReflectionHelpers.setField(dashboard, "watching", true);
        ReflectionHelpers.callInstanceMethod(dashboard, "requestSnapshot");
    }

    @Test
    public void playSendsDisplayedStationAndWaitsForConfirmedState() {
        reply(0, state(10620, false));
        find(view, "Включить выбранную станцию").performClick();
        assertEquals("play", requests.get(1).getString(RadioWidgetProtocol.ACTION));
        assertEquals(10620, requests.get(1).getInt(RadioWidgetProtocol.FREQUENCY));
        assertTrue(requests.get(1).getBoolean(RadioWidgetProtocol.FM));
        Bundle accepted = state(10620, false);
        accepted.putBoolean(RadioWidgetProtocol.ACCEPTED, true);
        accepted.putInt(RadioWidgetProtocol.REQUESTED_FREQUENCY, 10620);
        accepted.putBoolean(RadioWidgetProtocol.REQUESTED_FM, true);
        reply(1, accepted);
        assertFalse(find(view, "Включить выбранную станцию").isEnabled());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        reply(2, state(10620, true));
        assertTrue(find(view, "Пауза").isEnabled());
        dashboard.close();
    }

    @Test
    public void timeoutKeepsLastStationAndLateReplyAfterCloseCannotOverwriteCache() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertTrue(view.getContentDescription().toString().contains("106,2"));
        assertFalse(find(view, "Включить выбранную станцию").isEnabled());
        dashboard.close();
        reply(0, state(8750, true));
        RadioWidgetView reopened = new RadioWidgetDashboard(context).create(2, 2);
        assertTrue(reopened.getContentDescription().toString().contains("106,2"));
        assertFalse(find(reopened, "Включить выбранную станцию").isEnabled());
    }

    @Test
    public void olderPollCannotOverrideCommandAndNewStationSurvivesRecreation() {
        reply(0, state(10620, false));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        find(view, "Следующая станция").performClick();
        reply(1, state(8750, true));
        assertTrue(view.getContentDescription().toString().contains("106,2"));
        Bundle accepted = state(10620, false);
        accepted.putBoolean(RadioWidgetProtocol.ACCEPTED, true);
        accepted.putInt(RadioWidgetProtocol.REQUESTED_FREQUENCY, 10700);
        accepted.putBoolean(RadioWidgetProtocol.REQUESTED_FM, true);
        reply(2, accepted);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        reply(3, state(10700, true));
        dashboard.close();
        RadioWidgetView reopened = new RadioWidgetDashboard(context).create(2, 2);
        assertTrue(reopened.getContentDescription().toString().contains("107,0"));
    }

    private void reply(int index, Bundle state) {
        replies.get(index).send(1, state);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private Bundle state(int frequency, boolean playing) {
        Bundle state = new Bundle();
        state.putInt(RadioWidgetProtocol.SCHEMA, RadioWidgetProtocol.VERSION);
        state.putInt(RadioWidgetProtocol.FREQUENCY, frequency);
        state.putBoolean(RadioWidgetProtocol.FM, true);
        state.putBoolean(RadioWidgetProtocol.READY, true);
        state.putBoolean(RadioWidgetProtocol.CAN_SKIP, true);
        state.putBoolean(RadioWidgetProtocol.PLAYING, playing);
        return state;
    }

    private View find(View view, String description) {
        if (description.contentEquals(
                view.getContentDescription() == null ? "" : view.getContentDescription()))
            return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View child = find(group.getChildAt(index), description);
                if (child != null) return child;
            }
        }
        return null;
    }
}
