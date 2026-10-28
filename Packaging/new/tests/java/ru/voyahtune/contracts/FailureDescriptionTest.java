package ru.voyahtune.contracts;

import ru.voyahtune.agent.runtime.FailureDescription;

public final class FailureDescriptionTest {
    public static void main(String[] arguments) {
        Throwable cause = new NoClassDefFoundError("missing.OemDependency");
        Throwable failure = new IllegalStateException("Module preparation failed", cause);
        String description = FailureDescription.format(failure);
        if (!description.contains("IllegalStateException: Module preparation failed")
            || !description.contains("NoClassDefFoundError: missing.OemDependency")) {
            throw new AssertionError(description);
        }
        failure = new IllegalStateException("x".repeat(10000), cause);
        if (FailureDescription.format(failure).length() > 2048)
            throw new AssertionError("Unbounded IPC failure");
        Throwable first = new IllegalStateException("first");
        Throwable second = new IllegalStateException("second", first);
        first.initCause(second);
        if (FailureDescription.format(first).length() > 2048)
            throw new AssertionError("Cyclic failure");
        System.out.println("FailureDescriptionTest: passed");
    }
}
