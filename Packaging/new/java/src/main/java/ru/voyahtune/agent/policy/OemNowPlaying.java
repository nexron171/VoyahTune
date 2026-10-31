package ru.voyahtune.agent.policy;

public final class OemNowPlaying {
    public final String packageName;
    public final String title;
    public final String artist;
    public final String appLabel;
    public final int state;
    public final boolean hasArt;
    public final boolean rememberedSource;

    public OemNowPlaying(String packageName, String title, String artist, String appLabel,
            int state, boolean hasArt) {
        this(packageName, title, artist, appLabel, state, hasArt, false);
    }

    public OemNowPlaying(String packageName, String title, String artist, String appLabel,
            int state, boolean hasArt, boolean rememberedSource) {
        this.packageName = text(packageName);
        this.title = text(title);
        this.artist = text(artist);
        this.appLabel = text(appLabel);
        this.state = state;
        this.hasArt = hasArt;
        this.rememberedSource = rememberedSource;
    }

    public boolean overridesOem() {
        return !packageName.isEmpty() && !packageName.equals("android")
                && !packageName.startsWith("com.android.")
                && !packageName.startsWith("com.qinggan.")
                && !packageName.equals("com.pateo.rdsapp")
                && state != 0 && state != 7
                && (rememberedSource || !title.isEmpty() || !artist.isEmpty() || hasArt);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
