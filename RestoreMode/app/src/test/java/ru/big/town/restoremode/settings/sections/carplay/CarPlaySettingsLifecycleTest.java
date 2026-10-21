package ru.big.town.restoremode.settings.sections.carplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.RadioGroup;
import com.shilapi.xcertplay.CarPlayDock;
import ru.big.town.restoremode.carplay.CarPlayFeature;
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
import ru.big.town.restoremode.R;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CarPlaySettingsLifecycleTest {
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void featureSwitchHidesContentAndThemeSelectionPersistsForReconnect() throws Exception {
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
            RadioGroup choices = root.findViewById(R.id.carPlayLayoutChoices);
            assertEquals(3, choices.getChildCount());
            int[] views = {R.id.carPlayLayoutClassic, R.id.carPlayLayoutBottom, R.id.carPlayLayoutAston};
            CarPlayDock[] docks = {CarPlayDock.DRIVER_SIDE, CarPlayDock.BOTTOM, CarPlayDock.DUAL_SIDES};
            for (int index = 0; index < views.length; index++) {
                choices.check(views[index]);
                assertEquals(docks[index], CarPlayDock.Companion.load(controller.get()));
            }
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
            enabled.setChecked(false);
            shadowOf(Looper.getMainLooper()).idle();
            root.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1200, 720);
            enabled = root.findViewById(R.id.carPlayEnabled);
            assertFalse(enabled.isChecked());
            assertFalse(CarPlayFeature.isEnabled(controller.get()));
            assertEquals(View.GONE, root.findViewById(R.id.carPlayEnabledContent).getVisibility());
            assertTrue(enabled.isShown());
            assertTrue(shadowOf(RuntimeEnvironment.getApplication()).getBoundServiceConnections().isEmpty());
            enabled.setChecked(true);
            shadowOf(Looper.getMainLooper()).idle();
            root.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1200, 720);
            assertTrue(CarPlayFeature.isEnabled(controller.get()));
            assertEquals(View.VISIBLE, root.findViewById(R.id.carPlayEnabledContent).getVisibility());
            choices = root.findViewById(R.id.carPlayLayoutChoices);
            assertEquals(R.id.carPlayLayoutAston, choices.getCheckedRadioButtonId());
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
