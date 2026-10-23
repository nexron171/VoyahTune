package ru.big.town.anative;

import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Worker-confined dashboard subscriptions, with an independent session for each reply Binder. */
final class WidgetClients {
    private final Map<Messenger, Integer> sessions = new LinkedHashMap<>();

    void watch(Messenger client) {
        watch(client, 0);
    }

    void watch(Messenger client, int session) {
        if (client != null) {
            sessions.put(client, session);
        }
    }

    boolean isWatching(Messenger client, int session) {
        Integer current = sessions.get(client);
        return current != null && current == session;
    }

    boolean unwatch(Messenger client) {
        return sessions.remove(client) != null;
    }

    boolean unwatch(Messenger client, int session) {
        return isWatching(client, session) && unwatch(client);
    }

    boolean hasClients() {
        sessions.keySet().removeIf(client -> !client.getBinder().isBinderAlive());
        return !sessions.isEmpty();
    }

    boolean publish(int type, Bundle data) {
        Iterator<Map.Entry<Messenger, Integer>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Messenger, Integer> client = iterator.next();
            if (!client.getKey().getBinder().isBinderAlive()) {
                iterator.remove();
                continue;
            }
            Message response = Message.obtain(null, type, 0, client.getValue());
            response.setData(new Bundle(data));
            try {
                client.getKey().send(response);
            } catch (RemoteException dead) {
                iterator.remove();
            }
        }
        return !sessions.isEmpty();
    }

    void clear() {
        sessions.clear();
    }
}
