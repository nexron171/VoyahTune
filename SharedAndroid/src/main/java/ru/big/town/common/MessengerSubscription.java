package ru.big.town.common;

import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

/** A subscription belongs to its reply Binder; stale clients cannot remove a newer one. */
public final class MessengerSubscription {
    private Messenger client;
    public synchronized void set(Messenger next) { client = next; }
    public synchronized boolean active() { return client != null && client.getBinder().isBinderAlive(); }
    public synchronized boolean remove(Messenger owner) {
        if (client == null || !client.equals(owner)) return false;
        client = null;
        return true;
    }
    public synchronized void clear() { client = null; }
    public synchronized boolean send(Message message) {
        if (client == null) return false;
        try { client.send(message); return true; }
        catch (RemoteException dead) { client = null; return false; }
    }
}
