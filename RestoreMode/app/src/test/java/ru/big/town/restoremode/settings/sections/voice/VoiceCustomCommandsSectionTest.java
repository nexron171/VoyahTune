package ru.big.town.restoremode.settings.sections.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.vehicle.steering.SteeringActions;
import ru.big.town.restoremode.voice.commands.VoiceCommands;
import ru.big.town.restoremode.voice.commands.VoiceCustomCommandStore;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class VoiceCustomCommandsSectionTest {
    @Test
    public void editsWithTheSteeringPickerReordersAndDoesNotSaveOnCancel() {
        ActivityController<AppCompatActivity> controller =
                Robolectric.buildActivity(AppCompatActivity.class);
        controller.get().setTheme(R.style.Theme_RestoreMode_Settings);
        controller.setup();
        try {
            AppCompatActivity activity = controller.get();
            SharedPreferences preferences = activity.getSharedPreferences("DrivePreferences", 0);
            preferences.edit().clear().commit();
            VoiceCommands.invalidate();
            VoiceCustomCommandsSection section =
                    new VoiceCustomCommandsSection(activity, preferences, () -> {});
            View panel = section.create();
            activity.setContentView(panel);
            panel.findViewWithTag("customVoice:add").performClick();
            shadowOf(Looper.getMainLooper()).idle();
            AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
            assertFalse(editor.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled());
            ((EditText)
                            editor.findViewById(android.R.id.content)
                                    .findViewWithTag("customVoice:phrase"))
                    .setText("едем домой");
            pickAction(editor, "headlights:parking");
            pickAction(editor, "drive:COMFORT");
            View content = editor.findViewById(android.R.id.content);
            findByDescription(content, "Поднять действие 2").performClick();
            assertTrue(editor.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled());
            editor.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            VoiceCustomCommandStore.Entry saved = VoiceCustomCommandStore.load(preferences).get(0);
            assertEquals("едем домой", saved.phrase);
            assertEquals(Arrays.asList("drive:COMFORT", "headlights:parking"), saved.actions);

            panel = section.create();
            activity.setContentView(panel);
            panel.findViewWithTag("customVoice:edit:" + saved.id).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            AlertDialog editAgain = (AlertDialog) ShadowDialog.getLatestDialog();
            ((EditText)
                            editAgain
                                    .findViewById(android.R.id.content)
                                    .findViewWithTag("customVoice:phrase"))
                    .setText("другая фраза");
            editAgain.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
            assertEquals("едем домой", VoiceCustomCommandStore.load(preferences).get(0).phrase);
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private static void pickAction(AlertDialog editor, String id) {
        editor.findViewById(android.R.id.content)
                .findViewWithTag("customVoice:addAction")
                .performClick();
        AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
        ListView list = picker.getListView();
        assertEquals(SteeringActions.ALL.size() + 4, list.getAdapter().getCount());
        for (int index = 0; index < SteeringActions.ALL.size(); index++) {
            assertEquals(SteeringActions.ALL.get(index).label, list.getAdapter().getItem(index));
            if (SteeringActions.ALL.get(index).id.equals(id)) {
                list.performItemClick(null, index, index);
                return;
            }
        }
        throw new AssertionError("Action is not available: " + id);
    }

    private static View findByDescription(View view, String description) {
        if (view.getContentDescription() != null
                && description.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findByDescription(group.getChildAt(index), description);
                if (found != null) return found;
            }
        }
        return null;
    }
}
