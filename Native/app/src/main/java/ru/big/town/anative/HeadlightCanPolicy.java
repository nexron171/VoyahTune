package ru.big.town.anative;

/** Pure mapping from the existing boolean headlight target to the OEM VehicleState command. */
final class HeadlightCanPolicy {
    static final int ACTIVATE = 1;

    enum Command {
        LOW_BEAM("LOW_BEAM", 215),
        OUT_LAMP_OFF("OUT_LAMP_OFF", 1096),
        AUTO_LAMP_SWITCH("AUTO_LAMP_SWITCH", 1097);

        final String vehicleStateName;
        final int stableId;

        Command(String vehicleStateName, int stableId) {
            this.vehicleStateName = vehicleStateName;
            this.stableId = stableId;
        }
    }

    private HeadlightCanPolicy() {}

    /** true keeps the historical meaning "manual low beam"; false means explicit exterior-light off. */
    static Command commandFor(boolean headlightsOn) {
        return headlightsOn ? Command.LOW_BEAM : Command.OUT_LAMP_OFF;
    }

    /** Separate steering-wheel pair: true = manual low beam, false = OEM automatic light. */
    static Command commandForAutoPair(boolean lowBeam) {
        return lowBeam ? Command.LOW_BEAM : Command.AUTO_LAMP_SWITCH;
    }
}
