package ru.big.town.anative;

final class FuelFlapWidgetSnapshot {
    final boolean canOpen;
    final Integer refillLiters;
    final String message;

    FuelFlapWidgetSnapshot(boolean canOpen, Integer refillLiters, String message) {
        this.canOpen = canOpen;
        this.refillLiters = refillLiters;
        this.message = message;
    }

    static FuelFlapWidgetSnapshot read(OemVehicleStateTransport.Session session) {
        OemVehicleStateTransport.GearStatus gear = session.readGearStatus();
        boolean parking = gear != null && gear.ordinal == 0 && gear.value == 0;
        String message =
                gear == null
                        ? "Не удалось проверить положение селектора"
                        : parking ? "Нажмите для открытия" : "Переведите селектор в P";
        Integer refillLiters = null;
        try {
            OemVehicleStateTransport.FuelLevel fuel = session.readFuelLevel();
            if (fuel != null) {
                refillLiters =
                        FuelRefillEstimate.liters(fuel.capacityLiters, fuel.remainingPercent);
            }
        } catch (RuntimeException ignored) {
            // Fuel availability is independent of the permission to open in Parking.
        }
        return new FuelFlapWidgetSnapshot(parking, refillLiters, message);
    }
}
