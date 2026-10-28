package ru.voyahtune.agent.policy;

public final class MediaKeyDelivery {
    @FunctionalInterface
    public interface Transport {
        void send(int action, int keyCode) throws Exception;
    }

    private MediaKeyDelivery() {}

    public static boolean dispatch(int keyCode, Transport transport) {
        try {
            transport.send(0, keyCode);
        } catch (Exception failure) {
            return false;
        }
        try {
            transport.send(1, keyCode);
        } catch (Exception failure) {
            // Replaying DOWN after a delivered press would execute the command twice.
        }
        return true;
    }
}
