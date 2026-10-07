package ru.big.town.common;

/**
 * Signature-protected RestoreMode/Native Messenger contract диспетчера задач («Диспетчер задач»).
 *
 * <p>Список запущенных приложений и закрытие задач выполняет только Native: он priv-app, у него есть
 * REAL_GET_TASKS и FORCE_STOP_PACKAGES. RestoreMode шлёт запросы в SetModesService и получает ответ в
 * собственный Messenger через {@code msg.replyTo}. Ids 105..109 свободны: не пересекаются с
 * SetModesService (1..38), {@link SuspensionWidgetProtocol} (90..93), {@code ScenarioProtocol} (94) и
 * {@link EnergyWidgetProtocol} (100..103).</p>
 */
public final class TaskManagerProtocol {
    private TaskManagerProtocol() {}

    /** RestoreMode → Native: запросить список запущенных сторонних задач. Ответ — {@link #LIST}. */
    public static final int REQUEST = 105;
    /** Native → RestoreMode: параллельные списки {@link #PACKAGES}, {@link #LABELS} и {@link #WIDGETS}. */
    public static final int LIST = 106;
    /** RestoreMode → Native: закрыть одно приложение, extra {@link #PACKAGE}. Ответ — {@link #LIST}. */
    public static final int CLOSE = 107;
    /** RestoreMode → Native: вывести задачу приложения на передний план, extra {@link #PACKAGE}. */
    public static final int SWITCH = 108;
    /**
     * RestoreMode → Native: зафиксировать/снять фиксацию приложения. Extras {@link #PACKAGE} и
     * {@link #PINNED}. Зафиксированные приложения не закрывает «Закрыть все». Ответ — {@link #LIST}.
     */
    public static final int PIN = 109;
    /**
     * Закрыть все сторонние приложения. Совпадает с {@code SetModesService.MSG_CLOSE_ALL}; значение
     * продублировано здесь, чтобы диспетчер не зависел от константы экрана настроек.
     */
    public static final int CLOSE_ALL = 27;

    /** ArrayList&lt;String&gt; — пакеты запущенных сторонних приложений в порядке задач. */
    public static final String PACKAGES = "packages";
    /** ArrayList&lt;String&gt; — подписи приложений, индекс в индекс с {@link #PACKAGES}. */
    public static final String LABELS = "labels";
    /** boolean[] — признак фиксации, индекс в индекс с {@link #PACKAGES}. */
    public static final String PINNED = "pinned";
    /**
     * boolean[] — приложение запущено внутри виджета, индекс в индекс с {@link #PACKAGES}. Тап по
     * такой карточке открывает приложение на физическом экране, а не поднимает его задачу на
     * VirtualDisplay виджета.
     */
    public static final String WIDGETS = "widgets";
    /** String — пакет для {@link #CLOSE} и {@link #SWITCH}. */
    public static final String PACKAGE = "pkg";
}
