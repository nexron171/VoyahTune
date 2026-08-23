package ru.big.town.anative;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One restore pass split into independently retryable native CAN commands.
 *
 * <p>A failed command remains pending while later independent commands are still attempted. This
 * prevents a permanently failing middle command from replaying the already successful prefix on
 * every retry. A new intentional repeat resets the whole plan.</p>
 */
final class CanRestorePlan {
    enum AttemptResult {
        SUCCESS,
        TRANSIENT_FAILURE
    }

    interface Sender {
        boolean send(byte[][] frames, String label);
    }

    private static final int NO_DEPENDENCY = -1;

    private final List<Command> commands;
    private final boolean[] completed;

    private CanRestorePlan(List<Command> commands) {
        this.commands = commands;
        this.completed = new boolean[commands.size()];
    }

    AttemptResult sendPending(Sender sender) {
        for (int i = 0; i < commands.size(); i++) {
            if (completed[i]) continue;
            Command command = commands.get(i);
            if (command.dependency >= 0 && !completed[command.dependency]) continue;
            if (sender.send(command.frames, command.label)) completed[i] = true;
        }
        return isComplete() ? AttemptResult.SUCCESS : AttemptResult.TRANSIENT_FAILURE;
    }

    void resetForNextPass() {
        Arrays.fill(completed, false);
    }

    int pendingCount() {
        int pending = 0;
        for (boolean done : completed) if (!done) pending++;
        return pending;
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
            commands.add(new Command(label, copy(frames), dependency));
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
        final int dependency;

        Command(String label, byte[][] frames, int dependency) {
            this.label = label;
            this.frames = frames;
            this.dependency = dependency;
        }
    }
}
