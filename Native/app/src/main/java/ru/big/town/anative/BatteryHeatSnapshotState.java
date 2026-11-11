package ru.big.town.anative;

import java.util.HashMap;
import java.util.Map;

/** Worker-confined revisions keep a late OEM cache read from replacing newer CAN feedback. */
final class BatteryHeatSnapshotState {
    static final class Read {
        private final long epoch;
        private final long generation;
        private final long ambientRevision;
        private final Map<Integer, Long> revisions;

        private Read(long epoch, long generation, long ambientRevision,
                     Map<Integer, Long> revisions) {
            this.epoch = epoch;
            this.generation = generation;
            this.ambientRevision = ambientRevision;
            this.revisions = new HashMap<>(revisions);
        }
    }

    private final Map<Integer, Long> revisions = new HashMap<>();
    private long epoch;
    private long generation;
    private long ambientRevision;

    void reset(long connectionEpoch) {
        epoch = connectionEpoch;
        generation++;
        revisions.clear();
    }

    void recordState(int stateId) {
        revisions.put(stateId, revisions.getOrDefault(stateId, 0L) + 1L);
    }

    Read beginRead() {
        return new Read(epoch, generation, ambientRevision, revisions);
    }

    void recordAmbientTemperature() {
        ambientRevision++;
    }

    boolean acceptsAmbientTemperature(Read read) {
        return current(read) && read.ambientRevision == ambientRevision;
    }

    boolean accepts(Read read, int stateId, Integer value) {
        return value != null && value >= 0
                && current(read)
                && read.revisions.getOrDefault(stateId, 0L)
                        .equals(revisions.getOrDefault(stateId, 0L));
    }

    private boolean current(Read read) {
        return epoch != 0L && read.epoch == epoch && read.generation == generation;
    }
}
