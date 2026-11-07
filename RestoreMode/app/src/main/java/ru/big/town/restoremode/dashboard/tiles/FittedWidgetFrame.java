package ru.big.town.restoremode.dashboard.tiles;

import android.view.View;
import android.view.ViewGroup;

/** Keeps the original widget composition and scales it uniformly into its allocated cells. */
public final class FittedWidgetFrame extends ViewGroup {
    private final View content;
    private final int contentHeight;

    public FittedWidgetFrame(View content, int contentHeight) {
        super(content.getContext());
        this.content = content;
        this.contentHeight = Math.max(1, contentHeight);
        content.setPivotX(0);
        content.setPivotY(0);
        content.setOnLongClickListener(view -> performLongClick());
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, this.contentHeight));
    }

    public View content() {
        return content;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        content.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        content.layout(0, 0, getWidth(), contentHeight);
        float scale = Math.min(1f, (float) getHeight() / contentHeight);
        content.setScaleX(scale);
        content.setScaleY(scale);
        content.setTranslationX((getWidth() - getWidth() * scale) / 2);
        content.setTranslationY((getHeight() - contentHeight * scale) / 2);
    }
}
