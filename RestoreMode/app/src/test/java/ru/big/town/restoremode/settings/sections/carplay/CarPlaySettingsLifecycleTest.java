package ru.big.town.restoremode.settings.sections.carplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.shilapi.xcertplay.CarPlayDock;
import com.shilapi.xcertplay.CarPlaySettingsAccess;
import com.shilapi.xcertplay.media.CarPlayAudioSettings;

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
    public void featureSwitchPreservesScaleAndThemeDependencies() throws Exception {
        Messenger nativeService =
                new Messenger(new Handler(Looper.getMainLooper(), message -> true));
        shadowOf(RuntimeEnvironment.getApplication())
                .setComponentNameAndServiceForBindService(
                        new ComponentName(
                                "ru.big.town.anative", "ru.big.town.anative.SetModesService"),
                        nativeService.getBinder());
        ActivityController<FragmentActivity> controller =
                Robolectric.buildActivity(FragmentActivity.class);
        controller.get().setTheme(R.style.Theme_RestoreMode_Settings);
        controller.setup();
        try {
            CarPlaySettingsFragment fragment = new CarPlaySettingsFragment();
            controller
                    .get()
                    .getSupportFragmentManager()
                    .beginTransaction()
                    .add(android.R.id.content, fragment)
                    .commitNow();
            shadowOf(Looper.getMainLooper()).idle();
            View root = fragment.requireView();
            root.measure(
                    View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1200, 720);
            shadowOf(Looper.getMainLooper()).idle();
            Switch enabled = root.findViewById(R.id.carPlayEnabled);
            assertTrue(enabled.isChecked());
            Switch fullscreen = root.findViewById(R.id.carPlayFullscreen);
            assertFalse(fullscreen.isChecked());
            for (boolean selectedFullscreen : new boolean[] {true, false}) {
                fullscreen.setChecked(selectedFullscreen);
                assertEquals(
                        selectedFullscreen, CarPlaySettingsAccess.fullscreen(controller.get()));
                Intent config =
                        shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents().stream()
                                .filter(
                                        intent ->
                                                "ru.big.town.anative.DOCK_CONFIG"
                                                        .equals(intent.getAction()))
                                .reduce((first, second) -> second)
                                .get();
                assertEquals(
                        selectedFullscreen,
                        config.getBooleanExtra(CarPlayTarget.FULLSCREEN, !selectedFullscreen));
                assertEquals(
                        "ru.big.town.anative.SetModesConfigReceiver",
                        config.getComponent().getClassName());
            }
            SeekBar scale = root.findViewById(R.id.carPlayUiScale);
            assertTrue(scale.isEnabled());
            assertEquals(3, scale.getMax());
            assertEquals(2, scale.getProgress());
            assertEquals(100, CarPlaySettingsAccess.uiScalePercent(controller.get()));
            Switch hideSystemBars = root.findViewById(R.id.carPlayHideSystemBars);
            assertFalse(hideSystemBars.isChecked());
            assertFalse(hideSystemBars.isEnabled());
            android.widget.RadioGroup themes = root.findViewById(R.id.carPlayDockTheme);
            themes.check(R.id.carPlayDockBottom);
            assertEquals(CarPlayDock.BOTTOM, CarPlayDock.Companion.load(controller.get()));
            Switch ultra = root.findViewById(R.id.carPlayUltra);
            ultra.setChecked(false);
            assertFalse(root.findViewById(R.id.carPlayDockAstonMartin).isEnabled());
            ultra.setChecked(true);
            themes.check(R.id.carPlayDockAstonMartin);
            assertTrue(fullscreen.isChecked());
            assertTrue(hideSystemBars.isChecked());
            assertFalse(hideSystemBars.isEnabled());
            assertEquals(CarPlayDock.DUAL_SIDES, CarPlayDock.Companion.load(controller.get()));
            Intent astonConfig =
                    shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents().stream()
                            .filter(
                                    intent ->
                                            "ru.big.town.anative.DOCK_CONFIG"
                                                    .equals(intent.getAction()))
                            .reduce((first, second) -> second)
                            .get();
            assertTrue(astonConfig.getBooleanExtra(CarPlayTarget.FULLSCREEN, false));
            assertTrue(astonConfig.getBooleanExtra(CarPlayTarget.HIDE_SYSTEM_BARS, false));
            assertTrue(astonConfig.getBooleanExtra(CarPlayTarget.ASTON_MARTIN, false));
            fullscreen.setChecked(false);
            assertEquals(R.id.carPlayDockClassic, themes.getCheckedRadioButtonId());
            assertFalse(hideSystemBars.isChecked());
            assertFalse(hideSystemBars.isEnabled());
            fullscreen.setChecked(true);
            assertTrue(hideSystemBars.isEnabled());
            hideSystemBars.setChecked(true);
            assertTrue(CarPlaySettingsAccess.hideSystemBars(controller.get()));
            fullscreen.setChecked(false);
            fullscreen.setChecked(true);
            assertFalse(hideSystemBars.isChecked());
            assertNull(CarPlayDock.Companion.load(controller.get()).getTheme());
            int[] progress = {1, 0, 1, 2, 3};
            int[] percents = {85, 75, 85, 100, 115};
            for (int index = 0; index < progress.length; index++) {
                int key =
                        progress[index] < scale.getProgress()
                                ? KeyEvent.KEYCODE_DPAD_LEFT
                                : KeyEvent.KEYCODE_DPAD_RIGHT;
                assertTrue(scale.onKeyDown(key, new KeyEvent(KeyEvent.ACTION_DOWN, key)));
                assertEquals(progress[index], scale.getProgress());
                assertEquals(
                        percents[index], CarPlaySettingsAccess.uiScalePercent(controller.get()));
                assertEquals(
                        controller
                                .get()
                                .getString(R.string.carplay_ui_scale_value, percents[index]),
                        ((TextView) root.findViewById(R.id.carPlayUiScaleLabel))
                                .getText()
                                .toString());
            }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));
            root.measure(
                    View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1200, 720);
            android.graphics.Bitmap preview =
                    android.graphics.Bitmap.createBitmap(
                            root.getWidth(),
                            root.getHeight(),
                            android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(preview);
            canvas.drawColor(0xff101723);
            root.draw(canvas);
            java.io.File previewFile =
                    new java.io.File("build/reports/carplay-settings-preview.png");
            previewFile.getParentFile().mkdirs();
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(previewFile)) {
                preview.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
            }
            preview.recycle();
            fullscreen.setChecked(true);
            assertTrue(scale.isEnabled());
            assertEquals(3, scale.getProgress());
            assertEquals(115, CarPlaySettingsAccess.uiScalePercent(controller.get()));
            enabled.setChecked(false);
            shadowOf(Looper.getMainLooper()).idle();
            root.measure(
                    View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1200, 720);
            enabled = root.findViewById(R.id.carPlayEnabled);
            assertFalse(enabled.isChecked());
            assertFalse(CarPlayFeature.isEnabled(controller.get()));
            assertEquals(View.GONE, root.findViewById(R.id.carPlayEnabledContent).getVisibility());
            assertFalse(root.findViewById(R.id.carPlayFullscreen).isShown());
            assertTrue(enabled.isShown());
            assertTrue(
                    shadowOf(RuntimeEnvironment.getApplication())
                            .getBoundServiceConnections()
                            .isEmpty());
            enabled.setChecked(true);
            shadowOf(Looper.getMainLooper()).idle();
            root.measure(
                    View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1200, 720);
            assertTrue(CarPlayFeature.isEnabled(controller.get()));
            assertEquals(
                    View.VISIBLE, root.findViewById(R.id.carPlayEnabledContent).getVisibility());
            assertTrue(((Switch) root.findViewById(R.id.carPlayFullscreen)).isChecked());
            assertEquals(CarPlayDock.DRIVER_SIDE, CarPlayDock.Companion.load(controller.get()));
            scale = root.findViewById(R.id.carPlayUiScale);
            assertTrue(scale.isEnabled());
            assertEquals(3, scale.getProgress());
            assertEquals(115, CarPlaySettingsAccess.uiScalePercent(controller.get()));
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void bindsBeforeDeferredRowsAndRestoresVolumesAfterResume() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("voyah_carplay_audio", 0)
                .edit()
                .clear()
                .commit();
        Messenger nativeService =
                new Messenger(
                        new Handler(
                                Looper.getMainLooper(),
                                request -> {
                                    Message response =
                                            Message.obtain(null, CarPlayAudioProtocol.VALUES);
                                    Bundle values = new Bundle();
                                    values.putLong(
                                            CarPlayAudioProtocol.REQUEST,
                                            request.getData()
                                                    .getLong(CarPlayAudioProtocol.REQUEST));
                                    values.putIntArray(
                                            CarPlayAudioProtocol.LEVELS, new int[] {12, 8, 10, 6});
                                    values.putIntArray(
                                            CarPlayAudioProtocol.MAXIMA,
                                            new int[] {32, 32, 32, 32});
                                    response.setData(values);
                                    try {
                                        request.replyTo.send(response);
                                    } catch (RemoteException error) {
                                        throw new AssertionError(error);
                                    }
                                    return true;
                                }));
        shadowOf(RuntimeEnvironment.getApplication())
                .setComponentNameAndServiceForBindService(
                        new ComponentName(
                                "ru.big.town.anative", "ru.big.town.anative.SetModesService"),
                        nativeService.getBinder());

        ActivityController<FragmentActivity> controller =
                Robolectric.buildActivity(FragmentActivity.class);
        controller.get().setTheme(R.style.Theme_RestoreMode_Settings);
        controller.setup();
        try {
            CarPlaySettingsFragment fragment = new CarPlaySettingsFragment();
            controller
                    .get()
                    .getSupportFragmentManager()
                    .beginTransaction()
                    .add(android.R.id.content, fragment)
                    .commitNow();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(
                    1,
                    shadowOf(RuntimeEnvironment.getApplication())
                            .getBoundServiceConnections()
                            .size());

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
            assertTrue(
                    shadowOf(RuntimeEnvironment.getApplication())
                            .getBoundServiceConnections()
                            .isEmpty());
            controller.resume();
            shadowOf(Looper.getMainLooper()).idle();
            assertVolumes(root);
            assertTrue(!((Switch) root.findViewById(R.id.carPlayDuckMusic)).isChecked());
            assertEquals(
                    1,
                    shadowOf(RuntimeEnvironment.getApplication())
                            .getBoundServiceConnections()
                            .size());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private static void assertVolumes(View root) {
        int[] ids = {
            R.id.carPlayMediaVolume,
            R.id.carPlayNavigationVolume,
            R.id.carPlayPhoneVolume,
            R.id.carPlaySiriVolume
        };
        int[] expected = {12, 8, 10, 6};
        for (int index = 0; index < ids.length; index++) {
            SeekBar slider = root.findViewById(ids[index]);
            assertTrue(slider.isEnabled());
            assertEquals(32, slider.getMax());
            assertEquals(expected[index], slider.getProgress());
        }
    }
}
