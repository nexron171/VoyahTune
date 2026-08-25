package ru.big.town.anative;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One restore pass split into independently retryable CAN/OEM commands.
 *
 * <p>A failed command remains pending while later independent commands are still attempted. This
 * prevents a permanently failing middle command from replaying the already successful prefix on
 * every retry. A new intentional repeat resets the whole plan.</p>
 */
final class CanRestorePlan {
    enum AttemptResult {
        SUCCESS,
        /** Every command was submitted, but an asynchronous OEM API cannot prove CAN completion. */
        ACCEPTED_UNCONFIRMED,
        TRANSIENT_FAILURE;

        boolean isComplete() {
            return this != TRANSIENT_FAILURE;
        }
    }

    enum OperationResult {
        CONFIRMED,
        ACCEPTED_UNCONFIRMED,
        TRANSIENT_FAILURE
    }

    interface Sender {
        boolean send(byte[][] frames, String label);
    }

    interface Operation {
        OperationResult send();
    }

    private static final int NO_DEPENDENCY = -1;

    private final List<Command> commands;
    private final OperationResult[] results;

    private CanRestorePlan(List<Command> commands) {
        this.commands = commands;
        this.results = new OperationResult[commands.size()];
    }

    AttemptResult sendPending(Sender sender) {
        for (int i = 0; i < commands.size(); i++) {
            if (results[i] != null) continue;
            Command command = commands.get(i);
            if (command.dependency >= 0 && results[command.dependency] == null) continue;
            OperationResult result;
            if (command.operation != null) {
                result = command.operation.send();
            } else {
                result = sender.send(command.frames, command.label)
                        ? OperationResult.CONFIRMED : OperationResult.TRANSIENT_FAILURE;
            }
            if (result != OperationResult.TRANSIENT_FAILURE) results[i] = result;
        }
        if (!isComplete()) return AttemptResult.TRANSIENT_FAILURE;
        for (OperationResult result : results) {
            if (result == OperationResult.ACCEPTED_UNCONFIRMED) {
                return AttemptResult.ACCEPTED_UNCONFIRMED;
            }
        }
        return AttemptResult.SUCCESS;
    }

    void resetForNextPass() {
        for (int i = 0; i < commands.size(); i++) {
            if (commands.get(i).repeatOnNextPass) results[i] = null;
        }
    }

    int pendingCount() {
        int pending = 0;
        for (OperationResult result : results) if (result == null) pending++;
        return pending;
    }

    boolean hasRepeatableCommands() {
        for (Command command : commands) {
            if (command.repeatOnNextPass) return true;
        }
        return false;
    }

    private boolean isComplete() {
        return pendingCount() == 0;
    }

    static final class Builder {
        private final List<Command> commands = new ArrayList<>();

        int add(String label, byte[][] frames) {
            return addAfter(label, frames, NO_DEPENDENCY);
        }

        int addAfter(String label, byte[][] frames, int dependency) {
            validate(label, frames);
            if (dependency < NO_DEPENDENCY || dependency >= commands.size()) {
                throw new IllegalArgumentException("Invalid dependency for " + label);
            }
            commands.add(new Command(label, copy(frames), null, dependency, true));
            return commands.size() - 1;
        }

        /**
         * Adds an operation which is retried until accepted, then stays completed for the rest of
         * this ApplyEngine plan. This prevents an asynchronous TX77 acceptance from being replayed
         * by the three intentional native-CAN stabilization passes in the same wake.
         */
        int addOnce(String label, Operation operation) {
            return addOperation(label, operation, false);
        }

        /** Adds an OEM operation which may join the existing bounded stabilization passes. */
        int addOperation(String label, Operation operation, boolean repeatOnNextPass) {
            if (label == null || label.isEmpty()) {
                throw new IllegalArgumentException("CAN command label is empty");
            }
            if (operation == null) {
                throw new IllegalArgumentException("CAN operation is null for " + label);
            }
            commands.add(new Command(
                    label, null, operation, NO_DEPENDENCY, repeatOnNextPass));
            return commands.size() - 1;
        }

        CanRestorePlan build() {
            if (commands.isEmpty()) {
                throw new IllegalArgumentException("Restore plan has no CAN commands");
            }
            return new CanRestorePlan(new ArrayList<>(commands));
        }

        private static void validate(String label, byte[][] frames) {
            if (label == null || label.isEmpty()) {
                throw new IllegalArgumentException("CAN command label is empty");
            }
            if (frames == null || frames.length == 0) {
                throw new IllegalArgumentException("No CAN frames for required " + label);
            }
            for (byte[] frame : frames) {
                if (frame == null || frame.length != 10) {
                    throw new IllegalArgumentException("Invalid CAN frame for required " + label);
                }
            }
        }

        private static byte[][] copy(byte[][] frames) {
            byte[][] result = new byte[frames.length][];
            for (int i = 0; i < frames.length; i++) {
                result[i] = Arrays.copyOf(frames[i], frames[i].length);
            }
            return result;
        }
    }

    private static final class Command {
        final String label;
        final byte[][] frames;
        final Operation operation;
        final int dependency;
        final boolean repeatOnNextPass;

        Command(String label, byte[][] frames, Operation operation, int dependency,
                boolean repeatOnNextPass) {
            this.label = label;
            this.frames = frames;
            this.operation = operation;
            this.dependency = dependency;
            this.repeatOnNextPass = repeatOnNextPass;
        }
    }
}
