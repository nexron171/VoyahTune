package ru.big.town.anative;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** OEM AUTO_LAMP_SWITCH is a toggle; never resend it when Auto is already active. */
final class HeadlightAutoCommand {
    private HeadlightAutoCommand() {}

    static boolean send(Supplier<Integer> readAutoLamp, BooleanSupplier sender) {
        Integer auto = readAutoLamp.get();
        if (auto == null || (auto != 0 && auto != 1)) return false;
        return auto == 1 || sender.getAsBoolean();
    }
}
