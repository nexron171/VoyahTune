package ru.big.town.restoremode.settings.sections.carplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.view.KeyEvent;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.shilapi.xcertplay.CarPlayDock;
import com.shilapi.xcertplay.CarPlaySettingsAccess;
import com.shilapi.xcertplay.media.CarPlayAudioSettings;

import androidx.fragment.app.FragmentActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import ru.big.town.common.CarPlayAudioProtocol;
import ru.big.town.common.CarPlayTarget;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.carplay.CarPlayFeature;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CarPlaySettingsLifecycleTest {
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void featureSwitchHidesContentAndThemeAndScalePersistForReconnect() throws Exception {
        Messenger nativeService = new Messenger(new Handler(Looper.getMainLooper(), message -> true));
        shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
                new ComponentName("ru.big.town.anative", "ru.big.town.anative.SetModesService"),
                nativeService.getBinder());
        ActivityController<FragmentActivity> controller = Robolectric.buildActivity(FragmentActivity.class);
        controller.get().setTheme(R.style.Theme_RestoreMode_Settings);
        controller.setup();
        try {
            CarPlaySettingsFragment fragment = new CarPlaySettingsFragment();
            controller.get().getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, fragment).commitNow();
            shadowOf(Looper.getMainLooper()).idle();
            View root = fragment.requireView();
            root.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1200, 720);
            shadowOf(Looper.getMainLooper()).idle();
            Switch enabled = root.findViewById(R.id.carPlayEnabled);
            assertTrue(enabled.isChecked());
            Switch fullscreen = root.findViewById(R.id.carPlayFullscreen);
            assertFalse(fullscreen.isChecked());
            for (boolean selectedFullscreen : new boolean[] {true, false}) {
                fullscreen.setChecked(selectedFullscreen);
                assertEquals(selectedFullscreen, CarPlaySettingsAccess.fullscreen(controller.get()));
                Intent config = shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents().stream()
                        .filter(intent -> "ru.big.town.anative.DOCK_CONFIG".equals(intent.getAction()))
                        .reduce((first, second) -> second).get();
                assertEquals(selectedFullscreen, config.getBooleanExtra(CarPlayTarget.FULLSCREEN, !selectedFullscreen));
                assertEquals("ru.big.town.anative.SetModesConfigReceiver", config.getComponent().getClassName());
            }
            RadioGroup choices = root.findViewById(R.id.carPlayLayoutChoices);
            SeekBar scale = root.findViewById(R.id.carPlayUiScale);
            assertTrue(scale.isEnabled());
            assertEquals(3, scale.getMax());
            assertEquals(2, scale.getProgress());
            assertEquals(100, CarPlaySettingsAccess.uiScalePercent(controller.get()));
            assertEquals(3, choices.getChildCount());
            int[] views = {R.id.carPlayLayoutClassic, R.id.carPlayLayoutBottom, R.id.carPlayLayoutAston};
            CarPlayDock[] docks = {CarPlayDock.DRIVER_SIDE, CarPlayDock.BOTTOM, CarPlayDock.DUAL_SIDES};
            for (int index = 0; index < views.length; index++) {
                choices.check(views[index]);
                assertEquals(docks[index], CarPlayDock.Companion.load(controller.get()));
                assertEquals(docks[index] != CarPlayDock.DUAL_SIDES, scale.isEnabled());
            }
            assertEquals(View.VISIBLE, root.findViewById(R.id.carPlayUiScaleFixedTheme).getVisibility());
            choices.check(R.id.carPlayLayoutBottom);
            assertEquals(View.GONE, root.findViewById(R.id.carPlayUiScaleFixedTheme).getVisibility());
            int[] progress = {1, 0, 1, 2, 3};
            int[] percents = {85, 75, 85, 100, 115};
            for (int index = 0; index < progress.length; index++) {
                int key = progress[index] < scale.getProgress() ? KeyEvent.KEYCODE_DPAD_LEFT
                        : KeyEvent.KEYCODE_DPAD_RIGHT;
                assertTrue(scale.onKeyDown(key, new KeyEvent(KeyEvent.ACTION_DOWN, key)));
                assertEquals(progress[index], scale.getProgress());
                assertEquals(percents[index], CarPlaySettingsAccess.uiScalePercent(controller.get()));
                assertEquals(controller.get().getString(R.string.carplay_ui_scale_value, percents[index]),
                        ((TextView) root.findViewById(R.id.carPlayUiScaleLabel)).getText().toString());
            }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));
            root.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1200, 720);
            android.graphics.Bitmap preview = android.graphics.Bitmap.createBitmap(
                    root.getWidth(), root.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(preview);
            canvas.drawColor(0xff101723);
            root.draw(canvas);
            java.io.File previewFile = new java.io.File("build/reports/carplay-settings-preview.png");
            previewFile.getParentFile().mkdirs();
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(previewFile)) {
                preview.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
            }
            preview.recycle();
            fullscreen.setChecked(true);
            choices.check(R.id.carPlayLayoutAston);
            assertFalse(scale.isEnabled());
            assertEquals(2, scale.getProgress());
            assertEquals(115, CarPlaySettingsAccess.uiScalePercent(controller.get()));
            enabled.setChecked(false);
            shadowOf(Looper.getMainLooper()).idle();
            root.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1200, 720);
            enabled = root.findViewById(R.id.carPlayEnabled);
            assertFalse(enabled.isChecked());
            assertFalse(CarPlayFeature.isEnabled(controller.get()));
            assertEquals(View.GONE, root.findViewById(R.id.carPlayEnabledContent).getVisibility());
            assertFalse(root.findViewById(R.id.carPlayFullscreen).isShown());
            assertTrue(enabled.isShown());
            assertTrue(shadowOf(RuntimeEnvironment.getApplication()).getBoundServiceConnections().isEmpty());
            enabled.setChecked(true);
            shadowOf(Looper.getMainLooper()).idle();
            root.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1200, 720);
            assertTrue(CarPlayFeature.isEnabled(controller.get()));
            assertEquals(View.VISIBLE, root.findViewById(R.id.carPlayEnabledContent).getVisibility());
            assertTrue(((Switch) root.findViewById(R.id.carPlayFullscreen)).isChecked());
            choices = root.findViewById(R.id.carPlayLayoutChoices);
            assertEquals(R.id.carPlayLayoutAston, choices.getCheckedRadioButtonId());
            scale = root.findViewById(R.id.carPlayUiScale);
            assertFalse(scale.isEnabled());
            choices.check(R.id.carPlayLayoutClassic);
            assertTrue(scale.isEnabled());
            assertEquals(3, scale.getProgress());
            assertEquals(115, CarPlaySettingsAccess.uiScalePercent(controller.get()));
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void bindsBeforeDeferredRowsAndRestoresVolumesAfterResume() {
        RuntimeEnvironment.getApplication().getSharedPreferences("voyah_carplay_audio", 0)
                .edit().clear().commit();
        Messenger nativeService = new Messenger(new Handler(Looper.getMainLooper(), request -> {
            Message response = Message.obtain(null, CarPlayAudioProtocol.VALUES);
            Bundle values = new Bundle();
            values.putLong(CarPlayAudioProtocol.REQUEST,
                    request.getData().getLong(CarPlayAudioProtocol.REQUEST));
            values.putIntArray(CarPlayAudioProtocol.LEVELS, new int[] {12, 8, 10, 6});
            values.putIntArray(CarPlayAudioProtocol.MAXIMA, new int[] {32, 32, 32, 32});
            response.setData(values);
            try {
                request.replyTo.send(response);
            } catch (RemoteException error) {
                throw new AssertionError(error);
            }
            return true;
        }));
        shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
                new ComponentName("ru.big.town.anative", "ru.big.town.anative.SetModesService"),
                nativeService.getBinder());

        ActivityController<FragmentActivity> controller = Robolectric.buildActivity(FragmentActivity.class);
        controller.get().setTheme(R.style.Theme_RestoreMode_Settings);
        controller.setup();
        try {
            CarPlaySettingsFragment fragment = new CarPlaySettingsFragment();
            controller.get().getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, fragment).commitNow();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, shadowOf(RuntimeEnvironment.getApplication())
                    .getBoundServiceConnections().size());

            View root = fragment.requireView();
            int width = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY);
            int height = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
            root.measure(width, height);
            root.layout(0, 0, 1920, 1080);
            shadowOf(Looper.getMainLooper()).idle();
            assertVolumes(root);
            Switch duckMusic = root.findViewById(R.id.carPlayDuckMusic);
            assertTrue(duckMusic.isChecked());
            duckMusic.setChecked(false);
            assertTrue(!CarPlayAudioSettings.duckMusic(controller.get()));

            controller.pause();
            assertTrue(shadowOf(RuntimeEnvironment.getApplication()).getBoundServiceConnections().isEmpty());
            controller.resume();
            shadowOf(Looper.getMainLooper()).idle();
            assertVolumes(root);
            assertTrue(!((Switch) root.findViewById(R.id.carPlayDuckMusic)).isChecked());
            assertEquals(1, shadowOf(RuntimeEnvironment.getApplication())
                    .getBoundServiceConnections().size());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private static void assertVolumes(View root) {
        int[] ids = {R.id.carPlayMediaVolume, R.id.carPlayNavigationVolume,
                R.id.carPlayPhoneVolume, R.id.carPlaySiriVolume};
        int[] expected = {12, 8, 10, 6};
        for (int index = 0; index < ids.length; index++) {
            SeekBar slider = root.findViewById(ids[index]);
            assertTrue(slider.isEnabled());
            assertEquals(32, slider.getMax());
            assertEquals(expected[index], slider.getProgress());
        }
    }
}
