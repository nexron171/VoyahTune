package ru.voyahtune.agent.modules;

import java.util.concurrent.atomic.AtomicLong;
import ru.voyahtune.agent.policy.RadioStation;
import ru.voyahtune.agent.runtime.OemAccess;

public final class RdsTuneOwnership implements AutoCloseable {
    private final Object manager;
    private final Class<?> stationType;
    private final RdsModule.Originals originals;
    private final AtomicLong playbackCalls = new AtomicLong();
    private volatile Object owned;
    private volatile RadioStation dispatching;
    private int dispatchDepth;
    private boolean cancelAfterDispatch;
    private boolean ownsResumeDelay;

    public RdsTuneOwnership(Object manager, Class<?> stationType, RdsModule.Originals originals) {
        this.manager = manager;
        this.stationType = stationType;
        this.originals = originals;
    }

    public boolean owns(Object station) { Object command = owned; return command != null && command.equals(station); }
    public boolean hasOwned() { return owned != null; }
    public Object owned() { return owned; }
    public RadioStation dispatching() { return dispatching; }
    public long playbackCalls() { return playbackCalls.get(); }
    public synchronized void resumeDelay(boolean owned) { ownsResumeDelay = owned; }

    public Object command(RadioStation station) throws Exception {
        Object command = OemAccess.construct(stationType, station.frequency);
        OemAccess.call(command, "setIsFMStation", station.fm);
        OemAccess.call(command, "setIsRDS", false);
        OemAccess.call(command, "setPICode", 0);
        return command;
    }

    public synchronized void beginDispatch(RadioStation station) { dispatchDepth++; dispatching = station; }
    public synchronized void finishDispatch() throws Exception {
        if (--dispatchDepth != 0) return;
        dispatching = null;
        if (cancelAfterDispatch) { cancelAfterDispatch = false; cancel(); }
    }

    private void releaseResumeDelay() throws Exception {
        if (ownsResumeDelay) { OemAccess.write(manager, "delayPlay", false); ownsResumeDelay = false; }
    }

    public synchronized void release() throws Exception { releaseResumeDelay(); owned = null; }

    public synchronized void cancel() throws Exception {
        releaseResumeDelay();
        Object command = owned;
        if (command == null) return;
        if (dispatchDepth > 0) { cancelAfterDispatch = true; return; }
        Object delayed = OemAccess.read(manager, "delayPlayStation");
        if (delayed != null && delayed.equals(command)) {
            OemAccess.write(manager, "delayPlayStation", null);
            OemAccess.write(manager, "delayPlay", false);
        }
        Object pending = OemAccess.read(manager, "tempPlayStation");
        if (pending != null && pending.equals(command)) {
            OemAccess.write(manager, "tempPlayStation", null);
            if (Integer.valueOf(1002).equals(OemAccess.read(manager, "actionCode"))) OemAccess.write(manager, "actionCode", -1);
            OemAccess.call(OemAccess.read(manager, "handler"), "removeMessages", 1002);
        }
        release();
    }

    public void tune(RadioStation station) throws Exception {
        cancel();
        Object command = command(station);
        owned = command;
        beginDispatch(station);
        try { playbackCalls.incrementAndGet(); originals.call("tune", manager, command); }
        finally { finishDispatch(); }
    }

    public void playCurrent() throws Exception { playbackCalls.incrementAndGet(); originals.call("play", manager); }
    @Override public void close() throws Exception { cancel(); }
}
