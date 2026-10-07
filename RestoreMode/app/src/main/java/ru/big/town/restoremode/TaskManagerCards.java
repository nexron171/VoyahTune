package ru.big.town.restoremode;

import java.util.List;

/**
 * Чистая раскладка карточек «Диспетчера задач»: сколько слотов видно и нужна ли карточка
 * «Закрыть все приложения». Без Android-зависимостей, проверяется JVM-тестом
 * {@code TaskManagerCardsTest}.
 */
final class TaskManagerCards {
    /** Видимых слотов в ряду: 5 карточек приложений + карточка «Закрыть все приложения». */
    static final int VISIBLE_SLOTS = 6;
    /** Слотов, доступных приложениям (остальные — карточка «Закрыть все»). */
    static final int APP_SLOTS = VISIBLE_SLOTS - 1;

    private TaskManagerCards() {}

    /**
     * Нужна ли карточка «Закрыть все»: без запущенных приложений показывается только текст пустого
     * состояния, закрывать нечего.
     */
    static boolean hasCloseAllCard(List<String> packages) {
        return packages != null && !packages.isEmpty();
    }

    /** Ширина карточки: ровно {@link #VISIBLE_SLOTS} колонок в доступной ширине с промежутками. */
    static int cardWidth(int availableWidth, int gapPx) {
        int usable = availableWidth - gapPx * (VISIBLE_SLOTS - 1);
        return usable <= 0 ? 0 : usable / VISIBLE_SLOTS;
    }
}
