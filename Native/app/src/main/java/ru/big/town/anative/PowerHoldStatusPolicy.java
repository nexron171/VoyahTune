package ru.big.town.anative;

import java.util.Objects;

/** Android-free state machine driven by exact Power Hold vehicle feedback. */
final class PowerHoldStatusPolicy {
    enum Status {
        UNKNOWN(0),
        INACTIVE(1),
        ACTIVATING(2),
        ACTIVE(3),
        FAILED(4);

        final int ipcCode;

        Status(int ipcCode) {
            this.ipcCode = ipcCode;
        }

        static Status fromIpcCode(int code) {
            for (Status status : values()) {
                if (status.ipcCode == code) return status;
            }
            return UNKNOWN;
        }
    }

    enum ExitReason {
        NONE(0),
        LOW_BATTERY(1),
        TIME_UP(2),
        COMMON(3);

        final int ipcCode;

        ExitReason(int ipcCode) {
            this.ipcCode = ipcCode;
        }

        static ExitReason fromIpcCode(int code) {
            for (ExitReason reason : values()) {
                if (reason.ipcCode == code) return reason;
            }
            return NONE;
        }
    }

    static final class Snapshot {
        final Status status;
        final ExitReason exitReason;
        final long connectionEpoch;
        final long requestGeneration;

        Snapshot(Status status, ExitReason exitReason, long connectionEpoch,
                 long requestGeneration) {
            this.status = Objects.requireNonNull(status, "status");
            this.exitReason = Objects.requireNonNull(exitReason, "exitReason");
            this.connectionEpoch = connectionEpoch;
            this.requestGeneration = requestGeneration;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Snapshot)) return false;
            Snapshot that = (Snapshot) other;
            return connectionEpoch == that.connectionEpoch
                    && requestGeneration == that.requestGeneration
                    && status == that.status && exitReason == that.exitReason;
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, exitReason, connectionEpoch, requestGeneration);
        }
    }

    static final class Machine {
        private Status status = Status.UNKNOWN;
        private Status statusBeforeActivation = Status.UNKNOWN;
        private ExitReason exitReason = ExitReason.NONE;
        private ExitReason pendingExitReason = ExitReason.NONE;
        private long connectionEpoch;
        private long requestGeneration;
        private boolean activationAccepted;
        private boolean exitedFromActive;

        Snapshot snapshot() {
            return new Snapshot(status, exitReason, connectionEpoch, requestGeneration);
        }

        Snapshot onConnection(long epoch) {
            if (epoch <= 0 || epoch == connectionEpoch) return snapshot();
            connectionEpoch = epoch;
            activationAccepted = false;
            pendingExitReason = ExitReason.NONE;
            exitedFromActive = false;
            status = Status.UNKNOWN;
            exitReason = ExitReason.NONE;
            return snapshot();
        }

        Snapshot onConnectionLost(long epoch) {
            if (epoch != connectionEpoch) return snapshot();
            activationAccepted = false;
            pendingExitReason = ExitReason.NONE;
            exitedFromActive = false;
            status = Status.UNKNOWN;
            exitReason = ExitReason.NONE;
            return snapshot();
        }

        long beginActivation() {
            requestGeneration++;
            statusBeforeActivation = status;
            activationAccepted = false;
            pendingExitReason = ExitReason.NONE;
            exitedFromActive = false;
            status = Status.ACTIVATING;
            exitReason = ExitReason.NONE;
            return requestGeneration;
        }

        Snapshot finishActivation(long generation, PowerHoldPolicy.Outcome outcome) {
            if (generation != requestGeneration || status != Status.ACTIVATING) {
                return snapshot();
            }
            if (outcome == PowerHoldPolicy.Outcome.ACCEPTED) {
                activationAccepted = true;
                return snapshot();
            }
            activationAccepted = false;
            if (outcome == PowerHoldPolicy.Outcome.NOT_IN_PARK
                    || outcome == PowerHoldPolicy.Outcome.LOW_BATTERY) {
                status = statusBeforeActivation;
            } else {
                status = Status.FAILED;
            }
            return snapshot();
        }

        Snapshot onActivationTimeout(long generation) {
            if (generation == requestGeneration && status == Status.ACTIVATING
                    && activationAccepted) {
                activationAccepted = false;
                status = Status.FAILED;
            }
            return snapshot();
        }

        Snapshot onSwitch(long epoch, int value) {
            if (epoch != connectionEpoch) return snapshot();
            if (value == PowerHoldPolicy.POWER_HOLD_ON) {
                activationAccepted = false;
                pendingExitReason = ExitReason.NONE;
                exitedFromActive = false;
                status = Status.ACTIVE;
                exitReason = ExitReason.NONE;
                return snapshot();
            }
            if (value != 0) return snapshot();
            if (status == Status.ACTIVATING && activationAccepted) {
                return snapshot();
            }
            boolean wasActive = status == Status.ACTIVE;
            activationAccepted = false;
            status = Status.INACTIVE;
            exitedFromActive = wasActive;
            exitReason = wasActive
                    ? (pendingExitReason == ExitReason.NONE
                            ? ExitReason.COMMON : pendingExitReason)
                    : ExitReason.NONE;
            pendingExitReason = ExitReason.NONE;
            return snapshot();
        }

        Snapshot onWarning(long epoch, int value) {
            if (epoch != connectionEpoch) return snapshot();
            ExitReason reason = warningReason(value);
            if (reason == ExitReason.NONE) return snapshot();
            if (status == Status.INACTIVE && exitedFromActive) {
                exitReason = reason;
            } else {
                pendingExitReason = reason;
            }
            return snapshot();
        }
    }

    private PowerHoldStatusPolicy() {
    }

    static ExitReason warningReason(int value) {
        if (value == 1) return ExitReason.LOW_BATTERY;
        if (value == 2) return ExitReason.TIME_UP;
        return ExitReason.NONE;
    }
}
