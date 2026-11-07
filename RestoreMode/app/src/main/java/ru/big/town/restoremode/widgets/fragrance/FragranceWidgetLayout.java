package ru.big.town.restoremode.widgets.fragrance;

public enum FragranceWidgetLayout {
    MINI(1, 1, 129, 115),
    STRIP(2, 1, 266, 115),
    CARD(2, 2, 266, 238),
    PANEL_4X3(4, 3, 540, 361),
    PANEL_4X4(4, 4, 540, 484),
    PANEL_5X3(5, 3, 677, 361),
    PANEL_5X4(5, 4, 677, 484);

    public static final String ID = "fragranceWidget";
    public static final String VISIBLE = "showFragranceWidget";
    public final int columns;
    public final int rows;
    final int width;
    final int height;

    FragranceWidgetLayout(int columns, int rows, int width, int height) {
        this.columns = columns;
        this.rows = rows;
        this.width = width;
        this.height = height;
    }

    public static FragranceWidgetLayout fit(int columns, int rows) {
        if (columns <= 1) return MINI;
        if (columns >= 5) return rows >= 4 ? PANEL_5X4 : PANEL_5X3;
        if (columns >= 4) return rows >= 4 ? PANEL_4X4 : PANEL_4X3;
        return rows <= 1 ? STRIP : CARD;
    }

    boolean expanded() {
        return columns >= 4;
    }

    boolean panoramic() {
        return this == STRIP || expanded();
    }
}
