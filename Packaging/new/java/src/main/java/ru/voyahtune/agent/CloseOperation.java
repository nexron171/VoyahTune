package ru.voyahtune.agent;

import java.util.Objects;

public final class CloseOperation {
    private enum Phase {
        OPEN, CLOSING, COMPLETED
    }

    private Phase phase = Phase.OPEN;
    private Thread owner;
    private Throwable failure;

    public void run(AutoCloseable cleanup) throws Exception {
        Objects.requireNonNull(cleanup);
        synchronized (this) {
            if (phase != Phase.OPEN) {
                if (owner == Thread.currentThread()) {
                    throw new IllegalStateException("Recursive resource cleanup");
                }
                awaitCompletion();
                rethrowFailure();
                return;
            }
            phase = Phase.CLOSING;
            owner = Thread.currentThread();
        }
        Throwable caught = null;
        try {
            cleanup.close();
        } catch (Throwable problem) {
            caught = problem;
        } finally {
            synchronized (this) {
                failure = caught;
                owner = null;
                phase = Phase.COMPLETED;
                notifyAll();
            }
        }
        rethrowFailure();
    }

    private void awaitCompletion() {
        boolean interrupted = false;
        while (phase != Phase.COMPLETED) {
            try {
                wait();
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void rethrowFailure() throws Exception {
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        if (failure != null) {
            throw new IllegalStateException("Resource cleanup aborted", failure);
        }
    }
}
