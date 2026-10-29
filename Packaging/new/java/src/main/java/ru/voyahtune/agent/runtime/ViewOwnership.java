package ru.voyahtune.agent.runtime;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ViewOwnership implements AutoCloseable {
    public interface Access {
        Object read(Object view, String property) throws Exception;
        void write(Object view, String property, Object value) throws Exception;
        AutoCloseable retain(Object view, Object state) throws Exception;
    }

    private final Access access;
    private final WeakIdentityMap<Object, WeakReference<State>> views = new WeakIdentityMap<>();
    private boolean closed;

    public ViewOwnership(Access access) { this.access = java.util.Objects.requireNonNull(access); }

    public synchronized void write(Object view, String name, Object value) throws Exception {
        if (closed) throw new IllegalStateException("View ownership is closed");
        State state = state(view);
        Object current = access.read(view, name);
        Property property = state.properties.get(name);
        if (property == null) {
            property = new Property(current);
            state.properties.put(name, property);
        } else if (!Objects.equals(current, property.applied)) property.original = current;
        property.applied = value;
        access.write(view, name, value);
    }

    public synchronized void restore(Object view) throws Exception {
        WeakReference<State> reference = views.get(view);
        if (reference == null) return;
        State state = reference.get();
        if (state == null) throw new IllegalStateException("Live view lost its ownership anchor");
        Exception failure = null;
        for (String name : new ArrayList<>(state.properties.keySet())) {
            try {
                Property property = state.properties.get(name);
                if (Objects.equals(access.read(view, name), property.applied)) access.write(view, name, property.original);
                state.properties.remove(name);
            } catch (Exception caught) {
                if (failure == null) failure = caught;
                else failure.addSuppressed(caught);
            }
        }
        if (failure != null) throw failure;
        state.anchor.close();
        views.remove(view);
    }

    public synchronized void restore(Object view, String name) throws Exception {
        WeakReference<State> reference = views.get(view);
        if (reference == null) return;
        State state = reference.get();
        if (state == null) throw new IllegalStateException("Live view lost its ownership anchor");
        Property property = state.properties.get(name);
        if (property != null) {
            if (Objects.equals(access.read(view, name), property.applied)) access.write(view, name, property.original);
            state.properties.remove(name);
        }
        if (state.properties.isEmpty()) {
            state.anchor.close();
            views.remove(view);
        }
    }

    private State state(Object view) throws Exception {
        WeakReference<State> known = views.get(view);
        if (known != null) {
            State state = known.get();
            if (state == null) throw new IllegalStateException("Live view lost its ownership anchor");
            return state;
        }
        if (views.size() >= 512) throw new IllegalStateException("View ownership limit reached");
        State state = new State();
        // The view retains its baseline; the global weak registry cannot retain an OEM view graph.
        state.anchor = access.retain(view, state);
        views.put(view, new WeakReference<>(state));
        return state;
    }

    @Override public synchronized void close() throws Exception {
        closed = true;
        Exception failure = null;
        for (Object view : views.keys()) {
            try { restore(view); }
            catch (Exception caught) { if (failure == null) failure = caught; else failure.addSuppressed(caught); }
        }
        if (failure != null) throw failure;
    }

    private static final class State {
        final Map<String, Property> properties = new LinkedHashMap<>();
        AutoCloseable anchor;
    }
    private static final class Property {
        Object original;
        Object applied;
        Property(Object original) { this.original = original; }
    }
}
