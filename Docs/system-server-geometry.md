# Геометрия окон в system_server

`Packaging/inject/vd_bypass.js` изменяет геометрию сторонних окон на физических
дисплеях 0 и 1 и применяет пользовательский DPI. Версия 3.22.0 добавляет проверку
готовности, целостный кэш и ограниченное восстановление состояния. Она сохраняет
док, fullscreen с отступом статус-бара, compact viewport и VD-сплит.

Целевая платформа — официальные SE и Sport+ 2025–2026, Android 11. Иные прошивки
требуют отдельного подтверждения. В имеющемся logcat есть SIGSEGV system_server;
его причина не установлена. Исправления Java/JS логики и локальные модели не
доказывают устранение нативного падения ART/Frida.

## Подготовка и состояние

Установка обязательных компонентов выполняется после preflight. При ошибке
отменяются timers, восстанавливаются отслеживаемые окна и снимаются методы/receivers.
Неполный rollback явно завершается ошибкой. Подтверждение связано с поколением
процесса и версией агента; [loader](parallel-hook-loader.md#готовность-vd-в-3220)
сохраняет совместимый публичный v1 статус.

| Состояние геометрии | Методы layout и config | Переход |
| --- | --- | --- |
| `pending` | Сняты | Initial/wake задержка 1 секунда; серия реальных быстрых переходов — 5 секунд |
| `active` | Установлены | Повторный SCREEN_ON сохраняет методы |
| `sleeping` | Сняты | SCREEN_OFF отменяет все pending actions; повторный OFF ничего не меняет |
| `disabled` | Сняты | Выключение freeform восстанавливает tracked requested размеры |
| `error` | Выполнен rollback, полнота указана в результате | Нет повторного attach в этом агенте |

Timers проверяют epoch. Серия WIN_RELOAD объединяется в одну загрузку через 50 мс,
затем один replay через 100 мс; работающие методы не переустанавливаются и задержка
wake не сокращается. Запросы traversal объединяются в одном timer. Изменение lift
принимается только при совпадении с OEM property и поддерживаемом значении 1/2.

## Кэш настроек и DPI

Settings читаются при подготовке, WIN_RELOAD и wake вне layout/config callbacks.
Сначала полностью строится новый policy, затем публикуется одним присваиванием.
Ошибка чтения или валидации сохраняет прежний снимок; ошибка первой загрузки
прерывает установку. Проверяются целые конечные bounds, положительные размеры,
compact bottom в пределах viewport, lift 1/2 и DPI 0 либо 100–640.

Полный DPI-index — `voyahtune_dpi_packages`, который Native записывает из
`appDpiJson`. Launch fallback и записи слотов дока также поддерживают index.
RestoreMode передаёт прежний полный снимок; формат broadcast не изменён.
Неизвестный пакет имеет DPI 0 без ленивого чтения Settings. Настройка ранее не
запускавшегося приложения доступна при его первом config callback.

WeakHashMap guard блокирует вложенное обновление той же Task и снимается в finally.
Другие задачи продолжают обновляться. Перед записью сравнивается requested density.
Копируется только текущая requested Configuration с заменой densityDpi; resolved
bounds, appBounds и dp размеры не переносятся. После физического reparent остаются
прежний depth guard и replay через обычный traversal.

## Геометрия и восстановление окон

Сохраняются исключения системных пакетов, ограничение физических дисплеев,
пропуск настоящего windowing mode 5 и один дополнительный computeFrame.
До мутаций проверяются все шесть WindowFrames, DisplayFrames.mStable, LayoutParams,
requested width/height и computeFrame. Неподдерживаемое окно сохраняет штатный layout.

DisplayFrames и LayoutParams восстанавливаются в finally; ошибка одного поля не
останавливает восстановление остальных. При неудачном custom layout возвращаются
также исходные WindowFrames и requested размеры. Если восстановление неполно,
агент сообщает partial failure.

Для main window fullscreen requested Surface размер сохраняется между layout
проходами. Учёт ведётся по WindowState в Java WeakHashMap, максимум 128 окон.
Значение содержит только четыре числа; сильной ссылки на окно в нём нет. При
достижении предела новое окно сохраняет штатную геометрию. Совпавшие hashCode не
объединяют записи. Новый app relayout обновляет сохранённый оригинал; восстановление
не затирает изменённый самим приложением requested размер.

WindowState.removeImmediately очищает запись при завершении окна. Отключение
fullscreen/freeform и ошибка агента восстанавливают размеры через существующий
WindowManagerInternal.requestTraversalFromDisplayManager. Дополнительный перехват
WMS.requestTraversal выполняет очистку только после Thread.holdsLock(mGlobalLock).
При отсутствии подходящего контекста агент сообщает неполное восстановление.
Обход Java heap и ручное взятие JNI monitor не используются.

Этот путь основан на [AOSP Android 11 WindowManagerService](https://github.com/aosp-mirror/platform_frameworks_base/blob/android11-release/services/core/java/com/android/server/wm/WindowManagerService.java):
LocalService берёт mGlobalLock перед requestTraversal. ABI проверяется агентом,
а удержание lock проверяется при исполнении. Поведение OEM Surface и Frida на машине
этой локальной проверкой не подтверждено. Два дополнительных перехвата очистки не
означают уменьшение общего числа переходов ART/Frida.

Значения viewport 1920×720 и compact bottom 560 сохраняют прежний контракт;
560 — принятое проектное значение, а не новое измерение датчика или дисплея.

## Локальные проверки

- `node Packaging/tests/test_vd_stability.js`: 25 поведенческих сценариев с моделью
  Java, управляемыми часами и ошибками установки/мутаций/восстановления.
- `python3 Packaging/tests/test_parallel_hook_loader.py`: 25 сценариев настоящего
  shell supervisor/worker с имитацией Android/Frida, включая поколение и отсутствие ready.
- `test_vd_hot_hooks_disabled.sh`, `test_vd_reparent_replay.sh`,
  `test_screen_lift_resize_restore.sh`, `test_hook_status.sh`,
  `test_saved_config_startup_wake.sh`: прежние контракты геометрии и статусов.
- Native JVM-тесты и assembleDebug: индекс DPI и APK собираются с прежним протоколом.

Поведенческие тесты включены в test_vd_hot_hooks_disabled.sh и проверку перед сборкой
payload/классического релиза. Сбор данных с устройства и испытания на автомобиле
не выполнялись и не являются условием локальной приёмки.
