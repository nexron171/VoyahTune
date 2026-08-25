package ru.big.town.anative;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android-free fan-out with one bounded serial mailbox per consumer. */
final class CanBusEventRouter {
    static final int INTEREST_CONNECTION = 1 << 0;
    static final int INTEREST_DOOR = 1 << 1;
    static final int INTEREST_GEAR = 1 << 2;
    static final int INTEREST_LIGHT_STATUS = 1 << 3;
    static final int INTEREST_VEHICLE_STATE = 1 << 4;
    static final int INTEREST_AMBIENT_TEMPERATURE = 1 << 5;

    private static final int DEFAULT_MAILBOX_CAPACITY = 32;
    // Consumer handlers may be the Android main looper. Yield after every callback so a retained
    // transition burst cannot monopolize one looper quantum.
    private static final int DRAIN_SLICE = 1;

    interface Listener {
        void onCanBusEvent(CanBusEvent event);
    }

    private final CopyOnWriteArrayList<Mailbox> mailboxes = new CopyOnWriteArrayList<>();

    Subscription subscribe(int interestMask, int[] vehicleStateIds, Executor executor,
                           Listener listener) {
        return subscribe(interestMask, vehicleStateIds, executor, listener,
                DEFAULT_MAILBOX_CAPACITY);
    }

    Subscription subscribe(int interestMask, int[] vehicleStateIds, Executor executor,
                           Listener listener, int capacity) {
        if (executor == null || listener == null) {
            throw new IllegalArgumentException("executor/listener required");
        }
        Mailbox mailbox = new Mailbox(interestMask, vehicleStateIds, executor, listener, capacity);
        mailboxes.add(mailbox);
        return new Subscription(this, mailbox);
    }

    void dispatch(CanBusEvent event) {
        if (event == null) return;
        for (Mailbox mailbox : mailboxes) mailbox.offer(event);
    }

    boolean hasInterest(int interest) {
        for (Mailbox mailbox : mailboxes) {
            if (mailbox.hasInterest(interest)) return true;
        }
        return false;
    }

    boolean hasVehicleStateInterest(int stableId) {
        for (Mailbox mailbox : mailboxes) {
            if (mailbox.acceptsVehicleState(stableId)) return true;
        }
        return false;
    }

    int subscriberCount() {
        return mailboxes.size();
    }

    void invalidateThrough(long connectionEpoch) {
        for (Mailbox mailbox : mailboxes) mailbox.invalidateThrough(connectionEpoch);
    }

    private void remove(Mailbox mailbox) {
        mailboxes.remove(mailbox);
        mailbox.close();
    }

    static final class Subscription implements AutoCloseable {
        private final CanBusEventRouter owner;
        private final Mailbox mailbox;
        private final AtomicBoolean closed = new AtomicBoolean();

        Subscription(CanBusEventRouter owner, Mailbox mailbox) {
            this.owner = owner;
            this.mailbox = mailbox;
        }

        void offer(CanBusEvent event) {
            if (!closed.get()) mailbox.offer(event);
        }

        void forgetSignal(CanBusEvent.Kind kind) {
            if (!closed.get()) mailbox.forgetSignal(kind);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) owner.remove(mailbox);
        }
    }

    private static final class Mailbox {
        private final int interestMask;
        private final Set<Integer> vehicleStateIds = new HashSet<>();
        private final Executor executor;
        private final Listener listener;
        private final int capacity;
        private final ArrayDeque<CanBusEvent> queue = new ArrayDeque<>();
        private final Map<Integer, CanBusEvent> lastAccepted = new HashMap<>();
        private final Runnable drainRunnable = this::drain;

        private boolean drainScheduled;
        private volatile boolean closed;
        private long acceptedEpoch = Long.MIN_VALUE;
        private long invalidatedThroughEpoch = Long.MIN_VALUE;
        private long dropped;

        Mailbox(int interestMask, int[] vehicleStateIds, Executor executor,
                Listener listener, int capacity) {
            if (capacity < 2) throw new IllegalArgumentException("capacity must be >= 2");
            this.interestMask = interestMask;
            if (vehicleStateIds != null) {
                for (int id : vehicleStateIds) this.vehicleStateIds.add(id);
            }
            this.executor = executor;
            this.listener = listener;
            this.capacity = capacity;
        }

        boolean hasInterest(int interest) {
            return !closed && (interestMask & interest) != 0;
        }

        synchronized void forgetSignal(CanBusEvent.Kind kind) {
            if (kind != null) lastAccepted.remove(kind.ordinal() << 24);
        }

        boolean acceptsVehicleState(int stableId) {
            return hasInterest(INTEREST_VEHICLE_STATE) && vehicleStateIds.contains(stableId);
        }

        void offer(CanBusEvent event) {
            if (!accepts(event)) return;
            boolean schedule = false;
            synchronized (this) {
                if (closed) return;
                if (event.connectionEpoch <= invalidatedThroughEpoch) return;
                if (event.connectionEpoch < acceptedEpoch) return;
                if (event.connectionEpoch > acceptedEpoch) {
                    // A reconnect is a hard barrier: no queued state or transition from the old
                    // Binder recipient may execute in the new service session.
                    queue.clear();
                    lastAccepted.clear();
                    acceptedEpoch = event.connectionEpoch;
                }
                int key = event.signalKey();
                CanBusEvent previous = lastAccepted.get(key);
                if (event.samePayload(previous)) {
                    if (!drainScheduled && !queue.isEmpty()) {
                        drainScheduled = true;
                        schedule = true;
                    }
                } else {
                    lastAccepted.put(key, event);
                    if (!event.isOrderedTransition()) removeQueuedLevel(key);
                    if (queue.size() == capacity) {
                        CanBusEvent removed = dropForCapacity();
                        if (lastAccepted.get(removed.signalKey()) == removed) {
                            // A dropped transition was never delivered. Forgetting it lets an
                            // identical later safety signal enter the queue instead of being
                            // suppressed forever as a duplicate.
                            lastAccepted.remove(removed.signalKey());
                        }
                        dropped++;
                    }
                    queue.addLast(event);
                    if (!drainScheduled) {
                        drainScheduled = true;
                        schedule = true;
                    }
                }
            }
            if (schedule) scheduleDrain();
        }

        private boolean accepts(CanBusEvent event) {
            switch (event.kind) {
                case CONNECTION: return hasInterest(INTEREST_CONNECTION);
                case DOOR: return hasInterest(INTEREST_DOOR);
                case GEAR: return hasInterest(INTEREST_GEAR);
                case LIGHT_STATUS: return hasInterest(INTEREST_LIGHT_STATUS);
                case VEHICLE_STATE: return acceptsVehicleState(event.first);
                case AMBIENT_TEMPERATURE: return hasInterest(INTEREST_AMBIENT_TEMPERATURE);
                default: return false;
            }
        }

        private void removeQueuedLevel(int signalKey) {
            if (queue.isEmpty()) return;
            List<CanBusEvent> keep = new ArrayList<>(queue.size());
            while (!queue.isEmpty()) {
                CanBusEvent queued = queue.removeFirst();
                if (queued.signalKey() != signalKey) keep.add(queued);
            }
            queue.addAll(keep);
        }

        private CanBusEvent dropForCapacity() {
            // A connection is an epoch barrier and must never be displaced by a callback burst.
            // Prefer an already-coalescible level; only then sacrifice the oldest transition.
            Iterator<CanBusEvent> iterator = queue.iterator();
            while (iterator.hasNext()) {
                CanBusEvent queued = iterator.next();
                if (queued.kind != CanBusEvent.Kind.CONNECTION
                        && !queued.isOrderedTransition()) {
                    iterator.remove();
                    return queued;
                }
            }
            iterator = queue.iterator();
            while (iterator.hasNext()) {
                CanBusEvent queued = iterator.next();
                if (queued.kind != CanBusEvent.Kind.CONNECTION) {
                    iterator.remove();
                    return queued;
                }
            }
            // capacity >= 2 and duplicate connection events are coalesced, so this is defensive.
            return queue.removeFirst();
        }

        private void scheduleDrain() {
            try {
                executor.execute(drainRunnable);
            } catch (RuntimeException rejected) {
                synchronized (this) {
                    drainScheduled = false;
                }
            }
        }

        private void drain() {
            int delivered = 0;
            while (delivered < DRAIN_SLICE) {
                CanBusEvent event;
                synchronized (this) {
                    if (closed) return;
                    event = queue.pollFirst();
                    if (event == null) {
                        drainScheduled = false;
                        return;
                    }
                }
                try {
                    listener.onCanBusEvent(event);
                } catch (RuntimeException ignored) {
                    // One consumer must not poison its mailbox or another consumer.
                }
                delivered++;
            }

            boolean again;
            synchronized (this) {
                if (closed) return;
                again = !queue.isEmpty();
                if (!again) drainScheduled = false;
            }
            if (again) scheduleDrain();
        }

        synchronized void close() {
            closed = true;
            queue.clear();
            drainScheduled = false;
        }

        synchronized void invalidateThrough(long connectionEpoch) {
            if (closed || connectionEpoch <= invalidatedThroughEpoch) return;
            invalidatedThroughEpoch = connectionEpoch;
            queue.clear();
            lastAccepted.clear();
        }
    }
}
