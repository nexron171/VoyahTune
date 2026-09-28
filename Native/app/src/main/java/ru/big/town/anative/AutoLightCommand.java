package ru.big.town.anative;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Runs on the command queue: read current OEM switch, resolve target, then send. */
final class AutoLightCommand {
    private AutoLightCommand() {}

    static HeadlightCanPolicy.Command send(int reason, boolean extended,
            HeadlightCanPolicy.Command fallback, Supplier<Integer> readIhbc,
            BooleanSupplier isCurrent, Predicate<HeadlightCanPolicy.Command> sender) {
        if (!isCurrent.getAsBoolean()) return null;
        HeadlightCanPolicy.Command target = fallback;
        if (extended) {
            // Never select AUTO from the cached callback value. A missing/invalid switch
            // reading uses LOW for reasons 2/4; it is not evidence of OEM Auto being enabled.
            Integer ihbc = readIhbc.get();
            HeadlightCanPolicy.Command resolved = AutoLightPolicy.target(
                    reason, true, ihbc == null ? -1 : ihbc);
            if (resolved != null) target = resolved;
        }
        // A manual command or a newer policy decision may arrive during the blocking getter.
        if (!isCurrent.getAsBoolean()) return null;
        return sender.test(target) ? target : null;
    }
}
