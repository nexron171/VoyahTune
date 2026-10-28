package ru.voyahtune.agent.policy;

public final class RadioRestoreController implements AutoCloseable {
    @FunctionalInterface public interface Action { void run() throws Exception; }
    public interface IO {
        long now();
        void later(long delay, Runnable action);
        RadioStation current() throws Exception;
        boolean ready() throws Exception;
        boolean acquireFocus() throws Exception;
        boolean hasFocus() throws Exception;
        boolean isPlaying() throws Exception;
        boolean save(RadioStation station) throws Exception;
        void tune(RadioStation station) throws Exception;
        void playCurrent() throws Exception;
        void stageSelected(RadioStation station) throws Exception;
        void clearSelected() throws Exception;
        void cancelOwned() throws Exception;
        void releaseOwned() throws Exception;
        void log(String event);
        default void error(Exception failure) { throw new IllegalStateException("Deferred radio operation failed", failure); }
    }

    private final IO io;
    private RadioStation saved;
    private RadioStation selected;
    private long selectedUntil;
    private boolean scanRequested;
    private long scanRevision;
    private Request request;
    private long revision;
    private long candidateRevision;
    private RadioStation candidateStation;
    private boolean closed;
    private long silentCycle;

    public RadioRestoreController(IO io, RadioStation initial) {
        this.io = io;
        saved = valid(initial) && !initial.trafficAnnouncement ? initial : null;
    }

    private static boolean valid(RadioStation station) { return station != null && station.persistable(); }
    private static boolean same(RadioStation first, RadioStation second) { return valid(first) && valid(second) && first.sameFrequency(second); }
    private static boolean exact(RadioStation first, RadioStation second) { return first != null && first.sameRecord(second); }

    private void cancel(String reason, boolean keepCandidate) throws Exception {
        revision++;
        if (!keepCandidate) { candidateRevision++; candidateStation = null; }
        request = null;
        io.cancelOwned();
        io.log(reason);
    }

    public synchronized void observe(RadioStation station) throws Exception {
        if (closed || !valid(station) || station.trafficAnnouncement || !io.ready()) return;
        if (request != null && same(station, request.target)) {
            request = null;
            revision++;
            io.releaseOwned();
            io.log("restore confirmed " + station.frequency);
        }
        if (scanRequested) return;
        if (selected != null && io.now() > selectedUntil) selected = null;
        if (selected != null ? !same(station, selected) : saved != null && !same(station, saved)) return;
        if (exact(candidateStation, station)) return;
        candidateStation = station;
        long candidate = ++candidateRevision;
        later(750, () -> {
            synchronized (this) {
                if (closed || candidate != candidateRevision) return;
                candidateStation = null;
                if (scanRequested || !io.ready()) return;
                RadioStation current = io.current();
                if (!valid(current) || current.trafficAnnouncement || !same(current, station)) return;
                if (selected != null && !same(current, selected) || exact(saved, current)) return;
                if (io.save(current)) { saved = current; selected = null; io.log("station saved " + current.frequency); }
                else io.log("station save failed; previous durable record retained");
            }
        });
    }

    public synchronized boolean restoreSilent(long cycle) throws Exception {
        if (closed || cycle <= 0 || cycle == silentCycle) return false;
        silentCycle = cycle;
        if (saved == null || scanRequested || request != null || selected != null && io.now() <= selectedUntil || io.isPlaying()) return false;
        io.stageSelected(saved);
        io.log("silent selection restored " + saved.frequency + " cycle=" + cycle);
        return true;
    }

    public synchronized void select(RadioStation station) throws Exception {
        io.clearSelected();
        cancel("explicit station choice", false);
        scanRequested = false;
        selected = valid(station) ? station : null;
        selectedUntil = io.now() + 15000;
    }

    public synchronized void pause() throws Exception { cancel("playback paused", true); }

    public synchronized void cancelPending() throws Exception {
        scanRequested = false;
        scanRevision++;
        cancel("configuration changed", false);
    }

    public synchronized void scan() throws Exception {
        io.clearSelected();
        cancel("explicit scan", false);
        selected = null;
        scanRequested = true;
        long token = ++scanRevision;
        later(60000, () -> {
            synchronized (this) {
                if (!closed && token == scanRevision && scanRequested) { scanRequested = false; io.log("scan observation expired; durable station retained"); }
            }
        });
    }

    public synchronized void scanFinished() throws Exception {
        if (scanRequested) {
            scanRequested = false;
            RadioStation current = io.current();
            if (valid(current)) { selected = current; selectedUntil = io.now() + 15000; }
        }
        observe(io.current());
    }

    public synchronized void resume(Action fallback, boolean alreadyPlaying) throws Exception {
        io.clearSelected();
        if (closed || scanRequested || selected != null && io.now() <= selectedUntil || saved == null) { fallback.run(); return; }
        if (request != null) {
            if (io.now() < request.until) return;
            cancel("previous restore expired before new playback request", false);
        }
        candidateRevision++;
        candidateStation = null;
        long token = ++revision;
        request = new Request(saved, io.now() + 10000, alreadyPlaying);
        if (!io.acquireFocus()) { cancel("radio focus unavailable", false); return; }
        step(token);
    }

    private synchronized void step(long token) throws Exception {
        if (closed || token != revision || request == null) return;
        if (io.now() >= request.until) { cancel("restore timed out without confirmation", false); return; }
        if (!io.hasFocus()) { cancel("radio focus lost", false); return; }
        if (io.ready()) {
            RadioStation current = io.current();
            if (same(current, request.target) && !current.trafficAnnouncement) {
                if (!request.submitted && !request.alreadyPlaying) io.playCurrent();
                observe(current);
                return;
            }
            if (!request.submitted) {
                request.submitted = true;
                io.log("restore submitted " + request.target.frequency);
                try { io.tune(request.target); }
                catch (Exception failure) { cancel("restore dispatch failed: " + failure, false); return; }
                if (request == null || token != revision) return;
            }
        }
        later(250, () -> step(token));
    }

    private void later(long delay, Action action) {
        io.later(delay, () -> {
            try { action.run(); }
            catch (Exception failure) {
                try { pause(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                io.error(failure);
            }
        });
    }

    @Override public synchronized void close() throws Exception {
        if (closed) return;
        closed = true;
        io.clearSelected();
        cancel("hook closed", false);
    }

    private static final class Request {
        final RadioStation target;
        final long until;
        final boolean alreadyPlaying;
        boolean submitted;
        Request(RadioStation target, long until, boolean alreadyPlaying) { this.target = target; this.until = until; this.alreadyPlaying = alreadyPlaying; }
    }
}
