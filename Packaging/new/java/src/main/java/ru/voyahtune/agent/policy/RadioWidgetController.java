package ru.voyahtune.agent.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RadioWidgetController {
    public interface IO {
        RadioStation saved() throws Exception;
        RadioStation current() throws Exception;
        boolean ready() throws Exception;
        boolean isPlaying() throws Exception;
        List<RadioStation> list() throws Exception;
        void tune(RadioStation station) throws Exception;
        void pause() throws Exception;
    }

    public static final class Response {
        public boolean fm;
        public int frequency;
        public String name;
        public boolean ready;
        public boolean playing;
        public Boolean canSkip;
        public String message;
        public Boolean accepted;
        public RadioStation requested;
    }

    private final IO io;
    public RadioWidgetController(IO io) { this.io = io; }
    private static boolean valid(RadioStation station) { return station != null && station.tunable() && !station.trafficAnnouncement; }

    private List<RadioStation> stations(RadioStation target) throws Exception {
        List<RadioStation> result = new ArrayList<>();
        List<RadioStation> stations = io.list();
        if (stations != null) for (RadioStation station : stations) if (valid(station) && station.fm.equals(target.fm)) result.add(station);
        return result;
    }

    private Response snapshot() throws Exception {
        RadioStation saved = io.saved();
        RadioStation current = io.current();
        String name = valid(saved) && saved.name != null ? saved.name : "";
        if (valid(saved) && valid(current) && saved.sameFrequency(current) && current.name != null && !current.name.isEmpty()) name = current.name;
        Response response = new Response();
        response.fm = !valid(saved) || saved.fm;
        response.frequency = valid(saved) ? saved.frequency : -1;
        name = name.trim();
        response.name = name.substring(0, Math.min(name.length(), 160));
        response.ready = io.ready();
        response.playing = io.isPlaying();
        response.canSkip = response.ready && saved == null ? null : response.ready && valid(saved) && stations(saved).size() > 1;
        response.message = response.ready ? "" : "Радио не готово";
        return response;
    }

    public Response execute(String action, RadioStation requested) throws Exception {
        Response response = snapshot();
        if ("snapshot".equals(action)) return response;
        response.accepted = false;
        if ("pause".equals(action)) { io.pause(); response.accepted = true; return response; }
        if (!response.ready || !valid(requested)) return response;
        RadioStation target = new RadioStation(requested.fm, requested.frequency, false, 0, false, null);
        if ("next".equals(action) || "previous".equals(action)) {
            List<RadioStation> stations = stations(target);
            if (stations.size() < 2) { response.message = "Нет списка станций"; return response; }
            int found = -1;
            for (int index = 0; index < stations.size(); index++) if (stations.get(index).sameFrequency(target)) { found = index; break; }
            if (found >= 0) target = stations.get((found + ("next".equals(action) ? 1 : stations.size() - 1)) % stations.size());
            else {
                stations.sort(Comparator.comparingInt(station -> station.frequency));
                if ("next".equals(action)) {
                    target = stations.get(0);
                    for (RadioStation station : stations) if (station.frequency > requested.frequency) { target = station; break; }
                } else {
                    target = stations.get(stations.size() - 1);
                    for (int index = stations.size() - 1; index >= 0; index--) if (stations.get(index).frequency < requested.frequency) { target = stations.get(index); break; }
                }
            }
        } else if (!"play".equals(action)) return response;
        io.tune(target);
        response.accepted = true;
        response.requested = target;
        return response;
    }
}
