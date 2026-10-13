package ru.big.town.restoremode.carplay;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Activity;
import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import com.shilapi.xcertplay.CarPlayHomeUi;
import com.shilapi.xcertplay.DiPlayActivity;
import com.shilapi.xcertplay.VoyahCarPlayIntegration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import ru.big.town.restoremode.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class, qualifiers = "ru-w1280dp-h720dp-land")
public class CarPlayHomeScreenUiTest {
    @Test
    public void homeHasOnlyThreeCenteredTilesAndBrandedHeader() {
        verifyHome(1280, 720);
    }

    @Test
    @Config(qualifiers = "ru-w900dp-h400dp-land")
    public void compactHomeKeepsTilesInsideTheScreen() {
        verifyHome(900, 400);
    }

    @Test
    public void activityMountsAdaptedHomeWithoutStockHintsAndOpensSettings() {
        CarPlayHomeUi.install(CarPlayHomeScreen::new);
        VoyahCarPlayIntegration.install(mock(VoyahCarPlayIntegration.Host.class));
        var controller = Robolectric.buildActivity(DiPlayActivity.class).setup().visible();
        try {
            DiPlayActivity activity = controller.get();
            // Authentication errors are covered separately from the idle home contract.
            ReflectionHelpers.setField(activity, "setupError", null);
            ReflectionHelpers.callInstanceMethod(activity, "refreshStatus");
            View root = activity.getWindow().getDecorView();
            List<String> labels = new ArrayList<>();
            for (View view : descendants(root)) {
                if (view instanceof TextView && view.isShown()) {
                    String label = ((TextView) view).getText().toString();
                    if (!label.isEmpty()) labels.add(label);
                }
            }
            assertEquals(
                    Arrays.asList(
                            "CarPlay",
                            "VoyahTune",
                            "Беспроводное подключение",
                            "По Bluetooth и Wi-Fi",
                            "Подключение по USB",
                            "Подключите iPhone кабелем",
                            "Настройки",
                            "Экран и подключение",
                            "Выйти"),
                    labels);
            ((View) text(root, "Настройки").getParent()).performClick();
            assertEquals("settings", ReflectionHelpers.getField(activity, "page"));
            activity.getOnBackPressedDispatcher().onBackPressed();
            text(activity.getWindow().getDecorView(), "Выйти").performClick();
            assertTrue(activity.isFinishing());
        } finally {
            controller.pause().stop().destroy();
            VoyahCarPlayIntegration.install(null);
            ReflectionHelpers.setStaticField(CarPlayHomeUi.class, "factory", null);
        }
    }

    private void verifyHome(int widthDp, int heightDp) {
        var controller = Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            Activity activity = controller.get();
            CarPlayHomeUi.Actions actions = mock(CarPlayHomeUi.Actions.class);
            CarPlayHomeScreen screen = new CarPlayHomeScreen(activity, actions);
            screen.update("", false, true, "Лишняя подсказка точки доступа", null, true);
            screen.updateAutoConnect(true, true);
            View root = screen.getView();
            activity.setContentView(root);
            float density = activity.getResources().getDisplayMetrics().density;
            int width = Math.round(widthDp * density);
            int height = Math.round(heightDp * density);
            root.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);

            List<String> labels = new ArrayList<>();
            for (View view : descendants(root)) {
                if (view instanceof TextView && view.isShown()) {
                    String text = ((TextView) view).getText().toString();
                    if (!text.isEmpty()) labels.add(text);
                }
            }
            assertEquals(
                    Arrays.asList(
                            "CarPlay",
                            "VoyahTune",
                            "Беспроводное подключение",
                            "По Bluetooth и Wi-Fi",
                            "Подключение по USB",
                            "Подключите iPhone кабелем",
                            "Настройки",
                            "Экран и подключение",
                            "Выйти"),
                    labels);
            TextView brand = text(root, "VoyahTune");
            TextView title = text(root, "CarPlay");
            assertTrue(position(brand)[0] > position(title)[0]);
            assertTrue(position(brand)[1] < height / 4);
            List<View> tiles = new ArrayList<>();
            for (String label :
                    Arrays.asList("Беспроводное подключение", "Подключение по USB", "Настройки")) {
                tiles.add((View) text(root, label).getParent());
            }
            assertEquals(3, ((ViewGroup) tiles.get(0).getParent()).getChildCount());
            for (View tile : tiles) {
                int[] point = position(tile);
                assertTrue(tile.isClickable());
                assertTrue(point[0] >= 0 && point[0] + tile.getWidth() <= width);
                assertTrue(point[1] >= 0 && point[1] + tile.getHeight() <= height);
                assertEquals(height / 2f, point[1] + tile.getHeight() / 2f, 2f);
                assertEquals(tiles.get(0).getWidth(), tile.getWidth(), 1);
            }
            assertTrue(
                    position(tiles.get(1))[0]
                            >= position(tiles.get(0))[0] + tiles.get(0).getWidth());
            assertTrue(
                    position(tiles.get(2))[0]
                            >= position(tiles.get(1))[0] + tiles.get(1).getWidth());
            ImageButton back =
                    (ImageButton)
                            descendants(root).stream()
                                    .filter(
                                            view ->
                                                    "Назад"
                                                            .contentEquals(
                                                                    view.getContentDescription()
                                                                                    == null
                                                                            ? ""
                                                                            : view
                                                                                    .getContentDescription()))
                                    .findFirst()
                                    .orElseThrow();
            assertTrue(back.isShown());
            back.performClick();
            verify(actions).back();
            verify(actions, never()).disconnect();
            tiles.get(0).performClick();
            tiles.get(1).performClick();
            tiles.get(2).performClick();
            verify(actions).wireless();
            verify(actions).usb();
            verify(actions).settings();
            TextView exit = text(root, "Выйти");
            assertTrue(exit.isShown());
            assertTrue(position(exit)[1] >= position(tiles.get(0))[1] + tiles.get(0).getHeight());
            exit.performClick();
            assertTrue(activity.isFinishing());
            verify(actions, never()).disconnect();
            assertTrue(new CarPlayWindowPolicy().darkHostAppearance());
            assertEquals(R.drawable.carplay_home_logo, new CarPlayWindowPolicy().connectionIcon());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private static int[] position(View view) {
        int[] position = new int[2];
        view.getLocationOnScreen(position);
        return position;
    }

    private static TextView text(View root, String label) {
        return (TextView)
                descendants(root).stream()
                        .filter(
                                view ->
                                        view instanceof TextView
                                                && label.contentEquals(((TextView) view).getText()))
                        .findFirst()
                        .orElseThrow();
    }

    private static List<View> descendants(View root) {
        List<View> result = new ArrayList<>();
        result.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                result.addAll(descendants(group.getChildAt(index)));
            }
        }
        return result;
    }
}
