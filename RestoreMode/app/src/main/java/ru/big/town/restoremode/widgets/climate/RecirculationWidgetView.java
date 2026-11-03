package ru.big.town.restoremode.widgets.climate;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ru.big.town.common.RecirculationWidgetProtocol;
import ru.big.town.restoremode.R;

public final class RecirculationWidgetView extends LinearLayout {
    private final ImageView icon;
    private final TextView status;
    private int displayedMode = RecirculationWidgetProtocol.UNKNOWN;
    private boolean canCycle;

    public RecirculationWidgetView(Context context, Runnable cycle) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setBackgroundResource(R.drawable.card_dark_ripple);
        int padding = Math.round(6 * getResources().getDisplayMetrics().density);
        setPadding(padding, padding, padding, padding);
        TextView title = label("Рециркуляция", 13);
        addView(title, new LayoutParams(-1, -2));
        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(icon, new LayoutParams(-1, 0, 1));
        status = label("—", 14);
        addView(status, new LayoutParams(-1, -2));
        setFocusable(true);
        setOnClickListener(
                view -> {
                    if (canCycle) cycle.run();
                });
        update(new Bundle());
    }

    private TextView label(String text, int size) {
        TextView label = new TextView(getContext());
        label.setText(text);
        label.setTextSize(size);
        label.setTypeface(getResources().getFont(R.font.settings_arimo_regular));
        label.setTextColor(0xffeeeeee);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setIncludeFontPadding(false);
        return label;
    }

    public void update(Bundle state) {
        int mode =
                state.getInt(RecirculationWidgetProtocol.MODE, RecirculationWidgetProtocol.UNKNOWN);
        boolean known = RecirculationWidgetProtocol.validMode(mode);
        boolean pending = state.getBoolean(RecirculationWidgetProtocol.PENDING, false);
        canCycle =
                known && !pending && state.getBoolean(RecirculationWidgetProtocol.AVAILABLE, false);
        if (displayedMode != mode) {
            displayedMode = mode;
            int resource = imageResource(mode);
            if (resource == 0) {
                icon.setImageDrawable(null);
            } else {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 4;
                icon.setImageBitmap(
                        BitmapFactory.decodeResource(getResources(), resource, options));
            }
        }
        icon.setVisibility(known ? VISIBLE : INVISIBLE);
        icon.setAlpha(pending ? 0.6f : 1f);
        setActivated(pending);
        String label = RecirculationWidgetProtocol.label(mode);
        String message = state.getString(RecirculationWidgetProtocol.MESSAGE, "");
        boolean failed = known && !pending && !message.isEmpty();
        status.setText(label + (pending ? " · …" : failed ? " · !" : ""));
        status.setTextColor(failed ? 0xffffc166 : 0xffeeeeee);
        if (message.isEmpty()) {
            message = known ? "Нажмите: Вкл → Выкл → Авто" : "Нет данных рециркуляции";
        }
        setTooltipText(message);
        setContentDescription("Рециркуляция: " + label + ". " + message);
    }

    private static int imageResource(int mode) {
        switch (mode) {
            case RecirculationWidgetProtocol.ON:
                return R.drawable.recirculation_on;
            case RecirculationWidgetProtocol.OFF:
                return R.drawable.recirculation_off;
            case RecirculationWidgetProtocol.AUTO:
                return R.drawable.recirculation_auto;
            default:
                return 0;
        }
    }
}
