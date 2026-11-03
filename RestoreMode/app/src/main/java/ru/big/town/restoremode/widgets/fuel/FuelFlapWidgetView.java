package ru.big.town.restoremode.widgets.fuel;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ru.big.town.common.FuelFlapWidgetProtocol;
import ru.big.town.restoremode.R;

public final class FuelFlapWidgetView extends LinearLayout {
    private final TextView volume;
    private final TextView status;
    private boolean canOpen;
    private AlertDialog confirmation;

    public FuelFlapWidgetView(Context context, Runnable open) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setBackgroundResource(R.drawable.card_dark_ripple);
        int padding = Math.round(8 * getResources().getDisplayMetrics().density);
        setPadding(padding, padding, padding, padding);
        TextView title = label("Бензобак", 18, 0xffffffff);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        volume = label("", 17, 0xffeeeeee);
        status = label("", 12, 0xffbbbbbb);
        setFocusable(true);
        setOnClickListener(
                view -> {
                    if (canOpen && confirmation == null) confirmOpening(open);
                });
        update(new Bundle());
    }

    private void confirmOpening(Runnable open) {
        confirmation =
                new MaterialAlertDialogBuilder(getContext(), R.style.DarkDialog)
                        .setTitle("Открыть лючок бензобака?")
                        .setMessage(volume.getText() + ". Открытие доступно только в P.")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton(
                                "Открыть",
                                (dialog, which) -> {
                                    if (canOpen && confirmation != null && confirmation.isShowing())
                                        open.run();
                                })
                        .create();
        confirmation.setOnDismissListener(dialog -> confirmation = null);
        confirmation.show();
    }

    public void dismissConfirmation() {
        if (confirmation != null) confirmation.dismiss();
    }

    @Override
    protected void onDetachedFromWindow() {
        dismissConfirmation();
        super.onDetachedFromWindow();
    }

    private TextView label(String text, int size, int color) {
        TextView label = new TextView(getContext());
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setIncludeFontPadding(false);
        addView(label, new LayoutParams(-1, -2));
        return label;
    }

    public void update(Bundle state) {
        int liters = state.getInt(FuelFlapWidgetProtocol.REFILL_LITERS, -1);
        volume.setText(
                liters >= 0 && liters <= 200
                        ? "Можно заправить ≈ " + liters + " л"
                        : "Нет данных о топливе");
        status.setText(state.getString(FuelFlapWidgetProtocol.MESSAGE, "Нажмите для открытия"));
        canOpen = state.getBoolean(FuelFlapWidgetProtocol.CAN_OPEN, false);
        if (!canOpen) dismissConfirmation();
        setContentDescription("Бензобак. " + volume.getText() + ". " + status.getText());
        setTooltipText(status.getText());
    }
}
