package ru.big.town.restoremode.widgets.suspension;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

import ru.big.town.common.SuspensionWidgetProtocol;
import ru.big.town.restoremode.appearance.AppearanceSettings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SuspensionWidgetAppearanceTest {
    @Test
    public void carRemainsOpaqueWithAndWithoutHeightDataOnTransparentCards() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("DrivePreferences", 0).edit()
                .putBoolean(AppearanceSettings.TRANSPARENCY_ENABLED, true)
                .putInt(AppearanceSettings.TRANSPARENCY_PERCENT, 80).commit();
        for (int height : new int[] {-1, 5}) {
            SuspensionWidgetView widget = new SuspensionWidgetView(context, selected -> {});
            Bundle state = new Bundle();
            state.putInt(SuspensionWidgetProtocol.HEIGHT, height);
            widget.update(state);
            Bitmap car = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888);
            car.eraseColor(Color.RED);
            ReflectionHelpers.setField(widget, "carBitmap", car);
            widget.setBackground(null);
            widget.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
            widget.layout(0, 0, 600, 400);
            Bitmap rendered = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888);
            widget.draw(new Canvas(rendered));
            assertEquals(Color.RED, rendered.getPixel(300, 180));
        }
    }
}
