package ru.big.town.restoremode.settings.sections.vehicle;

final class TurnSoundProgress {
    private TurnSoundProgress() {}

    static String text(String stage) {
        switch (stage) {
            case "connecting":
                return "Подключение к приборке и передача данных…";
            case "checking_backup":
                return "Проверка наличия резервной копии…";
            case "backup_di":
                return "Сохранение оригинала «тик»…";
            case "backup_da":
                return "Сохранение оригинала «так»…";
            case "verifying_backup":
                return "Проверка целостности обоих оригиналов…";
            case "preparing_audio":
                return "Подготовка звука и выбранной громкости…";
            case "preparing_write":
                return "Подготовка приборки к записи…";
            case "write_di":
                return "Запись выбранного звука «тик»…";
            case "write_da":
                return "Запись выбранного звука «так»…";
            case "restore_di":
                return "Восстановление оригинального звука «тик»…";
            case "restore_da":
                return "Восстановление оригинального звука «так»…";
            case "verifying_di":
                return "Проверка записанного звука «тик»…";
            case "verifying_da":
                return "Проверка записанного звука «так»…";
            case "saving_state":
                return "Сохранение состояния функции…";
            case "finishing":
                return "Завершение операции на приборке…";
            case "reading_state":
                return "Проверка состояния функции…";
            default:
                return "Выполнение операции на приборке…";
        }
    }
}
