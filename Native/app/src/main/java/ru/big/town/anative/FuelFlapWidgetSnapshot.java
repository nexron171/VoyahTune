package ru.big.town.anative;

final class FuelFlapWidgetSnapshot {
    final boolean canOpen;
    final Integer refillLiters;
    final OemVehicleStateTransport.FuelLevel fuel;
    final String message;

    FuelFlapWidgetSnapshot(
            boolean canOpen, OemVehicleStateTransport.FuelLevel reading, String message) {
        this.canOpen = canOpen;
        refillLiters =
                reading == null
                        ? null
                        : FuelRefillEstimate.liters(
                                reading.capacityLiters, reading.remainingPercent);
        fuel = refillLiters == null ? null : reading;
        this.message = message;
    }

    static FuelFlapWidgetSnapshot read(OemVehicleStateTransport.Session session) {
        OemVehicleStateTransport.GearStatus gear = session.readGearStatus();
        boolean parking = gear != null && gear.ordinal == 0 && gear.value == 0;
        String message =
                gear == null
                        ? "Не удалось проверить положение селектора"
                        : parking ? "Нажмите для открытия" : "Переведите селектор в P";
        OemVehicleStateTransport.FuelLevel fuel = null;
        try {
            fuel = session.readFuelLevel();
        } catch (RuntimeException ignored) {
            // Fuel availability is independent of the permission to open in Parking.
        }
        return new FuelFlapWidgetSnapshot(parking, fuel, message);
    }
}
