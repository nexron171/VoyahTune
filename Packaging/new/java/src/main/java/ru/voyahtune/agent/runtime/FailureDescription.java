package ru.voyahtune.agent.runtime;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class FailureDescription {
    private static final int MAXIMUM_LENGTH = 2048;
    private static final int MAXIMUM_CAUSES = 8;
    private static final int MAXIMUM_MESSAGE_LENGTH = 256;

    private FailureDescription() {
    }

    public static String format(Throwable failure) {
        StringBuilder result = new StringBuilder();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int depth = 0; failure != null && depth < MAXIMUM_CAUSES && visited.add(failure); depth++) {
            if (result.length() > 0)
                result.append(" <- ");
            result.append(failure.getClass().getName());
            String message = failure.getMessage();
            if (message != null && !message.isEmpty()) {
                result.append(": ").append(message.substring(0, Math.min(message.length(), MAXIMUM_MESSAGE_LENGTH))
                                               .replace('\n', ' ')
                                               .replace('\r', ' '));
            }
            failure = failure.getCause();
            if (result.length() >= MAXIMUM_LENGTH)
                break;
        }
        return result.substring(0, Math.min(result.length(), MAXIMUM_LENGTH));
    }
}
