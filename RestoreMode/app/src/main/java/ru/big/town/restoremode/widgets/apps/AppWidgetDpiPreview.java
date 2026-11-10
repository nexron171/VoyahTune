package ru.big.town.restoremode.widgets.apps;

import android.os.Handler;
import android.view.View;
import android.widget.TextView;

import java.util.function.IntConsumer;

public final class AppWidgetDpiPreview implements AutoCloseable {
    private static final long DEBOUNCE_MILLIS = 3000;
    private final Handler handler;
    private final TextView preview;
    private final IntConsumer apply;
    private final Runnable commit = this::commit;
    private int current;
    private int selected;

    public AppWidgetDpiPreview(Handler handler, TextView preview, int current, IntConsumer apply) {
        this.handler = handler;
        this.preview = preview;
        this.current = AppWidgetStore.normalizeDpi(current);
        this.selected = this.current;
        this.apply = apply;
    }

    public void change(int direction) {
        int index = 0;
        for (int candidate = 0; candidate < AppWidgetStore.DPI_VALUES.length; candidate++) {
            if (AppWidgetStore.DPI_VALUES[candidate] == selected) {
                index = candidate;
                break;
            }
        }
        int next = Math.max(0, Math.min(AppWidgetStore.DPI_VALUES.length - 1, index + direction));
        selected = AppWidgetStore.DPI_VALUES[next];
        preview.setText(selected == 0 ? "DPI: Авто" : "DPI: " + selected);
        preview.setVisibility(View.VISIBLE);
        preview.bringToFront();
        handler.removeCallbacks(commit);
        handler.postDelayed(commit, DEBOUNCE_MILLIS);
    }

    private void commit() {
        preview.setVisibility(View.GONE);
        if (selected == current) return;
        current = selected;
        apply.accept(selected);
    }

    @Override
    public void close() {
        handler.removeCallbacks(commit);
        preview.setVisibility(View.GONE);
    }
}
