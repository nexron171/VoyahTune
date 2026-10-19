package ru.big.town.anative;

import java.util.HashMap;
import java.util.Map;

/** Worker-confined revisions keep a late OEM cache read from replacing newer CAN feedback. */
final class BatteryHeatSnapshotState {
    static final class Read {
        private final long epoch;
        private final Map<Integer, Long> revisions;

        private Read(long epoch, Map<Integer, Long> revisions) {
            this.epoch = epoch;
            this.revisions = new HashMap<>(revisions);
        }
    }

    private final Map<Integer, Long> revisions = new HashMap<>();
    private long epoch;

    void reset(long connectionEpoch) {
        epoch = connectionEpoch;
        revisions.clear();
    }

    void recordState(int stateId) {
        revisions.put(stateId, revisions.getOrDefault(stateId, 0L) + 1L);
    }

    Read beginRead() {
        return new Read(epoch, revisions);
    }

    boolean accepts(Read read, int stateId, Integer value) {
        return value != null && value >= 0
                && epoch != 0L && read.epoch == epoch
                && read.revisions.getOrDefault(stateId, 0L)
                        .equals(revisions.getOrDefault(stateId, 0L));
    }
}
