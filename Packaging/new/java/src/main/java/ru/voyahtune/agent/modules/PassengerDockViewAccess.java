package ru.voyahtune.agent.modules;

import android.view.View;
import android.widget.LinearLayout;

import ru.voyahtune.agent.runtime.ViewOwnership;

import java.util.List;
import java.util.Objects;

final class PassengerDockViewAccess implements ViewOwnership.Access {
    private final ViewOwnership.Access delegate;

    PassengerDockViewAccess(ViewOwnership.Access delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object read(Object owner, String property) throws Exception {
        View view = (View) owner;
        switch (property) {
            case "dockPadding":
                return List.of(
                        view.getPaddingLeft(),
                        view.getPaddingTop(),
                        view.getPaddingRight(),
                        view.getPaddingBottom());
            case "dockGravity":
                return ((LinearLayout) view).getGravity();
            default:
                return delegate.read(owner, property);
        }
    }

    @Override
    public void write(Object owner, String property, Object value) throws Exception {
        View view = (View) owner;
        if (Objects.equals(value, read(owner, property))) {
            return;
        }
        switch (property) {
            case "dockPadding":
                List<?> padding = (List<?>) value;
                view.setPadding(
                        (Integer) padding.get(0),
                        (Integer) padding.get(1),
                        (Integer) padding.get(2),
                        (Integer) padding.get(3));
                break;
            case "dockGravity":
                ((LinearLayout) view).setGravity((Integer) value);
                break;
            default:
                delegate.write(owner, property, value);
        }
    }

    @Override
    public AutoCloseable retain(Object owner, Object state) throws Exception {
        return delegate.retain(owner, state);
    }
}
