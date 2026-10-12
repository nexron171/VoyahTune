package ru.big.town.restoremode.settings.sections.carplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
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
import com.shilapi.xcertplay.media.CarPlayAudioSettings;

import androidx.fragment.app.FragmentActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import ru.big.town.common.CarPlayAudioProtocol;
import ru.big.town.restoremode.R;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CarPlaySettingsLifecycleTest {
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
