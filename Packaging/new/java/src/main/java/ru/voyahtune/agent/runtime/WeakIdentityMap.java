package ru.voyahtune.agent.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class WeakIdentityMap<K, V> {
    private final ReferenceQueue<K> collected = new ReferenceQueue<>();
    private final Map<Key<K>, V> values = new HashMap<>();

    public synchronized V get(K key) { drain(); return values.get(new Key<>(key, null)); }
    public synchronized void put(K key, V value) { drain(); values.put(new Key<>(key, collected), value); }
    public synchronized V remove(K key) { drain(); return values.remove(new Key<>(key, null)); }
    public synchronized int size() { drain(); return values.size(); }
    public synchronized List<K> keys() {
        drain();
        List<K> result = new ArrayList<>();
        for (Key<K> key : values.keySet()) {
            K value = key.get();
            if (value != null) result.add(value);
        }
        return result;
    }

    private void drain() {
        java.lang.ref.Reference<? extends K> key;
        while ((key = collected.poll()) != null) values.remove(key);
    }

    private static final class Key<K> extends WeakReference<K> {
        private final int hash;
        Key(K value, ReferenceQueue<K> queue) {
            super(java.util.Objects.requireNonNull(value), queue);
            hash = System.identityHashCode(value);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Key<?> && get() != null && get() == ((Key<?>) other).get();
        }
    }
}
