package ru.voyahtune.agent.policy;

import java.util.HashMap;
import java.util.Map;

public final class SteeringMedia {
    public interface Transport {
        Decision decide(String command, int keyCode) throws Exception;
        boolean dispatch(int keyCode);
    }

    public static final class Decision {
        public final String route;
        public final int keyCode;

        private Decision(String route, int keyCode) {
            this.route = route;
            this.keyCode = keyCode;
        }
    }

    private final Transport transport;
    private final Map<Integer, Decision> presses = new HashMap<>();

    public SteeringMedia(Transport transport) {
        this.transport = transport;
    }

    public static Decision decision(int physicalKeyCode, String route, int responseKeyCode) {
        if (route == null || !(route.equals("direct") || route.equals("keymanager")
                || route.equals("native") || route.equals("noop"))) {
            return new Decision("native", physicalKeyCode);
        }
        return new Decision(route, route.equals("keymanager") ? physicalKeyCode : responseKeyCode);
    }

    public synchronized boolean consume(int code, int action, int repeatCount) {
        int keyCode;
        String command;
        switch (code) {
            case 3:
                keyCode = 87;
                command = "next";
                break;
            case 4:
                keyCode = 88;
                command = "previous";
                break;
            case 6:
                keyCode = 85;
                command = "play_pause";
                break;
            default:
                return false;
        }
        Decision state = presses.get(code);
        if (action == 0 && repeatCount == 0) {
            try {
                state = transport.decide(command, keyCode);
            } catch (Exception failure) {
                state = null;
            }
            state = state == null ? decision(keyCode, null, keyCode)
                    : decision(keyCode, state.route, state.keyCode);
            if (state.route.equals("keymanager") && !transport.dispatch(state.keyCode)) {
                state = decision(keyCode, "native", keyCode);
            }
            presses.put(code, state);
        }
        if (action == 1) {
            presses.remove(code);
        }
        return state != null && !state.route.equals("native");
    }

    public synchronized void clear() {
        presses.clear();
    }

    public static boolean allowedProxyKey(int code, boolean nativeQinggan) {
        return nativeQinggan ? code == 85 : code == 85 || code == 87 || code == 88 || code == 126 || code == 127;
    }
}
