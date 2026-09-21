package com.nick.flowlauncher;

import android.graphics.Bitmap;

public final class TidalMediaState {
    public final boolean active;
    public final String title;
    public final String artist;
    public final boolean playing;
    public final Bitmap artwork;

    public TidalMediaState(boolean active, String title, String artist, boolean playing, Bitmap artwork) {
        this.active = active;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.playing = playing;
        this.artwork = artwork;
    }

    public static TidalMediaState empty() {
        return new TidalMediaState(false, "", "", false, null);
    }
}
