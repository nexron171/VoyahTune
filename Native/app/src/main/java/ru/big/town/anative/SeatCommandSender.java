package ru.big.town.anative;

/** One invocation, no readback, retries, session cancellation or compensating writes. */
final class SeatCommandSender {
    interface Transport {
        void prepare(String field, long deadlineMs) throws Exception;
        int send(String field, int value) throws Exception;
    }

    static String send(String action, long deadlineMs, Transport transport) {
        SeatCommand command = SeatCommand.parse(action);
        if (command == null) return "Неизвестная команда сиденья или руля";
        try {
            transport.prepare(command.field, deadlineMs);
            return transport.send(command.field, command.value) == 0 ? null : "Не удалось отправить команду автомобилю";
        } catch (SecurityException e) {
            return "Нет разрешения на управление автомобилем";
        } catch (UnsupportedOperationException e) {
            return "Эта функция недоступна в OEM API автомобиля";
        } catch (java.util.concurrent.TimeoutException e) {
            return "Нет подключения к сервису автомобиля";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Не удалось отправить команду автомобилю";
        } catch (Exception | LinkageError e) {
            return "Не удалось отправить команду автомобилю";
        }
    }
}
