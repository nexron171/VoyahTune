package ru.big.town.restoremode.widgets.energy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowCanvas;

import ru.big.town.common.EnergyWidgetProtocol;
import ru.big.town.common.EnergyWidgetSettings;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class VehicleWidgetViewTest {
    private Context context;
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test
    public void factoryRendersEachCardAtEverySupportedSize() {
        Class<?>[] classes = {
            EnergyWidgetView.class,
            ConsumptionWidgetView.class,
            TripWidgetView.class,
            TirePressureWidgetView.class,
            OdometerWidgetView.class
        };
        String[] titles = {
            "Заряд и топливо", "Расход", "Текущая поездка", "Давление в шинах", "Общий пробег"
        };
        for (int index = 0; index < VehicleWidgets.IDS.length; index++) {
            String widgetId = VehicleWidgets.IDS[index];
            for (int columns = EnergyWidgetLayout.minWidth(widgetId);
                    columns <= EnergyWidgetLayout.maxWidth(widgetId);
                    columns++) {
                for (int rows = EnergyWidgetLayout.minHeight(widgetId, columns);
                        rows <= EnergyWidgetLayout.maxHeight(widgetId, columns);
                        rows++) {
                    VehicleWidgetView widget =
                            VehicleWidgets.create(context, widgetId, "white", columns, rows);
                    assertEquals(classes[index], widget.getClass());
                    widget.update(telemetry());
                    List<String> text = render(widget);
                    assertTrue(widgetId + " " + columns + "×" + rows, text.contains(titles[index]));
                    for (int other = 0; other < titles.length; other++) {
                        if (other != index) {
                            assertFalse(text.contains(titles[other]));
                        }
                    }
                    widget.update(null);
                    assertTrue(render(widget).contains(titles[index]));
                }
            }
        }
    }

    @Test
    public void historyWindowRemainsClickableAndPersists() {
        EnergyWidgetView widget = new EnergyWidgetView(context, 8, 4);
        widget.update(telemetry());
        render(widget);
        EnergyChartLayout chart = energyChart(widget);
        tap(
                widget,
                chart.buttonLeft + chart.buttonWidth * 1.5f,
                (chart.buttonTop + chart.buttonBottom) / 2);
        assertEquals(
                EnergyWidgetSettings.WINDOWS[1],
                preferences.getInt(EnergyWidgetSettings.WINDOW_KEY, -1));
        render(new EnergyWidgetView(context, 8, 4));
        assertEquals(
                EnergyWidgetSettings.WINDOWS[1],
                preferences.getInt(EnergyWidgetSettings.WINDOW_KEY, -1));
    }

    @Test
    public void selectedHistoryPointSurvivesAnUpdateAndClearsWhenRemoved() {
        preferences.edit().putInt(EnergyWidgetSettings.WINDOW_KEY, 50).commit();
        EnergyWidgetView widget = new EnergyWidgetView(context, 8, 4);
        Bundle data = telemetry();
        widget.update(data);
        render(widget);
        EnergyChartLayout chart = energyChart(widget);
        tap(widget, (chart.left + chart.right) / 2, (chart.top + chart.bottom) / 2);
        assertTrue(render(widget).contains("47,2"));

        data.putFloatArray(EnergyWidgetProtocol.HISTORY_X, new float[] {75, 100, 101});
        data.putFloatArray(EnergyWidgetProtocol.HISTORY_EV, new float[] {47.2f, 44, 43});
        data.putDouble(EnergyWidgetProtocol.RECORDED_KM, 101);
        widget.update(data);
        assertTrue(render(widget).contains("47,2"));

        data.putFloatArray(EnergyWidgetProtocol.HISTORY_X, new float[] {100, 101});
        data.putFloatArray(EnergyWidgetProtocol.HISTORY_EV, new float[] {44, 43});
        widget.update(data);
        assertTrue(render(widget).contains("73,0"));
        assertFalse(render(widget).contains("47,2"));
    }

    @Test
    public void tripOwnsItsTimerAndReservesSpaceForHistory() {
        TripWidgetView widget = new TripWidgetView(context, 8, 2);
        widget.update(telemetry());
        widget.timer(3_723_000);
        assertTrue(render(widget).contains("01:02:03"));
        float fullHeight = widget.contentHeight;
        widget.showTripHistory(true);
        assertTrue(render(widget).contains("01:02:03"));
        assertEquals(fullHeight - 72, widget.contentHeight, .01f);
        widget.showTripHistory(false);
        render(widget);
        assertEquals(fullHeight, widget.contentHeight, .01f);
    }

    @Test
    public void staleTelemetryHidesTireReadingsButKeepsTripTotals() {
        Bundle data = telemetry();
        TirePressureWidgetView tires = new TirePressureWidgetView(context, "white", 4, 4);
        TripWidgetView trip = new TripWidgetView(context, 8, 2);
        tires.update(data);
        assertTrue(render(tires).contains("2,6"));
        assertTrue(tires.getContentDescription().toString().contains("2,6"));
        data.putBoolean(EnergyWidgetProtocol.CONNECTED, false);
        tires.update(data);
        trip.update(data);
        assertFalse(render(tires).contains("2,6"));
        assertTrue(render(tires).contains("Нет связи"));
        assertTrue(render(trip).contains("42,5"));
    }

    private EnergyChartLayout energyChart(VehicleWidgetView widget) {
        float fontScale =
                context.getResources().getDisplayMetrics().scaledDensity
                        / context.getResources().getDisplayMetrics().density;
        return new EnergyChartLayout(widget.contentWidth, widget.contentHeight, false, fontScale);
    }

    private void tap(VehicleWidgetView widget, float x, float y) {
        float density = context.getResources().getDisplayMetrics().density;
        MotionEvent event =
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x * density, y * density, 0);
        widget.onTouchEvent(event);
        widget.performClick();
        event.recycle();
        widget.cancelLongPress();
    }

    private List<String> render(VehicleWidgetView widget) {
        float density = context.getResources().getDisplayMetrics().density;
        widget.layout(
                0,
                0,
                Math.round(EnergyWidgetLayout.pixelsWide(widget.columns) * density),
                Math.round(widget.rows * 127 * density));
        Canvas canvas = new Canvas();
        widget.draw(canvas);
        ShadowCanvas drawing = Shadows.shadowOf(canvas);
        List<String> text = new ArrayList<>();
        for (int index = 0; index < drawing.getTextHistoryCount(); index++) {
            text.add(drawing.getDrawnTextEvent(index).text);
        }
        return text;
    }

    private Bundle telemetry() {
        Bundle data = new Bundle();
        data.putInt(EnergyWidgetProtocol.SCHEMA, EnergyWidgetProtocol.VERSION);
        data.putBoolean(EnergyWidgetProtocol.CONNECTED, true);
        data.putLong(EnergyWidgetProtocol.UPDATED, SystemClock.elapsedRealtime());
        data.putFloatArray(EnergyWidgetProtocol.LEVELS, new float[] {73, 60});
        data.putFloatArray(EnergyWidgetProtocol.ODOMETER, new float[] {15278});
        data.putFloatArray(EnergyWidgetProtocol.TIRES, new float[] {2.6f, 2.7f, 2.8f, 2.9f});
        data.putFloatArray(EnergyWidgetProtocol.TRIP, new float[] {42.5f, 18.2f, 6.1f});
        data.putFloatArray(EnergyWidgetProtocol.TRIP_OBSERVED_KM, new float[] {42.5f, 42.5f});
        data.putDouble(EnergyWidgetProtocol.RECORDED_KM, 100);
        data.putFloatArray(EnergyWidgetProtocol.HISTORY_X, new float[] {50, 75, 100});
        data.putFloatArray(EnergyWidgetProtocol.HISTORY_EV, new float[] {55, 47.2f, 44});
        data.putFloatArray(EnergyWidgetProtocol.HISTORY_FUEL, new float[] {70, 65, 60});
        data.putDoubleArray(EnergyWidgetProtocol.CONSUMPTION_START, new double[] {99.8, 99.9});
        data.putDoubleArray(EnergyWidgetProtocol.CONSUMPTION_END, new double[] {99.9, 100});
        data.putFloatArray(EnergyWidgetProtocol.CONSUMPTION_EV, new float[] {.02f, .03f});
        data.putFloatArray(EnergyWidgetProtocol.CONSUMPTION_FUEL, new float[] {.01f, .02f});
        return data;
    }
}
