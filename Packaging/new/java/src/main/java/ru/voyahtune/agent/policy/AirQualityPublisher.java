package ru.voyahtune.agent.policy;

import java.math.BigDecimal;

public final class AirQualityPublisher {
    public interface Storage {
        long bootCount();
        String read();
        void write(String value);
    }

    private AirQualityPublisher() {}

    public static void publish(Storage storage, double measured) {
        String value = Double.isFinite(measured) && measured >= 0 && Math.floor(measured) == measured
                ? BigDecimal.valueOf(measured).toBigInteger().toString() : "-1";
        String sample = storage.bootCount() + ":" + value;
        if (!sample.equals(storage.read())) {
            storage.write(sample);
        }
    }
}
