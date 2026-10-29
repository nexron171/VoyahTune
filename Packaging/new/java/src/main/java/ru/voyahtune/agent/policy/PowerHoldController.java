package ru.voyahtune.agent.policy;

import java.util.function.LongSupplier;

public final class PowerHoldController {
    public interface Manager {
        Boolean parking() throws Exception;
        int battery() throws Exception;
        int status() throws Exception;
        void activate(boolean engineExtender) throws Exception;
    }

    @FunctionalInterface
    public interface ManagerSource {
        Manager ready() throws Exception;
    }

    public static final class Response {
        public final int outcome;
        public final Integer status;

        private Response(int outcome, Integer status) {
            this.outcome = outcome;
            this.status = status;
        }
    }

    private final ManagerSource source;
    private final LongSupplier clock;

    public PowerHoldController(ManagerSource source, LongSupplier clock) {
        this.source = source;
        this.clock = clock;
    }

    public Response request(String operation, long deadline, boolean engineExtender) {
        boolean commandStarted = false;
        try {
            if (deadline <= clock.getAsLong()) return new Response(5, null);
            Manager manager = source.ready();
            int outcome = 5;
            if ("activate".equals(operation)) {
                outcome = validate(manager);
                if (outcome == 1) {
                    int status = manager.status();
                    if (status == 0) {
                        if (deadline <= clock.getAsLong()) return new Response(5, null);
                        commandStarted = true;
                        manager.activate(engineExtender);
                        status = manager.status();
                    }
                    outcome = status == 1 ? 7 : status == 2 ? 6 : 8;
                }
            } else if (!"status".equals(operation)) {
                return new Response(5, null);
            }
            return new Response(outcome, manager.status());
        } catch (Exception failure) {
            return new Response(commandStarted ? 8 : 4, null);
        }
    }

    private int validate(Manager manager) throws Exception {
        Boolean parking = manager.parking();
        if (parking == null) return 4;
        if (!parking) return 2;
        int battery = manager.battery();
        if (battery < 0 || battery > 100) return 4;
        return battery < 15 ? 3 : 1;
    }
}
