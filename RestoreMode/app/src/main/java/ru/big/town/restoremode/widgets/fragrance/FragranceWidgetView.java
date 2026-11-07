package ru.big.town.restoremode.widgets.fragrance;

import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import ru.big.town.common.FragranceWidgetProtocol;
import ru.big.town.common.FragranceWidgetProtocol.Snapshot;

public final class FragranceWidgetView extends FrameLayout {
    interface Command {
        void send(int command, int value);
    }

    private static final String[] INTENSITIES = {"Низкая", "Средняя", "Высокая"};
    private static final String[] DURATIONS = {"30 мин", "60 мин", "Безлимит"};
    private static final int[] TIMER_VALUES = {1, 2, 0};
    // VehicleAir positions its mist in this panorama coordinate space.
    private static final int SCENE_WIDTH = 1920;
    private static final int SCENE_HEIGHT = 720;
    private final FragranceWidgetLayout layout;
    private final Command command;
    private final ImageView background;
    private final View artworkShade;
    private final TextView title;
    private final FragranceTileGraphics graphics;
    private final TextView[] scents = new TextView[3];
    private final TextView[] levels = new TextView[3];
    private final TextView[] timers = new TextView[3];
    private TextView status;
    private TextView power;
    private View levelTrack;
    private View timerTrack;
    private Snapshot state = Snapshot.UNKNOWN;
    private boolean pending;
    private String message = "";
    private boolean visible;
    private boolean treeVisible;
    private final Rect visibleArea = new Rect();
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::updateVisibility;
    private boolean resumed;
    private Runnable visibilityChange;

    FragranceWidgetView(Context context, FragranceWidgetLayout layout, Command command) {
        super(context);
        this.layout = layout;
        this.command = command;
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xff11151d);
        shape.setCornerRadius(12 * getResources().getDisplayMetrics().density);
        setBackground(shape);
        setClipToOutline(true);

        background =
                image(
                        0,
                        0,
                        layout.expanded() ? SCENE_WIDTH : layout.width,
                        layout.expanded() ? SCENE_HEIGHT : layout.height);
        ImageView mist =
                layout.expanded()
                        ? image(1265, 60, 397, 424)
                        : layout.panoramic()
                                ? image(
                                        layout.width * .659f,
                                        layout.height / 12f,
                                        layout.width * .2068f,
                                        layout.height * .589f)
                                : image(
                                        layout.width * .3597f,
                                        0,
                                        layout.width * .528f,
                                        layout.height * .7257f);
        artworkShade = layout.expanded() ? addArtworkShade() : null;
        graphics = new FragranceTileGraphics(context, layout, mist, this::render);
        switch (layout) {
            case MINI:
                title = label(12, 10, 84, 44, 14);
                status = label(12, 82, 108, 26, 12);
                setOnClickListener(v -> toggle());
                break;
            case STRIP:
                title = label(13, 14, 143, 39, 16);
                addScents(4, 62, 49, 49);
                break;
            case CARD:
                title = label(20, 20, 147, 56, 20);
                addScents(3, 150, 82, 89);
                setOnClickListener(v -> openVehicleScreen());
                break;
            case PANEL_4X3:
            case PANEL_4X4:
            case PANEL_5X3:
            case PANEL_5X4:
                int controlsWidth = Math.round(layout.width * .57f);
                title = label(24, 24, controlsWidth - 63, 48, 21);
                power = button(controlsWidth - 30, 24, 39, 39, 12, this::toggle);
                addScents(24, 88, 64, 74);
                levelTrack =
                        addSegments(
                                Math.round(layout.height * .49f),
                                "Интенсивность",
                                levels,
                                INTENSITIES,
                                FragranceWidgetProtocol.LEVEL,
                                new int[] {1, 2, 3});
                timerTrack =
                        addSegments(
                                Math.round(layout.height * .73f),
                                "Таймер",
                                timers,
                                DURATIONS,
                                FragranceWidgetProtocol.TIMER,
                                TIMER_VALUES);
                break;
            default:
                throw new AssertionError(layout);
        }
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        render();
    }

    private View addArtworkShade() {
        View shade = new View(getContext());
        shade.setBackground(
                new GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[] {0xdd000000, 0x99000000, 0x00000000}));
        shade.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(shade, new Placement(0, 0, 0, 0, 0));
        return shade;
    }

    private ImageView image(float x, float y, float width, float height) {
        ImageView image = new ImageView(getContext());
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(image, new Placement(x, y, width, height, 0));
        return image;
    }

    private TextView label(int x, int y, int width, int height, int size) {
        TextView text = new TextView(getContext());
        text.setTextColor(0xffffffff);
        text.setIncludeFontPadding(false);
        text.setMaxLines(2);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setGravity(Gravity.TOP | Gravity.START);
        addView(text, new Placement(x, y, width, height, size));
        return text;
    }

    private TextView button(int x, int y, int width, int height, int size, Runnable action) {
        TextView button = label(x, y, width, height, size);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setOnClickListener(
                v -> {
                    if (v.isEnabled()) action.run();
                });
        return button;
    }

    private void addScents(int x, int y, int size, int spacing) {
        for (int i = 0; i < scents.length; i++) {
            int value = i + 1;
            scents[i] =
                    button(
                            x + i * spacing,
                            y,
                            size,
                            size,
                            11,
                            () -> {
                                if (state.matches(FragranceWidgetProtocol.SCENT, value)) toggle();
                                else send(FragranceWidgetProtocol.SCENT, value);
                            });
        }
    }

    private View addSegments(
            int y, String name, TextView[] buttons, String[] labels, int command, int[] values) {
        int availableHeight = layout.height - 24 - Math.round(layout.height * .49f);
        int segmentHeight =
                Math.min(36, (availableHeight - 2 * 27 - 12) / 2);
        int segmentWidth = segmentHeight * 2;
        y = Math.min(y, layout.height - 24 - 27 - segmentHeight);
        TextView caption = label(24, y, segmentWidth * 3, 22, 14);
        caption.setTextColor(0xffcacaca);
        caption.setText(name);
        View track = new View(getContext());
        addView(track, new Placement(24, y + 27, segmentWidth * 3, segmentHeight, 0));
        for (int i = 0; i < buttons.length; i++) {
            int value = values[i];
            TextView item =
                    button(
                            24 + segmentWidth * i,
                            y + 27,
                            segmentWidth,
                            segmentHeight,
                            14,
                            () -> send(command, value));
            item.setText(labels[i]);
            item.setContentDescription(labels[i]);
            buttons[i] = item;
        }
        return track;
    }

    private void send(int key, int value) {
        if (!pending && state.allows(key, value)) command.send(key, value);
    }

    private void toggle() {
        send(FragranceWidgetProtocol.POWER, state.on() ? 1 : 2);
    }

    private void openVehicleScreen() {
        Display display = getDisplay();
        if (display == null) {
            WindowManager window = getContext().getSystemService(WindowManager.class);
            if (window == null) return;
            display = window.getDefaultDisplay();
        }
        Intent intent =
                new Intent("com.qinggan.app.vehicle.SHOW_FRAGRANCE")
                        .setPackage(FragranceArtwork.VEHICLE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions options =
                ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId());
        try {
            getContext().startActivity(intent, options.toBundle());
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(
                            getContext(),
                            "Штатный экран ароматизатора недоступен",
                            Toast.LENGTH_SHORT)
                    .show();
        }
    }

    void update(Snapshot value, boolean busy, String detail) {
        if (state.power == value.power
                && state.scent == value.scent
                && state.level == value.level
                && state.timer == value.timer
                && pending == busy
                && message.equals(detail)) return;
        state = value;
        pending = busy;
        message = detail;
        render();
    }

    private void render() {
        FragranceArtwork art = graphics.artwork();
        String[] names = art == null ? FragranceArtwork.DEFAULT_NAMES : art.names;
        String name = state.available() ? names[state.scent - 1] : "Ароматизатор";
        String feedback = message;
        if (feedback.isEmpty() && !state.available()) feedback = "Нет данных";
        if (feedback.isEmpty() && pending) feedback = "Переключение…";
        String stateText = state.on() ? "Включено" : "Выключено";
        String powerLabel = state.on() ? "Выключить ароматизатор" : "Включить ароматизатор";
        boolean available = state.available() && !pending;
        title.setText(status == null && !feedback.isEmpty() ? feedback : name);
        background.setImageDrawable(
                state.available()
                        ? drawable(FragranceArtwork.background(layout, state.scent, state.on()))
                        : null);
        if (status != null) {
            status.setText(feedback.isEmpty() ? stateText : feedback);
            setContentDescription(
                    name + ". " + status.getText() + (available ? ". " + powerLabel : ""));
        }
        if (layout == FragranceWidgetLayout.CARD) {
            setContentDescription(name + ". Открыть штатный экран ароматизатора");
        }
        if (layout != FragranceWidgetLayout.MINI) {
            for (int i = 0; i < scents.length; i++) {
                boolean selected = state.on() && state.scent == i + 1;
                decorateButton(
                        scents[i],
                        names[i],
                        FragranceArtwork.icon(i + 1, selected),
                        selected,
                        available);
            }
        }
        if (power != null) {
            decorateButton(
                    power,
                    powerLabel,
                    state.on() ? "fragrance_ic_switch_on" : "fragrance_ic_switch_off",
                    state.on(),
                    available);
            if (power.getBackground() == null) power.setText(state.on() ? "Выкл" : "Вкл");
            renderSegments(levels, levelTrack, state.level, FragranceWidgetProtocol.LEVEL, null);
            renderSegments(
                    timers, timerTrack, state.timer, FragranceWidgetProtocol.TIMER, TIMER_VALUES);
        }
        graphics.animate(state.available() && state.on());
    }

    private void decorateButton(
            TextView button, String label, String image, boolean selected, boolean enabled) {
        Drawable icon = drawable(image);
        button.setBackground(icon);
        button.setText(icon == null ? label : "");
        button.setTextColor(icon == null && selected ? 0xff84ceff : 0xffffffff);
        button.setContentDescription(label);
        button.setSelected(selected);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .55f);
    }

    private void renderSegments(
            TextView[] buttons, View track, int actual, int command, int[] values) {
        boolean enabled = !pending && state.allows(command, actual);
        track.setBackground(
                drawable(
                        state.on()
                                ? "fragrance_newcontrol_bgenable"
                                : "fragrance_newcontrol_bgdisable"));
        for (int i = 0; i < buttons.length; i++) {
            TextView item = buttons[i];
            boolean selected = actual == (values == null ? i + 1 : values[i]);
            Drawable tab =
                    selected
                            ? drawable(
                                    state.on()
                                            ? "fragrance_newtab_sel"
                                            : "fragrance_newtab_disable")
                            : null;
            item.setBackground(tab);
            if (selected && tab == null) item.setBackgroundColor(0xff365777);
            item.setSelected(selected);
            item.setEnabled(enabled);
            item.setAlpha(enabled ? 1f : .4f);
        }
    }

    private Drawable drawable(String name) {
        FragranceArtwork art = graphics.artwork();
        return art == null ? null : art.drawable(getResources(), name);
    }

    void setResumed(boolean resumed) {
        this.resumed = resumed;
        graphics.setVisible(resumed && visible, getWidth(), getHeight());
    }

    boolean isVisibleTile() {
        return visible;
    }

    void onVisibilityChange(Runnable listener) {
        visibilityChange = listener;
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        treeVisible = isVisible;
        updateVisibility();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        treeVisible = isShown() && getWindowVisibility() == VISIBLE;
        getViewTreeObserver().addOnScrollChangedListener(scrollListener);
        updateVisibility();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateVisibility();
    }

    private void updateVisibility() {
        changeVisibility(treeVisible && isAttachedToWindow() && getLocalVisibleRect(visibleArea));
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
        treeVisible = false;
        changeVisibility(false);
        super.onDetachedFromWindow();
    }

    private void changeVisibility(boolean value) {
        if (visible == value) return;
        visible = value;
        graphics.setVisible(resumed && value, getWidth(), getHeight());
        if (visibilityChange != null) visibilityChange.run();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        graphics.setVisible(resumed && visible, width, height);
    }

    private static final class Placement extends LayoutParams {
        final float x, y, baseWidth, baseHeight;
        final int fontSize;

        Placement(float x, float y, float width, float height, int fontSize) {
            super(0, 0, Gravity.TOP | Gravity.LEFT);
            this.x = x;
            this.y = y;
            baseWidth = width;
            baseHeight = height;
            this.fontSize = fontSize;
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        float scaleX = width / (float) layout.width;
        float scaleY = height / (float) layout.height;
        float controlScale = Math.min(scaleX, scaleY);
        float artworkScale = Math.max(width / (float) SCENE_WIDTH, height / (float) SCENE_HEIGHT);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            Placement box = (Placement) child.getLayoutParams();
            if (child == artworkShade) {
                box.width = Math.round(width / 2f);
                box.height = height;
            } else if (layout.expanded() && child instanceof ImageView) {
                box.width = Math.round(box.baseWidth * artworkScale);
                box.height = Math.round(box.baseHeight * artworkScale);
                box.leftMargin =
                        width
                                - Math.round(SCENE_WIDTH * artworkScale)
                                + Math.round(box.x * artworkScale);
                box.topMargin =
                        height
                                - Math.round(SCENE_HEIGHT * artworkScale)
                                + Math.round(box.y * artworkScale);
            } else {
                float horizontalScale = layout.expanded() ? controlScale : scaleX;
                float verticalScale = layout.expanded() ? controlScale : scaleY;
                box.width = Math.round(box.baseWidth * horizontalScale);
                box.height = Math.round(box.baseHeight * verticalScale);
                box.leftMargin = Math.round(box.x * horizontalScale);
                box.topMargin = Math.round(box.y * verticalScale);
            }
            if (box.fontSize > 0) {
                ((TextView) child)
                        .setTextSize(TypedValue.COMPLEX_UNIT_PX, box.fontSize * controlScale);
            }
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
