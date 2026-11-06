package ru.big.town.restoremode.settings.ui.preview;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.Size;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.dashboard.tiles.TileSizeStore;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

import java.util.ArrayList;
import java.util.List;

public final class WidgetSizeGrid extends LinearLayout {
    public interface AllowedSize {
        boolean contains(int columns, int rows);
    }

    public interface OnSizeSelected {
        void select(int columns, int rows);
    }

    private static final int COLUMNS = 12;
    private static final int ROWS = 5;
    private final Button sizeButton;
    private final Grid grid;
    private final String widgetId;
    private final int defaultColumns;
    private final int defaultRows;
    private int selectedColumns;
    private int selectedRows;
    private AllowedSize allowed;
    private OnSizeSelected listener;

    public WidgetSizeGrid(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        TypedArray attributes = context.obtainStyledAttributes(attrs, R.styleable.WidgetSizeGrid);
        widgetId = attributes.getString(R.styleable.WidgetSizeGrid_settingsTileKey);
        defaultColumns = attributes.getInt(R.styleable.WidgetSizeGrid_settingsTileWidth, 1);
        defaultRows = attributes.getInt(R.styleable.WidgetSizeGrid_settingsTileHeight, 1);
        attributes.recycle();

        sizeButton = new Button(context);
        sizeButton.setAllCaps(false);
        sizeButton.setTag("settings.field");
        sizeButton.setOnClickListener(view -> showChoices());
        addView(sizeButton, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        TextView hint = new TextView(context);
        hint.setText(R.string.widget_size_drag_hint);
        hint.setTextColor(0xffb7c6d8);
        hint.setPadding(0, SettingsDesign.dp(this, 6), 0, SettingsDesign.dp(this, 6));
        addView(hint);
        grid = new Grid(context);
        addView(grid, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    public void usePreferences(SharedPreferences preferences) {
        if (widgetId == null) {
            return;
        }
        int[] size = TileSizeStore.dimensions(preferences, widgetId, defaultColumns, defaultRows);
        bind(
                size[0],
                size[1],
                (columns, rows) -> TileSizeStore.supportsSize(widgetId, columns, rows),
                (columns, rows) -> TileSizeStore.setSize(preferences, widgetId, columns, rows));
    }

    public void bind(int columns, int rows, AllowedSize allowed, OnSizeSelected listener) {
        this.allowed = allowed;
        this.listener = listener;
        grid.clearDrag();
        display(columns, rows);
        selectedColumns = columns;
        selectedRows = rows;
    }

    private String label(int columns, int rows) {
        return columns + " × " + rows;
    }

    private void display(int columns, int rows) {
        sizeButton.setText(
                getResources().getString(R.string.widget_size_selection, label(columns, rows)));
        grid.invalidate();
    }

    private void select(int columns, int rows) {
        if (allowed != null
                && allowed.contains(columns, rows)
                && (columns != selectedColumns || rows != selectedRows)) {
            selectedColumns = columns;
            selectedRows = rows;
            display(columns, rows);
            listener.select(columns, rows);
        }
    }

    private void showChoices() {
        if (allowed == null) {
            return;
        }
        List<String> labels = new ArrayList<>();
        List<Size> cells = new ArrayList<>();
        int selected = -1;
        for (int columns = 1; columns <= COLUMNS; columns++) {
            for (int rows = 1; rows <= ROWS; rows++) {
                if (allowed.contains(columns, rows)) {
                    if (columns == selectedColumns && rows == selectedRows) {
                        selected = labels.size();
                    }
                    labels.add(label(columns, rows));
                    cells.add(new Size(columns, rows));
                }
            }
        }
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.widget_size_choices)
                .setSingleChoiceItems(
                        labels.toArray(new String[0]),
                        selected,
                        (dialog, index) -> {
                            Size size = cells.get(index);
                            select(size.getWidth(), size.getHeight());
                            dialog.dismiss();
                        })
                .show();
    }

    private final class Grid extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float gap = SettingsDesign.dp(this, 3);
        private int pointer = MotionEvent.INVALID_POINTER_ID;
        private int dragColumns;
        private int dragRows;

        Grid(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            setMeasuredDimension(width, resolveSize(Math.round(width * 720f / 1920f), heightSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (allowed == null) {
                return;
            }
            float stepX = (getWidth() + gap) / COLUMNS;
            float stepY = (getHeight() + gap) / ROWS;
            int columns = dragColumns == 0 ? selectedColumns : dragColumns;
            int rows = dragRows == 0 ? selectedRows : dragRows;
            for (int row = 1; row <= ROWS; row++) {
                for (int column = 1; column <= COLUMNS; column++) {
                    paint.setColor(allowed.contains(column, row) ? 0xffa8dfae : 0xff3c485b);
                    canvas.drawRoundRect(
                            (column - 1) * stepX,
                            (row - 1) * stepY,
                            column * stepX - gap,
                            row * stepY - gap,
                            2,
                            2,
                            paint);
                }
            }
            // Outline the footprint so valid endpoints inside it remain visible.
            float stroke = SettingsDesign.dp(this, 2);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(0xffa1c5fa);
            canvas.drawRoundRect(
                    stroke / 2,
                    stroke / 2,
                    columns * stepX - gap - stroke / 2,
                    rows * stepY - gap - stroke / 2,
                    3,
                    3,
                    paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void preview(float x, float y) {
            int columns = 1 + (int) (x * COLUMNS / (getWidth() + gap));
            int rows = 1 + (int) (y * ROWS / (getHeight() + gap));
            boolean valid =
                    x >= 0
                            && y >= 0
                            && x < getWidth()
                            && y < getHeight()
                            && allowed.contains(columns, rows);
            dragColumns = valid ? columns : 0;
            dragRows = valid ? rows : 0;
            display(valid ? columns : selectedColumns, valid ? rows : selectedRows);
        }

        private void clearDrag() {
            if (pointer != MotionEvent.INVALID_POINTER_ID && getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            pointer = MotionEvent.INVALID_POINTER_ID;
            dragColumns = dragRows = 0;
        }

        @Override
        protected void onDetachedFromWindow() {
            clearDrag();
            display(selectedColumns, selectedRows);
            super.onDetachedFromWindow();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (allowed == null) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pointer = event.getPointerId(0);
                    getParent().requestDisallowInterceptTouchEvent(true);
                    preview(event.getX(), event.getY());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int index = event.findPointerIndex(pointer);
                    if (index >= 0) {
                        preview(event.getX(index), event.getY(index));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (pointer == event.getPointerId(0)) {
                        preview(event.getX(), event.getY());
                        int columns = dragColumns;
                        int rows = dragRows;
                        clearDrag();
                        if (columns > 0) {
                            select(columns, rows);
                            performClick();
                        }
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    if (pointer != event.getPointerId(event.getActionIndex())) {
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    break;
                default:
                    return true;
            }
            clearDrag();
            display(selectedColumns, selectedRows);
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
