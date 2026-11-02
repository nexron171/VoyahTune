package ru.big.town.restoremode.widgets.climate;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import ru.big.town.common.CabinTemperatureProtocol;
import ru.big.town.restoremode.R;

public final class CabinTemperatureWidgetView extends LinearLayout {
    private final TextView value;

    public CabinTemperatureWidgetView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setBackgroundResource(R.drawable.card_dark_ripple);
        int padding = Math.round(6 * getResources().getDisplayMetrics().density);
        setPadding(padding, padding, padding, padding);
        TextView title = new TextView(context);
        title.setText("В салоне");
        title.setTextColor(0xffeeeeee);
        title.setTextSize(14);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        addView(title, new LayoutParams(-1, -2));
        value = new TextView(context);
        value.setTextColor(0xffffffff);
        value.setTextSize(26);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        value.setGravity(Gravity.CENTER);
        value.setSingleLine(true);
        value.setIncludeFontPadding(false);
        addView(value, new LayoutParams(-1, -2));
        setTooltipText("Температура салона · экспериментальный датчик");
        update(new Bundle());
    }

    public void update(Bundle state) {
        double temperature = state.getDouble(CabinTemperatureProtocol.VALUE, Double.NaN);
        boolean available =
                state.getBoolean(CabinTemperatureProtocol.AVAILABLE, false)
                        && Double.isFinite(temperature);
        value.setText(available ? CabinTemperatureProtocol.format(temperature) + "°" : "—");
        setContentDescription(
                available
                        ? "Температура в салоне: "
                                + CabinTemperatureProtocol.format(temperature)
                                + " °C"
                        : "Температура в салоне: нет данных");
    }
}
