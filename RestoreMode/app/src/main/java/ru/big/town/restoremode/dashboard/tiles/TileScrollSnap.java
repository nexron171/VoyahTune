package ru.big.town.restoremode.dashboard.tiles;

/**
 * Арифметика автовыравнивания горизонтальной сетки главного экрана после остановки прокрутки.
 *
 * <p>Класс не зависит от Android: расчёт цели используется и в активности, и в JVM-тестах.
 * Решётка колонок начинается от начала сетки ({@code gridX = 0}), поэтому выровненная по колонке
 * позиция повторяет вид крайнего левого положения покоя: отступ сетки, затем целая колонка.
 */
public final class TileScrollSnap {

    /** Варианты выравнивания, доступные в настройке «Сетка главного экрана». */
    public enum Mode {
        /** Выравнивание выключено. */
        OFF,
        /** До ближайшей границы колонки — в кадре всегда ровные столбцы. */
        COLUMN,
        /** До целой страницы из {@code columns} колонок; хватает лёгкого движения. */
        PAGE,
        /** Левая граница плитки встаёт вплотную к левому краю кадра. */
        TILE
    }

    /** Порядок режимов, ключей и подписей совпадает: он же задаёт список в настройках. */
    public static final Mode[] MODES = {Mode.OFF, Mode.COLUMN, Mode.PAGE, Mode.TILE};

    public static final String[] KEYS = {"off", "column", "page", "tile"};

    public static final String[] LABELS = {
        "Выключено",
        "По границе колонки",
        "По целой странице",
        "По левому краю плитки"
    };

    /** Режим по умолчанию, если настройка ещё не задана или повреждена. */
    public static final Mode DEFAULT_MODE = Mode.TILE;

    private TileScrollSnap() {}

    /** Режим по значению настройки; неизвестное значение даёт {@link #DEFAULT_MODE}. */
    public static Mode fromKey(String key) {
        if (key != null) {
            for (int i = 0; i < KEYS.length; i++) {
                if (KEYS[i].equals(key)) {
                    return MODES[i];
                }
            }
        }
        return DEFAULT_MODE;
    }

    /** Значение настройки для режима. */
    public static String key(Mode mode) {
        int index = indexOf(mode);
        return KEYS[index];
    }

    /** Индекс режима в списке настроек; неизвестный режим даёт индекс {@link #DEFAULT_MODE}. */
    public static int indexOf(Mode mode) {
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i] == mode) {
                return i;
            }
        }
        return indexOf(DEFAULT_MODE);
    }

    /**
     * Целевая прокрутка для режимов {@link Mode#OFF}, {@link Mode#COLUMN} и {@link Mode#PAGE}.
     * Режим {@link Mode#TILE} требует геометрии плиток и считается через {@link #tileLeftEdge}.
     *
     * @param scrollX текущая прокрутка
     * @param anchor прокрутка в начале жеста (−1, если неизвестна)
     * @param pitch шаг одной колонки в пикселях
     * @param columns число видимых колонок в кадре (ширина страницы для {@link Mode#PAGE})
     * @param maxScroll максимально допустимая прокрутка (0 — сетка не прокручивается)
     * @param mode выбранный режим
     * @return прокрутка после выравнивания; всегда в {@code [0, maxScroll]}
     */
    public static int target(
            int scrollX, int anchor, int pitch, int columns, int maxScroll, Mode mode) {
        if (mode == null || mode == Mode.OFF || pitch <= 0 || maxScroll <= 0) {
            return clamp(scrollX, maxScroll);
        }
        switch (mode) {
            case COLUMN:
                return clamp(nearest(scrollX, pitch), maxScroll);
            case PAGE:
                return pageTarget(scrollX, anchor, pitch, columns, maxScroll);
            default:
                // TILE выравнивается по реальным левым краям карточек — см. tileLeftEdge().
                return clamp(scrollX, maxScroll);
        }
    }

    /**
     * Страница по лёгкому движению: достаточно уйти с текущей страницы, чтобы перейти на
     * соседнюю в сторону движения. Дальний пролист доводится до ближайшей страницы.
     *
     * @param anchor прокрутка в начале жеста (−1, если неизвестна)
     */
    public static int pageTarget(int scrollX, int anchor, int pitch, int columns, int maxScroll) {
        int step = pitch * Math.max(1, columns);
        int candidate = nearest(scrollX, step);
        if (anchor < 0) {
            return clamp(candidate, maxScroll);
        }
        int base = (anchor / step) * step;
        if (scrollX > anchor && candidate <= base) {
            candidate = base + step;
        } else if (scrollX < anchor && candidate >= base) {
            candidate = base - step;
        }
        return clamp(candidate, maxScroll);
    }

    /**
     * Выравнивание по левому краю плитки: из левых границ карточек (и начала сетки) берётся
     * ближайшая слева к текущей прокрутке. Так край плитки встаёт вплотную к левому краю кадра,
     * а плитки шире одной колонки учитываются своей реальной шириной.
     *
     * @param scrollX текущая прокрутка
     * @param lefts левые границы видимых плиток в координатах сетки (включая отступ карточки)
     * @param maxScroll максимально допустимая прокрутка
     */
    public static int tileLeftEdge(int scrollX, int[] lefts, int maxScroll) {
        // 0 — начало сетки: крайнее левое положение покоя не сдвигает отступ сетки.
        int edge = 0;
        if (lefts != null) {
            for (int left : lefts) {
                if (left <= scrollX && left > edge) {
                    edge = left;
                }
            }
        }
        return clamp(edge, maxScroll);
    }

    private static int nearest(int value, int step) {
        return (int) (Math.round((double) value / step) * step);
    }

    private static int clamp(int value, int maxScroll) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, Math.max(0, maxScroll));
    }
}
