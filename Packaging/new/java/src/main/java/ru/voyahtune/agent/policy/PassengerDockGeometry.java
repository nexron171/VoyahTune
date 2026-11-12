package ru.voyahtune.agent.policy;

public final class PassengerDockGeometry {
    private PassengerDockGeometry() {}

    public static int[] positions(
            int raisedHeight,
            int availableHeight,
            int[] heights,
            boolean firstAssigned,
            boolean secondAssigned,
            boolean lowered) {
        if (raisedHeight <= 0 || availableHeight <= 0 || heights.length != 6) {
            throw new IllegalArgumentException("Passenger dock dimensions are incomplete");
        }
        long fullHeight = 0;
        long visibleHeight = 0;
        int visibleCount = 0;
        boolean[] visible = {
            true, firstAssigned && !lowered, secondAssigned && !lowered, true, true, true
        };
        for (int index = 0; index < heights.length; index++) {
            if (heights[index] <= 0) {
                throw new IllegalArgumentException("Passenger dock button is not measured");
            }
            fullHeight += heights[index];
            if (visible[index]) {
                visibleHeight += heights[index];
                visibleCount++;
            }
        }
        double gap = (raisedHeight - fullHeight) / 7.0;
        double contentHeight = visibleHeight + (visibleCount - 1) * gap;
        if (gap < 0 || contentHeight > availableHeight) {
            throw new IllegalArgumentException(
                    "Passenger dock buttons exceed the available height");
        }
        double position = (availableHeight - contentHeight) / 2.0;
        int[] positions = new int[heights.length];
        for (int index = 0; index < heights.length; index++) {
            positions[index] = visible[index] ? (int) Math.round(position) : -1;
            if (visible[index]) {
                position += heights[index] + gap;
            }
        }
        return positions;
    }
}
