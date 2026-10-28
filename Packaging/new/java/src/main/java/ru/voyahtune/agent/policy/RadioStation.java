package ru.voyahtune.agent.policy;

public final class RadioStation {
    public final Boolean fm;
    public final Integer frequency;
    public final Boolean rds;
    public final Integer programmeId;
    public final boolean trafficAnnouncement;
    public final String name;

    public RadioStation(Boolean fm, Integer frequency, Boolean rds, Integer programmeId, boolean trafficAnnouncement, String name) {
        this.fm = fm;
        this.frequency = frequency;
        this.rds = rds;
        this.programmeId = programmeId;
        this.trafficAnnouncement = trafficAnnouncement;
        this.name = name;
    }

    public boolean tunable() {
        return fm != null && frequency != null && (fm ? frequency >= 6500 && frequency <= 10800 : frequency >= 150 && frequency <= 30000);
    }

    public boolean persistable() { return tunable() && rds != null && programmeId != null && programmeId >= 0 && programmeId <= 65535; }
    public boolean sameFrequency(RadioStation other) {
        return other != null && tunable() && other.tunable() && fm.equals(other.fm) && frequency.equals(other.frequency);
    }
    public boolean sameRecord(RadioStation other) {
        return sameFrequency(other) && java.util.Objects.equals(rds, other.rds) && java.util.Objects.equals(programmeId, other.programmeId);
    }
}
