package ru.big.town.restoremode.dashboard.tiles;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Size;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.GridLayout;

import ru.big.town.restoremode.R;

import java.util.HashMap;
import java.util.Map;

public final class DashboardEditGrid extends GridLayout {
    public interface Actions {
        void editingChanged(boolean editing);

        void move(View tile);

        void remove(TileOrderStore.Tile tile, View view);

        void resize(TileOrderStore.Tile tile, int columns, int rows);
    }

    public interface Sizes {
        boolean allows(int columns, int rows);
    }

    private static final class Item {
        final TileOrderStore.Tile tile;
        final int columns, rows;
        final Sizes sizes;

        Item(TileOrderStore.Tile tile, int columns, int rows, Sizes sizes) {
            this.tile = tile;
            this.columns = columns;
            this.rows = rows;
            this.sizes = sizes;
        }

        boolean removable() {
            return !TileOrderStore.Tile.TYPE_WIDGET.equals(tile.type)
                    || !"cardSettings".equals(tile.id);
        }
    }

    private enum Gesture {
        BODY,
        REMOVE,
        RESIZE,
        WINDOW_CONTROL
    }

    private final Map<View, Item> items = new HashMap<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path corner = new Path();
    private final Rect original = new Rect();
    private final Rect viewport = new Rect();
    private final Runnable extendAtEdge = this::extendAtEdge;
    private float pointerX, pointerY;
    private int extraColumns, extraRows;
    private boolean extendRight, extendDown;
    private final Runnable hold = this::longPress;
    private final float density = getResources().getDisplayMetrics().density;
    private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
    private Actions actions;
    private boolean editing;
    private View pressed;
    private Gesture gesture;
    private float downX, downY;
    private boolean moved;
    private int columnPitch, rowPitch;
    private Size preview;

    public DashboardEditGrid(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setActions(Actions actions) {
        this.actions = actions;
    }

    public void beginLayout(int columnPitch, int rowPitch) {
        resetGesture();
        items.clear();
        this.columnPitch = Math.max(1, columnPitch);
        this.rowPitch = Math.max(1, rowPitch);
    }

    public void register(View view, TileOrderStore.Tile tile, int columns, int rows, Sizes sizes) {
        view.setTransitionName(tile.type + ":" + tile.id);
        items.put(view, new Item(tile, columns, rows, sizes));
        view.setOnLongClickListener(
                ignored -> {
                    if (editing) {
                        actions.move(view);
                    } else {
                        setEditing(true);
                    }
                    return true;
                });
    }

    public boolean isEditing() {
        return editing;
    }

    public void setEditing(boolean enabled) {
        if (editing == enabled) {
            return;
        }
        if (enabled && pressed != null) {
            long time = SystemClock.uptimeMillis();
            MotionEvent cancel =
                    MotionEvent.obtain(time, time, MotionEvent.ACTION_CANCEL, downX, downY, 0);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
        }
        resetGesture();
        editing = enabled;
        actions.editingChanged(enabled);
        invalidate();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            beginGesture(event);
        }
        if (action == MotionEvent.ACTION_MOVE && pressed != null) {
            if (Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop) {
                moved = true;
                removeCallbacks(hold);
            }
            if (gesture == Gesture.RESIZE) {
                previewSize(event);
            }
        }
        boolean consume = editing && gesture != Gesture.WINDOW_CONTROL;
        if (action == MotionEvent.ACTION_UP && editing && pressed != null) {
            View view = pressed;
            Item item = items.get(view);
            if (gesture == Gesture.REMOVE
                    && !moved
                    && controlAt(view, event.getX(), event.getY()) == Gesture.REMOVE) {
                resetGesture();
                actions.remove(item.tile, view);
            } else if (gesture == Gesture.RESIZE) {
                previewSize(event);
                Size size = preview;
                resetGesture();
                if (size.getWidth() != item.columns || size.getHeight() != item.rows) {
                    actions.resize(item.tile, size.getWidth(), size.getHeight());
                }
            }
        }
        if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            resetGesture();
        }
        return consume || super.dispatchTouchEvent(event) || pressed != null;
    }

    private void beginGesture(MotionEvent event) {
        resetGesture();
        downX = event.getX();
        downY = event.getY();
        pressed = tileAt(downX, downY);
        if (pressed == null) return;
        gesture = editing ? controlAt(pressed, downX, downY) : Gesture.BODY;
        if (gesture == Gesture.BODY) {
            View handler = touchTarget(pressed, downX - pressed.getX(), downY - pressed.getY());
            if (editing && isWindowControl(handler)) {
                gesture = Gesture.WINDOW_CONTROL;
            } else if (editing
                    || handler == null
                    || handler == pressed
                    || !handler.isLongClickable()) {
                postDelayed(hold, ViewConfiguration.getLongPressTimeout());
            }
        } else if (gesture == Gesture.RESIZE) {
            Item item = items.get(pressed);
            preview = new Size(item.columns, item.rows);
            original.set(
                    pressed.getLeft(), pressed.getTop(), pressed.getRight(), pressed.getBottom());
            pressed.setTranslationZ(8 * density);
            getParent().requestDisallowInterceptTouchEvent(true);
        }
    }

    private void longPress() {
        if (pressed == null || moved) {
            return;
        }
        pressed.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (editing) {
            actions.move(pressed);
        } else {
            setEditing(true);
        }
    }

    // Match the topmost interactive descendant, including scrolled lists inside a tile.
    private View touchTarget(View view, float x, float y) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = group.getChildCount() - 1; index >= 0; index--) {
                View child = group.getChildAt(index);
                float childX = x + group.getScrollX() - child.getX();
                float childY = y + group.getScrollY() - child.getY();
                if (child.getVisibility() == VISIBLE
                        && childX >= 0
                        && childY >= 0
                        && childX < child.getWidth()
                        && childY < child.getHeight()) {
                    View target = touchTarget(child, childX, childY);
                    if (target != null) return target;
                }
            }
        }
        return view.isClickable() || view.isLongClickable() ? view : null;
    }

    private boolean isWindowControl(View view) {
        while (view != null && view != this) {
            if (view.getId() == R.id.appWidgetOverlayControls) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }

    private View tileAt(float x, float y) {
        for (int index = getChildCount() - 1; index >= 0; index--) {
            View child = getChildAt(index);
            if (child.getVisibility() == VISIBLE
                    && items.containsKey(child)
                    && x >= child.getX()
                    && y >= child.getY()
                    && x < child.getX() + child.getWidth()
                    && y < child.getY() + child.getHeight()) {
                return child;
            }
        }
        return null;
    }

    private Gesture controlAt(View view, float x, float y) {
        float localX = x - view.getX(), localY = y - view.getY();
        float hit = Math.min(48 * density, Math.min(view.getWidth(), view.getHeight()) / 2f);
        Item item = items.get(view);
        if (item.removable() && localX >= 0 && localY >= 0 && localX < hit && localY < hit) {
            return Gesture.REMOVE;
        }
        if (item.sizes != null
                && localX >= view.getWidth() - hit
                && localY >= view.getHeight() - hit) {
            return Gesture.RESIZE;
        }
        return Gesture.BODY;
    }

    private void previewSize(MotionEvent event) {
        pointerX = event.getX();
        pointerY = event.getY();
        if (getLocalVisibleRect(viewport)) {
            boolean right =
                    pointerX > downX + density * 2 && pointerX >= viewport.right - 12 * density;
            boolean bottom =
                    pointerY > downY + density * 2 && pointerY >= viewport.bottom - 12 * density;
            if (right != extendRight || bottom != extendDown) {
                extendRight = right;
                extendDown = bottom;
                removeCallbacks(extendAtEdge);
                if (right || bottom) postDelayed(extendAtEdge, 450);
            }
        }
        updatePreview();
    }

    // Holding at the viewport edge lets a tile in the last visible row or column grow.
    private void extendAtEdge() {
        if (preview == null) return;
        Sizes sizes = items.get(pressed).sizes;
        boolean horizontal =
                extendRight
                        && extraColumns < 12
                        && preview.getWidth()
                                < nearestSize(sizes, 12, preview.getHeight(), preview).getWidth();
        boolean vertical =
                extendDown
                        && extraRows < 5
                        && preview.getHeight()
                                < nearestSize(sizes, preview.getWidth(), 5, preview).getHeight();
        if (!horizontal && !vertical) return;
        if (horizontal) extraColumns++;
        if (vertical) extraRows++;
        updatePreview();
        postDelayed(extendAtEdge, 280);
    }

    private void updatePreview() {
        Item item = items.get(pressed);
        float columns = item.columns + (pointerX - downX) / columnPitch + extraColumns;
        float rows = item.rows + (pointerY - downY) / rowPitch + extraRows;
        Size size = nearestSize(item.sizes, columns, rows, preview);
        if (size.equals(preview)) {
            return;
        }
        preview = size;
        layoutPressed(
                original.width() + (size.getWidth() - item.columns) * columnPitch,
                original.height() + (size.getHeight() - item.rows) * rowPitch);
        invalidate();
    }

    static Size nearestSize(Sizes sizes, float columns, float rows, Size current) {
        int nearestWidth = current.getWidth();
        int nearestHeight = current.getHeight();
        float distance = squaredDistance(columns - nearestWidth, rows - nearestHeight);
        for (int width = 1; width <= 12; width++) {
            for (int height = 1; height <= 5; height++) {
                float candidate = squaredDistance(columns - width, rows - height);
                if (candidate < distance && sizes.allows(width, height)) {
                    nearestWidth = width;
                    nearestHeight = height;
                    distance = candidate;
                }
            }
        }
        return new Size(nearestWidth, nearestHeight);
    }

    private static float squaredDistance(float x, float y) {
        return x * x + y * y;
    }

    private void layoutPressed(int width, int height) {
        width = Math.max(1, width);
        height = Math.max(1, height);
        pressed.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        int left = original.left, top = original.top;
        if (!viewport.isEmpty() && width > original.width()) {
            left = Math.max(viewport.left, Math.min(left, viewport.right - width));
        }
        if (!viewport.isEmpty() && height > original.height()) {
            top = Math.max(viewport.top, Math.min(top, viewport.bottom - height));
        }
        pressed.layout(left, top, left + width, top + height);
    }

    private void resetGesture() {
        removeCallbacks(hold);
        removeCallbacks(extendAtEdge);
        extendRight = extendDown = false;
        extraColumns = extraRows = 0;
        viewport.setEmpty();
        if (pressed != null && preview != null) {
            layoutPressed(original.width(), original.height());
            pressed.setTranslationZ(0);
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
        }
        pressed = null;
        gesture = null;
        preview = null;
        moved = false;
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!editing) return;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (preview != null && child == pressed) continue;
            canvas.save();
            if (preview != null) {
                canvas.clipOutRect(
                        pressed.getLeft(),
                        pressed.getTop(),
                        pressed.getRight(),
                        pressed.getBottom());
            }
            drawControls(canvas, child);
            canvas.restore();
        }
        if (preview != null) drawControls(canvas, pressed);
    }

    private void drawControls(Canvas canvas, View child) {
        Item item = items.get(child);
        if (item == null || child.getVisibility() != VISIBLE || child.getAlpha() == 0) return;
        canvas.save();
        canvas.translate(child.getLeft(), child.getTop());
        canvas.concat(child.getMatrix());
        float width = child.getWidth(), height = child.getHeight();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x99000000);
        canvas.save();
        if (TileOrderStore.Tile.TYPE_APP_WIDGET.equals(item.tile.type)) {
            View controls = child.findViewById(R.id.appWidgetOverlayControls);
            if (controls != null && controls.getVisibility() == VISIBLE) {
                excludeButtons(canvas, controls, controls.getLeft(), controls.getTop());
            }
        }
        canvas.drawRoundRect(0, 0, width, height, 12 * density, 12 * density, paint);
        canvas.restore();
        if (item.removable()) {
            float center = 21 * density;
            paint.setColor(0xff422329);
            canvas.drawCircle(center, center, 16 * density, paint);
            paint.setColor(0xffff625f);
            paint.setStrokeWidth(3 * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            float radius = 6 * density;
            canvas.drawLine(
                    center - radius, center - radius, center + radius, center + radius, paint);
            canvas.drawLine(
                    center - radius, center + radius, center + radius, center - radius, paint);
        }
        if (item.sizes != null) {
            float right = width - 7 * density, bottom = height - 7 * density;
            corner.reset();
            corner.moveTo(right - 29 * density, bottom);
            corner.lineTo(right - 10 * density, bottom);
            corner.quadTo(right, bottom, right, bottom - 10 * density);
            corner.lineTo(right, bottom - 29 * density);
            paint.setColor(0xffffffff);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(6 * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawPath(corner, paint);
        }
        if (child == pressed && preview != null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xffffffff);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(19 * density);
            canvas.drawText(
                    preview.getWidth() + " × " + preview.getHeight(),
                    width / 2,
                    height / 2 - (paint.ascent() + paint.descent()) / 2,
                    paint);
        }
        canvas.restore();
    }

    private void excludeButtons(Canvas canvas, View view, int left, int top) {
        if (view.getVisibility() != VISIBLE) return;
        if (view.isClickable()) {
            canvas.clipOutRect(left, top, left + view.getWidth(), top + view.getHeight());
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View child = group.getChildAt(index);
                excludeButtons(canvas, child, left + child.getLeft(), top + child.getTop());
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        resetGesture();
        super.onDetachedFromWindow();
    }
}
