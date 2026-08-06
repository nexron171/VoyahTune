package ru.big.town.anative;

/** Чистая логика выбора следующего значения для циклического действия кнопки руля. */
final class SteeringActionPolicy {
    private SteeringActionPolicy() {}

    static String nextMode(String csv, String current) {
        if (csv == null || csv.trim().isEmpty()) return null;
        String[] raw = csv.split(",");
        java.util.ArrayList<String> modes = new java.util.ArrayList<>();
        for (String value : raw) {
            String mode = value.trim();
            if (!mode.isEmpty()) modes.add(mode);
        }
        if (modes.isEmpty()) return null;
        int currentIndex = modes.indexOf(current);
        return modes.get(currentIndex >= 0 ? (currentIndex + 1) % modes.size() : 0);
    }
}
