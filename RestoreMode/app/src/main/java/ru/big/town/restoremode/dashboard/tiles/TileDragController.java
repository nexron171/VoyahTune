package ru.big.town.restoremode.dashboard.tiles;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.DragEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;

import java.util.ArrayList;
import java.util.List;

/** Previews the same packed layout that will be persisted after a drop. */
public final class TileDragController {
    public interface LayoutFactory {
        GridLayout.LayoutParams create(int column, int row, int width, int height);
    }

    public interface Commit {
        void move(int from, int before);
    }

    private static final long MOVE_MS = 200;
    private final GridLayout grid;
    private final LayoutFactory layoutFactory;
    private final Commit commit;
    private final List<Item> items = new ArrayList<>();
    private final List<Slot> slots = new ArrayList<>();
    private final SlotsDrawable overlay = new SlotsDrawable();
    private Item dragged;
    private Slot selected;
    private boolean settling;
    private int originalMinimumWidth;

    private static final class Item {
        final View view;
        final int position, width, height;

        public Item(View view, int position, int width, int height) {
            this.view = view;
            this.position = position;
            this.width = width;
            this.height = height;
        }
    }

    private static final class Slot {
        final int before;
        final List<Item> order;
        final List<RectF> bounds;
        final RectF target;

        public Slot(int before, List<Item> order, List<RectF> bounds, Item dragged) {
            this.before = before;
            this.order = order;
            this.bounds = bounds;
            target = bounds.get(order.indexOf(dragged));
        }
    }

    public TileDragController(GridLayout grid, LayoutFactory layoutFactory, Commit commit) {
        this.grid = grid;
        this.layoutFactory = layoutFactory;
        this.commit = commit;
        grid.setOnDragListener((v, event) -> handle(event, event.getX(), event.getY()));
    }

    public void add(View view, int position, int width, int height) {
        items.add(new Item(view, position, width, height));
        view.setOnDragListener(
                (v, event) -> handle(event, v.getX() + event.getX(), v.getY() + event.getY()));
    }

    public boolean start(View view) {
        if (dragged != null || settling) {
            return false;
        }
        for (Item item : items) {
            if (item.view == view) {
                dragged = item;
            }
        }
        if (dragged == null || view.getWidth() == 0) {
            dragged = null;
            return false;
        }
        originalMinimumWidth = grid.getMinimumWidth();
        buildSlots();
        if (!view.startDragAndDrop(null, new View.DragShadowBuilder(view), this, 0)) {
            cancel();
            return false;
        }
        view.setAlpha(0f);
        overlay.setBounds(0, 0, grid.getWidth(), grid.getHeight());
        grid.getOverlay().add(overlay);
        return true;
    }

    private void buildSlots() {
        List<Item> remaining = new ArrayList<>(items);
        remaining.remove(dragged);
        int maxRight = grid.getWidth();
        for (int index = 0; index <= remaining.size(); index++) {
            List<Item> order = new ArrayList<>(remaining);
            order.add(index, dragged);
            // Measure an off-screen GridLayout: previews include real margins, row remainders
            // and stretched first-row tiles, without reinflating live/embedded widgets.
            GridLayout preview = new GridLayout(grid.getContext());
            preview.setOrientation(grid.getOrientation());
            preview.setRowCount(grid.getRowCount());
            preview.setLayoutDirection(grid.getLayoutDirection());
            preview.setPadding(
                    grid.getPaddingLeft(),
                    grid.getPaddingTop(),
                    grid.getPaddingRight(),
                    grid.getPaddingBottom());
            preview.setMinimumWidth(grid.getWidth());
            List<boolean[]> occupied = new ArrayList<>();
            for (Item item : order) {
                int[] cell =
                        TileGridPacking.place(
                                occupied, grid.getRowCount(), item.width, item.height);
                preview.addView(
                        new View(grid.getContext()),
                        layoutFactory.create(cell[0], cell[1], item.width, item.height));
            }
            preview.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(grid.getHeight(), View.MeasureSpec.EXACTLY));
            preview.layout(0, 0, preview.getMeasuredWidth(), preview.getMeasuredHeight());
            List<RectF> bounds = new ArrayList<>();
            for (int i = 0; i < preview.getChildCount(); i++) {
                View child = preview.getChildAt(i);
                bounds.add(
                        new RectF(
                                child.getLeft(),
                                child.getTop(),
                                child.getRight(),
                                child.getBottom()));
            }
            int before =
                    index < remaining.size() ? remaining.get(index).position : Integer.MAX_VALUE;
            Slot slot = new Slot(before, order, bounds, dragged);
            // Different insertion orders may pack the dragged tile into the same physical slot.
            boolean duplicate = false;
            for (Slot existing : slots) {
                if (existing.target.equals(slot.target)) {
                    duplicate = true;
                }
            }
            if (!duplicate) {
                slots.add(slot);
            }
            maxRight = Math.max(maxRight, preview.getMeasuredWidth());
        }
        grid.setMinimumWidth(maxRight);
    }

    private boolean handle(DragEvent event, float x, float y) {
        if (event.getLocalState() != this || dragged == null) {
            return false;
        }
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                if (!settling) {
                    select(x, y);
                    scrollAtEdge(x);
                }
                break;
            case DragEvent.ACTION_DROP:
                if (settling) {
                    return true;
                }
                select(x, y);
                settle(x, y);
                break;
            case DragEvent.ACTION_DRAG_ENDED:
                if (!settling) {
                    cancel();
                }
                break;
        }
        return true;
    }

    private void select(float x, float y) {
        Slot nearest = null;
        float distance = Float.MAX_VALUE;
        for (Slot slot : slots) {
            float dx = x - slot.target.centerX(), dy = y - slot.target.centerY();
            float candidate = dx * dx + dy * dy;
            if (candidate < distance) {
                nearest = slot;
                distance = candidate;
            }
        }
        if (nearest == null || nearest == selected) {
            return;
        }
        selected = nearest;
        for (int i = 0; i < selected.order.size(); i++) {
            Item item = selected.order.get(i);
            if (item != dragged) {
                animateTo(item.view, selected.bounds.get(i), MOVE_MS);
            }
        }
        overlay.invalidateSelf();
    }

    private void animateTo(View view, RectF bounds, long duration) {
        view.setPivotX(0);
        view.setPivotY(0);
        view.animate()
                .translationX(bounds.left - view.getLeft())
                .translationY(bounds.top - view.getTop())
                .scaleX(bounds.width() / view.getWidth())
                .scaleY(bounds.height() / view.getHeight())
                .setInterpolator(new DecelerateInterpolator())
                .setDuration(duration)
                .start();
    }

    private void settle(float x, float y) {
        if (selected == null) {
            cancel();
            return;
        }
        settling = true;
        View view = dragged.view;
        view.setTranslationX(x - view.getLeft() - view.getWidth() / 2f);
        view.setTranslationY(y - view.getTop() - view.getHeight() / 2f);
        view.setAlpha(1f);
        view.animate()
                .withEndAction(
                        () -> {
                            int from = dragged.position, before = selected.before;
                            cancel();
                            commit.move(from, before);
                        });
        animateTo(view, selected.target, 260);
    }

    private void scrollAtEdge(float x) {
        if (!(grid.getParent() instanceof HorizontalScrollView)) {
            return;
        }
        HorizontalScrollView scroll = (HorizontalScrollView) grid.getParent();
        float localX = x - scroll.getScrollX();
        float edge = 48 * grid.getResources().getDisplayMetrics().density;
        int step = Math.round(edge / 3);
        if (localX < edge) {
            scroll.smoothScrollBy(-step, 0);
        } else if (localX > scroll.getWidth() - edge) {
            scroll.smoothScrollBy(step, 0);
        }
    }

    public void cancel() {
        grid.getOverlay().remove(overlay);
        if (dragged != null) {
            grid.setMinimumWidth(originalMinimumWidth);
        }
        for (Item item : items) {
            item.view.animate().withEndAction(null).cancel();
            item.view.setAlpha(1f);
            item.view.setTranslationX(0);
            item.view.setTranslationY(0);
            item.view.setScaleX(1);
            item.view.setScaleY(1);
        }
        dragged = null;
        selected = null;
        settling = false;
        slots.clear();
    }

    private final class SlotsDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override
        public void draw(Canvas canvas) {
            float density = grid.getResources().getDisplayMetrics().density;
            for (Slot slot : slots) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(slot == selected ? 0x5549BFFF : 0x1449BFFF);
                canvas.drawRoundRect(slot.target, 12 * density, 12 * density, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth((slot == selected ? 3 : 1) * density);
                paint.setColor(slot == selected ? 0xFF75D5FF : 0x8875D5FF);
                canvas.drawRoundRect(slot.target, 12 * density, 12 * density, paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter filter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
