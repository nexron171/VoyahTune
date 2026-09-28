package ru.big.town.anative;

import static ru.big.town.anative.HeadlightCanPolicy.Command.*;

/** OEM reason -> requested lamp mode; null means no outdoor decision. */
final class AutoLightPolicy {
    static final String EXTENDED_KEY = "extendedAutoLight";
    static final int IHBC_FUNCTION = 141;

    private AutoLightPolicy() {}

    static HeadlightCanPolicy.Command target(int reason, boolean extended, int ihbc) {
        if (!extended) {
            switch (reason) {
                case 0: return OUT_LAMP_OFF;
                case 2: case 3: case 4: return LOW_BEAM;
                default: return null;
            }
        }
        switch (reason) {
            case 0: case 1: return OUT_LAMP_OFF;
            case 2: case 4: return ihbc == 1 ? AUTO_LAMP_SWITCH : LOW_BEAM;
            case 3: case 5: case 6: case 7: return LOW_BEAM;
            default: return null;
        }
    }

    static boolean shouldRestoreLowBeam(HeadlightCanPolicy.Command target) {
        return target == LOW_BEAM;
    }
}
