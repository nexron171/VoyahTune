package ru.big.town.updater.ui;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Private UI-to-daemon transport. No archive, caller Intent or shell command is forwarded. */
final class RootClient implements UpdateService {
    private static final int MAX_RESPONSE = 8 * 1024 * 1024;
    private final LocalSocketAddress address;
    private final int expectedUid;

    RootClient() {
        this(new LocalSocketAddress("voyahtune_updater", LocalSocketAddress.Namespace.RESERVED), 0);
    }

    RootClient(LocalSocketAddress address, int expectedUid) {
        this.address = address;
        this.expectedUid = expectedUid;
    }

    public JSONObject call(JSONObject request) throws Exception {
        return call(request, event -> {});
    }

    JSONObject call(JSONObject request, java.util.function.Consumer<JSONObject> progress)
            throws Exception {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(address);
            // connect() creates the underlying Android socket before options can be set.
            String command = request.optString("command");
            socket.setSoTimeout(
                    "turn_sound".equals(command)
                            ? 240000
                            : "get_settings".equals(command) || "set_settings".equals(command)
                                    ? 45000
                                    : 5000);
            if (socket.getPeerCredentials().getUid() != expectedUid) {
                throw new IOException("Ответ получен не от root-службы");
            }
            byte[] input = (request.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            if (input.length > 8192) throw new IOException("Запрос слишком велик");
            socket.getOutputStream().write(input);
            socket.getOutputStream().flush();
            java.io.BufferedInputStream response =
                    new java.io.BufferedInputStream(socket.getInputStream());
            int received = 0;
            while (true) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                boolean complete = false;
                while (received < MAX_RESPONSE) {
                    int value = response.read();
                    if (value == -1) break;
                    received++;
                    if (value == '\n') {
                        complete = true;
                        break;
                    }
                    bytes.write(value);
                }
                if (!complete)
                    throw new IOException("Служба вернула неполный или слишком большой ответ");
                JSONObject result = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                if (result.optInt("schema", -1) != 1)
                    throw new IOException("Несовместимая версия службы");
                if (!result.optBoolean("ok")) {
                    String error = result.optString("error", "Ошибка службы");
                    if ("Неизвестная команда или формат запроса".equals(error))
                        throw new UnsupportedOperationException(error);
                    throw new IOException(error);
                }
                if ("turn_sound_progress".equals(result.optString("event"))) {
                    progress.accept(result);
                    continue;
                }
                return result;
            }
        }
    }
}
