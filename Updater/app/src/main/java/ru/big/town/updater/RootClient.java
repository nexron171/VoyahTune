package ru.big.town.updater;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Private UI-to-daemon transport. No archive, caller Intent or shell command is forwarded. */
final class RootClient {
    private static final int MAX_RESPONSE = 8 * 1024 * 1024;

    JSONObject call(JSONObject request) throws Exception {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress("voyahtune_updater", LocalSocketAddress.Namespace.RESERVED));
            // connect() creates the underlying Android socket before options can be set.
            String command = request.optString("command");
            socket.setSoTimeout("get_settings".equals(command) || "set_settings".equals(command) ? 45000 : 5000);
            if (socket.getPeerCredentials().getUid() != 0) {
                throw new IOException("Ответ получен не от root-службы");
            }
            byte[] input = (request.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            if (input.length > 8192) throw new IOException("Запрос слишком велик");
            socket.getOutputStream().write(input);
            socket.getOutputStream().flush();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            java.io.BufferedInputStream response = new java.io.BufferedInputStream(socket.getInputStream());
            boolean complete = false;
            while (bytes.size() < MAX_RESPONSE) {
                int value = response.read();
                if (value == -1) break;
                if (value == '\n') { complete = true; break; }
                bytes.write(value);
            }
            if (!complete) throw new IOException("Служба вернула неполный или слишком большой ответ");
            JSONObject result = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            if (result.optInt("schema", -1) != 1) throw new IOException("Несовместимая версия службы");
            if (!result.optBoolean("ok")) {
                String error = result.optString("error", "Ошибка службы");
                if ("Неизвестная команда или формат запроса".equals(error))
                    throw new UnsupportedOperationException(error);
                throw new IOException(error);
            }
            return result;
        }
    }
}
