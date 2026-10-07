package ru.big.town.updater.ui;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.UUID;

/** An independent stateful JSON peer. It never calls device installation APIs. */
final class MockRootService implements AutoCloseable {
    private final String name = "voyahtune-test-" + UUID.randomUUID();
    private final LocalServerSocket listener = new LocalServerSocket(name);
    final CopyOnWriteArrayList<JSONObject> requests = new CopyOnWriteArrayList<>();
    volatile String wireOverride;
    volatile boolean offline, legacyFinish, dns = true;
    private volatile boolean closed;
    private JSONObject state = new JSONObject();
    private JSONObject settings = new JSONObject().put("catalogUrl", "https://example.test/catalog.json").put("dnsEnabled", false);
    private final Thread server;

    MockRootService() throws Exception {
        phase("idle", false);
        server = new Thread(() -> {
            while (!closed) {
                try (LocalSocket socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    String line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
                    if (line == null) continue;
                    JSONObject request = new JSONObject(line);
                    requests.add(request);
                    if (offline) continue;
                    String wire = wireOverride;
                    if (wire == null) wire = reply(request).toString() + "\n";
                    socket.getOutputStream().write(wire.getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch (Exception e) { if (!closed) failure = e; }
            }
        }, "MockRootService");
        server.setDaemon(true);
        server.start();
    }
    volatile Exception failure;
    RootClient client() { return client(Process.myUid()); }
    RootClient client(int uid) { return new RootClient(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT), uid); }
    synchronized void phase(String phase, boolean selected) throws Exception {
        state = new JSONObject().put("phase", phase).put("step", "Шаг службы")
                .put("installedVersion", "3.22.0-od").put("bytes", 25).put("total", 100)
                .put("completedSteps", 3).put("totalSteps", 8).put("notice", JSONObject.NULL)
                .put("error", phase.equals("failed") || phase.equals("repair-required") ? "Ошибка проверки" : JSONObject.NULL);
        if (selected) state.put("selected", new JSONObject().put("version", "3.23.0-od")
                .put("payload", new JSONObject().put("size", 104857600)));
    }
    synchronized void value(String key, Object value) throws Exception { state.put(key, value); }
    synchronized String phase() { return state.optString("phase"); }
    long count(String command) { return requests.stream().filter(r -> command.equals(r.optString("command"))).count(); }
    JSONObject last(String command) {
        for (int i=requests.size()-1;i>=0;i--) if (command.equals(requests.get(i).optString("command"))) return requests.get(i);
        return null;
    }
    private synchronized JSONObject reply(JSONObject request) throws Exception {
        JSONObject out = new JSONObject().put("schema", 1).put("ok", true);
        switch (request.getString("command")) {
            case "status": return out.put("state", new JSONObject(state.toString())).put("settings", settings)
                    .put("capabilities", new JSONArray(dns ? "[\"dns-settings\",\"install-step-progress\"]" : "[]"));
            case "check": phase("checking", false); break;
            case "download": phase("downloading", true); break;
            case "apply": phase("preparing", true); break;
            case "finish":
                if (legacyFinish) return out.put("ok", false).put("error", "Неизвестная команда или формат запроса");
                if (phase().equals("committed") || (request.optBoolean("reset_errors") && phase().equals("failed"))) phase("idle", false);
                break;
            case "get_settings": return out.put("settings", settings).put("dnsStatus", "off");
            case "set_catalog_url":
            case "set_settings":
                settings.put("catalogUrl", request.getString("url"));
                if (request.has("dns_enabled")) settings.put("dnsEnabled", request.get("dns_enabled"));
                return out.put("settings", settings);
            case "dismiss": state.put("notice", JSONObject.NULL); break;
            default: return out.put("ok", false).put("error", "Неизвестная команда или формат запроса");
        }
        return out;
    }
    @Override public void close() throws Exception {
        closed = true;
        listener.close();
        server.join(4000);
    }
}
