package ru.big.town.updater;

/** Presentation of daemon facts only. No timer-derived installation progress. */
final class UpdatePresentation {
    String eyebrow = "ОБНОВЛЕНИЯ", title = "Обновления VoyahTune";
    String subtitle = "Проверьте наличие нового релиза.", badge = "Установлено";
    String primary = "Проверить обновления", command = "check", progressLabel = "", progressNote = "";
    int nav, percent;
    boolean busy, meter, indeterminate, secondary, success;

    static UpdatePresentation from(String phase, boolean selected, String step,
            long bytes, long size, long completed, long steps) {
        UpdatePresentation p = new UpdatePresentation();
        p.busy = isBusy(phase);
        switch (phase) {
            case "checking":
                p.heading("ПРОВЕРКА ОБНОВЛЕНИЙ", "Ищем новый релиз", "Проверяем каталог релизов.", "Проверяем");
                p.waiting(0, "Проверка каталога", "Скачивание не начинается автоматически."); break;
            case "downloading":
                p.heading("СКАЧИВАНИЕ", "Скачиваем VoyahTune", "Можно вернуться в VoyahTune — скачивание продолжится в фоне.", "Скачивается");
                p.waiting(1, "Загрузка архива", "Можно пользоваться VoyahTune");
                if (size > 0 && bytes >= 0) {
                    p.indeterminate = false; p.percent = percentage(bytes, size);
                    p.progressNote = bytes / (1024 * 1024) + " из " + size / (1024 * 1024) + " МБ";
                }
                break;
            case "verifying":
                p.heading("ПРОВЕРКА АРХИВА", "Проверяем скачанный релиз", "Проверяем целостность и распаковываем архив.", "Проверяем");
                p.waiting(1, "Проверка целостности архива", step); break;
            case "verified":
                p.heading("ВСЁ ГОТОВО", "Можно устанавливать", "Релиз скачан и проверен. Установка начнётся после подтверждения.", "Архив проверен");
                p.nav = 2; p.primary = "Установить"; p.command = "apply"; break;
            case "preparing":
                p.heading("ПОДГОТОВКА", "Готовимся к установке", "Проверяем условия установки и доступ к системным файлам.", "Подготовка");
                p.waiting(2, "Подготовка к установке", step); break;
            case "applying":
                p.heading("УСТАНОВКА", "Устанавливаем обновление", "Обновляем файлы и приложения. Сохраняйте питание автомобиля.", "Установка");
                p.waiting(2, step, "Обновление выполняется автономно");
                if (steps > 0 && completed >= 0 && completed <= steps) {
                    p.indeterminate = false; p.percent = percentage(completed, steps);
                    p.progressNote = "Завершено " + completed + " из " + steps + " шагов";
                }
                break;
            case "reboot-pending":
                p.heading("ПЕРЕЗАГРУЗКА", "Перезапускаем систему", "Экран временно погаснет. Проверка продолжится после загрузки.", "Перезагрузка");
                p.waiting(2, "Ожидаем перезагрузку", "Система перезапускается"); break;
            case "validating":
                p.heading("ПРОВЕРКА ЗАПУСКА", "Проверяем работу VoyahTune", "Дожидаемся стабильной работы приложений и служб.", "Проверка запуска");
                p.waiting(2, "Проверка запуска VoyahTune", step); break;
            case "committed":
                p.heading("ОБНОВЛЕНИЕ ЗАВЕРШЕНО", "VoyahTune готов к работе", "Новая версия установлена. Проверка запуска успешно завершена.", "Установлен");
                p.nav = 3; p.primary = "В VoyahTune"; p.command = "close"; p.success = true; break;
            case "repair-required":
                p.heading("ОШИБКА УСТАНОВКИ", "Не удалось завершить обновление", "Установите релиз через USB с компьютера.", "Нужен USB");
                p.nav = 2; p.primary = "В VoyahTune"; p.command = "close"; break;
            default:
                if (selected && !"failed".equals(phase)) {
                    p.heading("НОВАЯ ВЕРСИЯ", "Доступен релиз", "Скачайте релиз, затем запустите установку в удобное время.", "Готов к скачиванию");
                    p.primary = "Скачать"; p.command = "download"; p.secondary = true;
                } else if ("failed".equals(phase)) {
                    p.heading("ОШИБКА ОБНОВЛЕНИЯ", "Не удалось подготовить релиз", "Подробная причина показана ниже.", "Ошибка");
                } else if (!step.isEmpty()) p.subtitle = step;
        }
        return p;
    }
    private void heading(String e, String t, String s, String b) { eyebrow=e; title=t; subtitle=s; badge=b; }
    private void waiting(int n, String label, String note) {
        nav=n; meter=true; indeterminate=true; progressLabel=label; progressNote=note; primary="Выполняется…"; command="";
    }
    static boolean isBusy(String phase) {
        switch (phase) {
            case "checking": case "downloading": case "verifying": case "preparing":
            case "applying": case "reboot-pending": case "validating": return true;
            default: return false;
        }
    }
    private static int percentage(long done, long total) { return (int)Math.min(100, Math.max(0, 100.0 * done / total)); }
}
