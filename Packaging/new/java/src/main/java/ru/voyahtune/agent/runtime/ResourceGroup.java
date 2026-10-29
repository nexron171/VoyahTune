package ru.voyahtune.agent.runtime;

import java.util.ArrayList;
import java.util.List;
import ru.voyahtune.agent.CloseOperation;

public final class ResourceGroup implements AutoCloseable {
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final CloseOperation closing = new CloseOperation();
    private boolean closed;

    public synchronized void own(AutoCloseable resource) throws Exception {
        if (closed) { resource.close(); throw new IllegalStateException("Resource group is closed"); }
        resources.add(java.util.Objects.requireNonNull(resource));
    }

    @Override public void close() throws Exception {
        closing.run(() -> {
            List<AutoCloseable> owned;
            synchronized (this) { closed = true; owned = new ArrayList<>(resources); resources.clear(); }
            ModuleScope.closeAll(owned);
        });
    }
}
