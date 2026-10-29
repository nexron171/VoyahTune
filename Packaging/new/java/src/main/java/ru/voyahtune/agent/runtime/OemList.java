package ru.voyahtune.agent.runtime;

import java.lang.ref.WeakReference;
import java.util.AbstractList;
import java.util.List;

public final class OemList extends AbstractList<Object> {
    private final WeakReference<List<?>> list;
    private final Class<?> element;

    public OemList(Object list, Class<?> element) {
        if (!(list instanceof List<?>)) throw new IllegalArgumentException("OEM list required");
        this.list = new WeakReference<>((List<?>) list);
        this.element = element;
    }

    private List<?> current() {
        List<?> value = list.get();
        if (value == null) throw new IllegalStateException("OEM list was collected");
        return value;
    }
    @Override public int size() { return current().size(); }
    @Override public Object get(int index) { Object value = current().get(index); return value == null ? null : element.cast(value); }
    @Override public Object remove(int index) { Object value = current().remove(index); modCount++; return value; }
    @Override public void add(int index, Object value) {
        element.cast(value);
        try { OemAccess.call(current(), "add", index, value); modCount++; }
        catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("OEM list insertion failed", failure); }
    }
}
